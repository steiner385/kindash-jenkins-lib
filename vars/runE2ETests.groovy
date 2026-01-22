#!/usr/bin/env groovy
/**
 * Run E2E Tests Stage
 * Executes end-to-end tests with full Docker infrastructure setup
 *
 * Features:
 * - Full Docker infrastructure orchestration (app + postgres)
 * - Playwright-in-container approach for reliable network access
 * - Pre/post Docker cleanup with comprehensive port management
 * - Resource locking (test-infrastructure)
 * - Playwright browser installation with caching
 * - GitHub status reporting
 * - Playwright and Allure report publishing
 *
 * Usage:
 *   runE2ETests()  // Use defaults
 *   runE2ETests(testCommand: 'npm run test:e2e')
 *   runE2ETests(browsers: ['chromium', 'firefox'])
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/e2e'
    def pm = pipelineHelpers.getPackageManager()
    def testCommand = config.testCommand ?: "${pm.run} test:e2e:baseline"
    def lockResource = config.lockResource ?: 'test-infrastructure'
    def composeFile = config.composeFile ?: 'docker/docker-compose.e2e.yml'
    def ports = config.ports ?: [3010, 5433]  // E2E app and postgres ports
    def browsers = config.browsers ?: ['chromium']
    def skipLock = config.skipLock ?: false
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Pre-cleanup: Ensure clean environment
    echo "Cleaning up previous test artifacts and containers..."
    dockerCleanup(
        composeFile: composeFile,
        ports: ports,
        cleanLockfiles: true
    )

    // Aggressive cleanup to avoid 'ContainerConfig' errors
    sh '''
        echo "Cleaning up E2E containers and volumes..."

        # Force remove containers (ignore errors if they don't exist)
        docker stop kindash-e2e-app kindash-e2e-postgres 2>/dev/null || true
        docker rm -f kindash-e2e-app kindash-e2e-postgres 2>/dev/null || true
        docker ps -a -q -f "name=playwright-e2e-runner" | xargs -r docker rm -f 2>/dev/null || true

        # Remove any E2E volumes explicitly
        docker volume ls -q -f "name=docker_" | grep -E "(postgres|e2e)" | xargs -r docker volume rm -f 2>/dev/null || true

        # Remove network if it exists
        docker network rm kindash-e2e-network 2>/dev/null || true

        # Export BUILD_NUMBER for unique container names
        export BUILD_NUMBER=${BUILD_NUMBER}

        # Give Docker daemon time to process removals
        sleep 2
    '''

    // Remove volumes and networks for clean start (should be a no-op after explicit cleanup)
    dockerCompose.safe('down -v --remove-orphans', composeFile)

    // Aggressive cleanup: Remove all dangling Docker resources
    sh '''
        echo "Running system prune to remove dangling resources..."
        docker system prune --force --volumes 2>/dev/null || true
        sleep 2
    '''

    // Kill any processes on E2E ports
    sh '''
        echo "Cleaning up ports..."
        for port in 5433 3010; do
            fuser -k $port/tcp 2>/dev/null || true
        done
        sleep 2
    '''

    try {
        // Define the test execution closure
        def runTests = {
            // Export BUILD_NUMBER for unique container names to avoid Docker metadata conflicts
            env.BUILD_NUMBER = env.BUILD_NUMBER ?: 'local'

            // Report pending status
            githubStatusReporter(
                status: 'pending',
                context: statusContext,
                description: 'E2E tests running'
            )

            // Verify cleanup was successful
            sh '''
                echo "Verifying cleanup..."
                if docker ps -a | grep -E "kindash-e2e-(app|postgres)-${BUILD_NUMBER}"; then
                    echo "ERROR: Found existing E2E containers after cleanup!"
                    docker ps -a | grep "kindash-e2e"
                    exit 1
                fi
                echo "Cleanup verification passed"
            '''

            // Build Docker images
            echo "Building E2E Docker images..."
            dockerCompose('build --parallel', composeFile)

            // Start E2E infrastructure (app + postgres)
            echo "Starting E2E infrastructure..."
            dockerCompose('up -d', composeFile)

            // Wait for postgres to be ready
            echo "Waiting for PostgreSQL..."
            sh '''
                for i in $(seq 1 30); do
                    docker exec kindash-e2e-postgres-${BUILD_NUMBER} pg_isready -U testuser -d kindash_e2e_test && break
                    echo "Waiting for postgres... $i/30"
                    sleep 2
                done
            '''

            // Wait for app using curl container on the E2E network
            echo "Waiting for app to be healthy..."
            sh '''
                APP_HEALTHY=false
                for i in $(seq 1 60); do
                    # Check health from INSIDE the Docker network using curl container
                    if docker run --rm --network kindash-e2e-network-${BUILD_NUMBER} curlimages/curl:latest \
                        curl -f -s "http://kindash-e2e-app-${BUILD_NUMBER}:3010/api/health" > /dev/null 2>&1; then
                        echo "App is healthy on E2E network!"
                        APP_HEALTHY=true
                        break
                    fi
                    echo "Waiting for app... $i/60"
                    sleep 2
                done

                if [ "$APP_HEALTHY" = "false" ]; then
                    echo "App failed to start:"
                    docker logs kindash-e2e-app-${BUILD_NUMBER} --tail 100
                    exit 1
                fi
            '''

            // Install Playwright browsers if not cached
            playwrightSetup(browsers: browsers)

            // Run Playwright tests INSIDE a Docker container on the same network
            echo "Running Playwright tests inside Docker container..."
            sh '''
                CONTAINER_NAME="playwright-e2e-runner-$$"

                echo "Creating Playwright container: $CONTAINER_NAME"
                echo "Network: kindash-e2e-network-${BUILD_NUMBER}"
                echo "Base URL: http://kindash-e2e-app-${BUILD_NUMBER}:3010"

                # Create Playwright container on the E2E network
                docker run -d \
                    --name "$CONTAINER_NAME" \
                    --network kindash-e2e-network-${BUILD_NUMBER} \
                    -w /app \
                    -e CI=true \
                    -e E2E_DOCKER=true \
                    -e PLAYWRIGHT_BASE_URL=http://kindash-e2e-app-${BUILD_NUMBER}:3010 \
                    -e USE_EXISTING_SERVER=true \
                    -e E2E_BASE_URL=http://kindash-e2e-app-${BUILD_NUMBER}:3010 \
                    -e BASE_URL=http://kindash-e2e-app-${BUILD_NUMBER}:3010 \
                    -e PORT=3010 \
                    mcr.microsoft.com/playwright:v1.57.0-noble \
                    sleep infinity

                # Copy project files using tar to handle any symlinks
                echo "Copying project files to container..."
                tar -chf - \
                    --exclude=node_modules \
                    --exclude=.git \
                    --exclude=dist-* \
                    --exclude=coverage \
                    --exclude=playwright-report \
                    --exclude=test-results \
                    --exclude=allure-results \
                    . | docker exec -i "$CONTAINER_NAME" tar -xf - -C /app/

                echo "Files copied. Installing dependencies..."

                # Run npm install and Playwright tests inside the container
                docker exec "$CONTAINER_NAME" bash -c "
                    echo 'Installing npm dependencies...'
                    npm config set registry https://registry.npmjs.org
                    npm install --legacy-peer-deps --no-audit --no-fund 2>&1 || {
                        echo 'npm install failed'
                        exit 1
                    }

                    echo 'Running Playwright tests...'
                    echo 'Base URL: \$E2E_BASE_URL'
                    echo '=========================================='

                    npx playwright test --reporter=list,junit,allure-playwright
                " || {
                    EXIT_CODE=$?
                    echo "Playwright tests exited with code $EXIT_CODE"

                    # Copy results even on failure
                    docker cp "$CONTAINER_NAME":/app/playwright-report ./playwright-report 2>/dev/null || true
                    docker cp "$CONTAINER_NAME":/app/test-results ./test-results 2>/dev/null || true
                    docker cp "$CONTAINER_NAME":/app/allure-results ./allure-results 2>/dev/null || true

                    # Cleanup container
                    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
                    exit $EXIT_CODE
                }

                # Copy test results back to workspace
                echo "Copying test results..."
                docker cp "$CONTAINER_NAME":/app/playwright-report ./playwright-report 2>/dev/null || true
                docker cp "$CONTAINER_NAME":/app/test-results ./test-results 2>/dev/null || true
                docker cp "$CONTAINER_NAME":/app/allure-results ./allure-results 2>/dev/null || true

                # Cleanup container
                docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

                echo "Playwright tests completed successfully"
            '''
        }

        // Run tests with or without lock
        if (skipLock) {
            runTests()
        } else {
            lock(resource: lockResource, inversePrecedence: true) {
                runTests()
            }
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'E2E tests passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'E2E tests failed'
        )

        // Show service logs for debugging
        echo "=== Service Logs (last 50 lines) ==="
        dockerCompose.safe('logs --tail=50', composeFile)

        throw e

    } finally {
        // Always publish reports
        publishReports(
            playwright: true,
            allure: true
        )

        // Post-cleanup: Ensure Docker containers and ports are freed
        echo "Post-test cleanup..."
        sh 'docker ps -a -q -f "name=playwright-e2e-runner" | xargs -r docker rm -f 2>/dev/null || true'
        dockerCompose.safe('down -v', composeFile)

        dockerCleanup(
            composeFile: composeFile,
            ports: ports,
            cleanLockfiles: false
        )
    }
}

/**
 * Run E2E tests on specific browsers
 */
def withBrowsers(List browsers, Map config = [:]) {
    call(config + [browsers: browsers])
}

/**
 * Run E2E tests with visual regression
 */
def withVisualRegression(Map config = [:]) {
    call(config)

    // Run visual regression tests after E2E
    script {
        def pm = pipelineHelpers.getPackageManager()
        def visualResult = sh(script: "${pm.run} test:visual", returnStatus: true)
        if (visualResult != 0) {
            unstable('Visual regression tests detected differences')
        }
    }

    archiveArtifacts artifacts: 'visual-regression/**', allowEmptyArchive: true
}

return this
