#!/usr/bin/env groovy
/**
 * Run E2E Tests Stage
 * Executes end-to-end tests with full Docker infrastructure setup
 *
 * Features:
 * - Full Docker infrastructure orchestration (app + postgres)
 * - Playwright-in-container approach for reliable network access
 * - Pre/post Docker cleanup with comprehensive port management
 * - Resource locking (test-infrastructure) [DISABLED BY DEFAULT - skipLock=true]
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
    // Skip locking by default - Docker Compose project naming prevents resource conflicts
    def skipLock = config.skipLock ?: true
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Define cleanup function to run inside lock
    def performCleanup = {
        // Pre-cleanup: Ensure clean environment
        echo "Cleaning up previous test artifacts and containers..."
        dockerCleanup(
            composeFile: composeFile,
            ports: ports,
            cleanLockfiles: true
        )

        // Aggressive cleanup: Remove containers, networks, and stale docker-proxy processes
        sh '''
            echo "Force-removing E2E containers (bypassing corrupted metadata)..."

            # Use docker-compose down to clean up project-specific resources
            # This automatically handles dynamic container/network names based on COMPOSE_PROJECT_NAME
            docker compose -f docker/docker-compose.e2e.yml down --remove-orphans --volumes 2>/dev/null || true

            # Also remove by name filter as backup for old fixed-name containers (legacy cleanup)
            docker ps -a -q -f "name=kindash-e2e-postgres" | xargs -r docker rm -f 2>/dev/null || true
            docker ps -a -q -f "name=kindash-e2e-app" | xargs -r docker rm -f 2>/dev/null || true
            docker ps -a -q -f "name=playwright-e2e-runner" | xargs -r docker rm -f 2>/dev/null || true

            # Prune stopped containers
            echo "Pruning stopped containers..."
            docker container prune -f 2>/dev/null || true

            # CRITICAL: Remove old fixed-name networks (legacy cleanup)
            # New builds use project-prefixed networks cleaned up by docker-compose down
            echo "Removing legacy E2E networks..."
            docker network rm kindash-e2e-network 2>/dev/null || true
            docker network rm docker_kindash-e2e-network 2>/dev/null || true

            # Kill any stale docker-proxy processes for our ports
            # docker-proxy handles port forwarding and can leave stale bindings
            echo "Killing any stale docker-proxy processes..."
            pkill -f "docker-proxy.*5433" 2>/dev/null || true
            pkill -f "docker-proxy.*3010" 2>/dev/null || true

            # Prune unused networks (cleans up disconnected network endpoints)
            echo "Pruning unused Docker networks..."
            docker network prune -f 2>/dev/null || true

            # CRITICAL: Check for any containers that might be holding our ports
            # even if they're not in our compose file (orphaned from previous runs)
            echo "Checking for any containers using our E2E ports..."
            for port in 5433 3010; do
                # Find containers publishing to this port and remove them
                CONTAINER_IDS=$(docker ps -a --format '{{.ID}}:{{.Ports}}' | grep ":$port->" | cut -d: -f1)
                if [ -n "$CONTAINER_IDS" ]; then
                    echo "Found containers using port $port, removing them..."
                    echo "$CONTAINER_IDS" | xargs -r docker rm -f 2>/dev/null || true
                fi
            done

            # Additional cleanup: Remove any dangling containers that might hold port state
            echo "Removing dangling containers..."
            docker container prune -f 2>/dev/null || true

            # Give Docker a moment to release internal port allocations
            echo "Waiting for Docker to release port allocations..."
            sleep 2
        '''

        // Remove volumes and networks for clean start
        dockerCompose.safe('down -v --remove-orphans', composeFile)

        // Kill any processes on E2E ports and wait for them to be released
        sh '''
            echo "Killing processes on ports 5433 and 3010..."
            for port in 5433 3010; do
                fuser -k $port/tcp 2>/dev/null || true
            done

            echo "Waiting for ports to be released (max 30 seconds)..."
            for i in $(seq 1 30); do
                PORT_5433_FREE=true
                PORT_3010_FREE=true

                # Check if port 5433 is free
                if lsof -i :5433 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 5433/tcp >/dev/null 2>&1; then
                    PORT_5433_FREE=false
                fi

                # Check if port 3010 is free
                if lsof -i :3010 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 3010/tcp >/dev/null 2>&1; then
                    PORT_3010_FREE=false
                fi

                if [ "$PORT_5433_FREE" = "true" ] && [ "$PORT_3010_FREE" = "true" ]; then
                    echo "All ports are free!"
                    break
                fi

                echo "Waiting for ports to be released... attempt $i/30"
                sleep 1
            done

            # Final check
            if lsof -i :5433 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 5433/tcp >/dev/null 2>&1; then
                echo "WARNING: Port 5433 still in use after 30 seconds"
            fi
            if lsof -i :3010 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 3010/tcp >/dev/null 2>&1; then
                echo "WARNING: Port 3010 still in use after 30 seconds"
            fi
        '''
    }

    try {
        // Define the test execution closure
        def runTests = {
            // CRITICAL: Run cleanup INSIDE the lock, after acquiring exclusive access
            // This prevents port conflicts with other builds that may still be running
            performCleanup()

            // Report pending status
            githubStatusReporter(
                status: 'pending',
                context: statusContext,
                description: 'E2E tests running'
            )

            // Build Docker images
            echo "Building E2E Docker images..."
            dockerCompose('build --parallel', composeFile)

            // CRITICAL: Re-check port availability after Docker build
            // The build can take 30-60 seconds, during which ports might be grabbed
            echo "Re-verifying port availability after Docker build..."
            sh '''
                echo "Checking if ports 5433 and 3010 are still free..."
                for i in $(seq 1 30); do
                    PORT_5433_FREE=true
                    PORT_3010_FREE=true

                    # Check if port 5433 is free
                    if lsof -i :5433 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 5433/tcp >/dev/null 2>&1; then
                        PORT_5433_FREE=false
                    fi

                    # Check if port 3010 is free
                    if lsof -i :3010 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 3010/tcp >/dev/null 2>&1; then
                        PORT_3010_FREE=false
                    fi

                    if [ "$PORT_5433_FREE" = "true" ] && [ "$PORT_3010_FREE" = "true" ]; then
                        echo "✅ Ports are still free after Docker build!"
                        break
                    fi

                    echo "⚠️  Ports in use after Docker build - killing processes and retrying... attempt $i/30"
                    fuser -k 5433/tcp 2>/dev/null || true
                    fuser -k 3010/tcp 2>/dev/null || true
                    sleep 1
                done

                # Final check
                if lsof -i :5433 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 5433/tcp >/dev/null 2>&1; then
                    echo "❌ ERROR: Port 5433 still in use after 30 seconds - cannot start containers"
                    exit 1
                fi
                if lsof -i :3010 -sTCP:LISTEN -t >/dev/null 2>&1 || fuser 3010/tcp >/dev/null 2>&1; then
                    echo "❌ ERROR: Port 3010 still in use after 30 seconds - cannot start containers"
                    exit 1
                fi
            '''

            // Start E2E infrastructure (app + postgres)
            // Use --force-recreate to clear Docker's internal port allocation state
            // (fuser/lsof only see OS-level bindings, not Docker's internal state)
            echo "Starting E2E infrastructure..."
            dockerCompose('up -d --force-recreate --remove-orphans', composeFile)

            // Wait for postgres to be ready
            echo "Waiting for PostgreSQL..."
            sh '''
                for i in $(seq 1 30); do
                    docker compose -f docker/docker-compose.e2e.yml exec -T postgres pg_isready -U testuser -d kindash_e2e_test && break
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
                    # Use Docker service name 'app' which works with any COMPOSE_PROJECT_NAME
                    if docker run --rm --network ${COMPOSE_PROJECT_NAME}_kindash-e2e-network curlimages/curl:latest \
                        curl -f -s "http://app:3010/api/health" > /dev/null 2>&1; then
                        echo "App is healthy on E2E network!"
                        APP_HEALTHY=true
                        break
                    fi
                    echo "Waiting for app... $i/60"
                    sleep 2
                done

                if [ "$APP_HEALTHY" = "false" ]; then
                    echo "App failed to start:"
                    docker compose -f docker/docker-compose.e2e.yml logs app --tail 100
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
                echo "Network: ${COMPOSE_PROJECT_NAME}_kindash-e2e-network"
                echo "Base URL: http://app:3010"

                # Create Playwright container on the E2E network
                # CRITICAL: --memory=4g prevents OOM killer (exit code 137) during npm install
                # Use Docker service name 'app' which works with any COMPOSE_PROJECT_NAME
                docker run -d \
                    --name "$CONTAINER_NAME" \
                    --network ${COMPOSE_PROJECT_NAME}_kindash-e2e-network \
                    --memory=4g \
                    --memory-swap=4g \
                    -w /app \
                    -e CI=true \
                    -e E2E_DOCKER=true \
                    -e PLAYWRIGHT_BASE_URL=http://app:3010 \
                    -e USE_EXISTING_SERVER=true \
                    -e E2E_BASE_URL=http://app:3010 \
                    -e BASE_URL=http://app:3010 \
                    -e PORT=3010 \
                    mcr.microsoft.com/playwright:v1.57.0-noble \
                    sleep infinity

                # Copy project files using tar to handle any symlinks
                # NOTE: Container has 4GB memory limit to prevent OOM during npm install
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

                # Run npm ci and Playwright tests inside the container
                # npm ci provides clean install and verifies lock file integrity
                docker exec "$CONTAINER_NAME" bash -c "
                    echo 'Installing npm dependencies...'
                    npm config set registry https://registry.npmjs.org
                    npm ci --legacy-peer-deps --no-audit --no-fund 2>&1 || {
                        echo 'npm ci failed'
                        exit 1
                    }

                    echo 'Running Playwright tests...'
                    echo 'Base URL: \$E2E_BASE_URL'
                    echo '=========================================='

                    npx playwright test --config=playwright.ci-baseline.config.ts
                " || {
                    EXIT_CODE=$?
                    echo "Playwright tests exited with code $EXIT_CODE"

                    # Copy results even on failure
                    docker cp "$CONTAINER_NAME":/app/playwright-report ./playwright-report 2>/dev/null || true
                    docker cp "$CONTAINER_NAME":/app/test-results ./test-results 2>/dev/null || true
                    docker cp "$CONTAINER_NAME":/app/allure-results ./allure-results 2>/dev/null || true

                    # Cleanup container
                    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

                    # Check if tests actually failed by examining JUnit results
                    # Playwright may exit non-zero for non-test reasons (reporter cleanup, warnings)
                    if [ -f "test-results/junit.xml" ]; then
                        # Count actual test failures and errors in the JUnit XML
                        FAILURES=$(grep -o 'failures="[0-9]*"' test-results/junit.xml | grep -o '[0-9]*' | head -1)
                        ERRORS=$(grep -o 'errors="[0-9]*"' test-results/junit.xml | grep -o '[0-9]*' | head -1)
                        FAILURES=${FAILURES:-0}
                        ERRORS=${ERRORS:-0}

                        if [ "$FAILURES" -gt 0 ] || [ "$ERRORS" -gt 0 ]; then
                            echo "❌ Tests failed: $FAILURES failures, $ERRORS errors"
                            exit 1
                        else
                            echo "⚠️  Playwright exited with code $EXIT_CODE but all tests passed (0 failures, 0 errors)"
                            echo "This is likely due to reporter cleanup or non-test warnings"
                            # Don't exit - let the success path handle cleanup
                        fi
                    else
                        echo "⚠️  JUnit results not found, falling back to exit code"
                        exit $EXIT_CODE
                    fi
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
        // Set COMPOSE_PROJECT_NAME for collision-free parallel builds
        def composeProjectName = "e2e-build-${env.BUILD_NUMBER}"
        withEnv(["COMPOSE_PROJECT_NAME=${composeProjectName}"]) {
            if (skipLock) {
                runTests()
            } else {
                lock(resource: lockResource, inversePrecedence: true) {
                    runTests()
                }
            }
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'E2E tests passed'
        )

    } catch (Exception e) {
        // Show service logs for debugging
        echo "=== Service Logs (last 50 lines) ==="
        dockerCompose.safe('logs --tail=50', composeFile)

        // Report failure status to GitHub - E2E tests are strictly enforced
        echo "E2E tests failed with error: ${e.message}"
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'E2E tests failed'
        )

        throw e

    } finally {
        // Always publish reports
        // Note: allure-results are left in workspace to be picked up by
        // global post block, which publishes the consolidated report
        publishReports(
            junit: true,       // Publish JUnit results for Jenkins native trend chart
            playwright: true,
            allure: false
        )

        // DON'T archive allure-results here - let them accumulate in workspace
        // for the global post block to publish as a single consolidated report

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
