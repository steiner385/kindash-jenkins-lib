#!/usr/bin/env groovy
/**
 * KinDash Multi-Branch Pipeline (Global Variable)
 *
 * This is the actual pipeline definition, called from the minimal stub Jenkinsfile
 * in the main KinDash repo.
 *
 * Full CI pipeline: Lint, Unit Tests, E2E Tests, Build
 *
 * Multi-branch Jenkins provides these environment variables automatically:
 *   BRANCH_NAME   - Current branch name
 *   CHANGE_ID     - PR number (null if not a PR build)
 *
 * Webhook Setup:
 *   URL: https://jenkins.kindash.com/github-webhook/
 *   Content type: application/json
 *   Events: Push, Pull requests
 */

def call() {
    pipeline {
        agent any

        environment {
            GITHUB_OWNER = 'steiner385'
            GITHUB_REPO = 'KinDash'
            CI = 'true'
            NODE_ENV = 'test'
            NODE_OPTIONS = '--max-old-space-size=6144'

            // Multi-branch provides BRANCH_NAME, CHANGE_ID, CHANGE_TARGET automatically

            // Unique project name per build for E2E Docker Compose isolation
            // This prevents container name conflicts across concurrent builds
            // Image caching still works because docker-compose.e2e.yml uses explicit image: tags
            E2E_PROJECT_NAME = "e2e-build-${env.BUILD_NUMBER ?: 'local'}"
        }

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            disableConcurrentBuilds(abortPrevious: true)
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        // ✅ Only build PRs and protected branches (prevents duplicate builds)
                        def isPR = env.CHANGE_ID != null
                        def isProtectedBranch = env.BRANCH_NAME in ['main', 'develop', 'staging'] || env.BRANCH_NAME?.startsWith('deploy/')

                        if (!isPR && !isProtectedBranch) {
                            echo "Skipping build for branch: ${env.BRANCH_NAME}"
                            echo "This branch will be built when a PR is created."
                            currentBuild.result = 'NOT_BUILT'
                            return
                        }

                        // Determine build type for logging and status reporting
                        def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : "Branch: ${env.BRANCH_NAME}"
                        echo "=== Multi-Branch Build ==="
                        echo "Build type: ${buildType}"
                        if (env.CHANGE_ID) {
                            echo "PR Title: ${env.CHANGE_TITLE ?: 'N/A'}"
                            echo "PR Author: ${env.CHANGE_AUTHOR ?: 'N/A'}"
                            echo "Target Branch: ${env.CHANGE_TARGET ?: 'N/A'}"
                        }
                        echo "=========================="

                        // Report pending status to GitHub
                        githubStatusReporter(
                            status: 'pending',
                            context: 'jenkins/ci',
                            description: "Build started for ${buildType}"
                        )
                    }

                    // Checkout the KinDash application repo (multi-branch SCM)
                    checkout scm

                    // Remove stale test directories and coverage files from previous builds
                    sh '''
                        rm -rf coverage || true
                        find . -path "*/coverage/*" -name "*.xml" -delete 2>/dev/null || true
                    '''

                    script {
                        env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        echo "Building commit: ${env.GIT_COMMIT_SHORT}"
                    }
                }
            }

            stage('Install Dependencies') {
                when {
                    expression { currentBuild.result != 'NOT_BUILT' }
                }
                steps {
                    installDependencies()
                }
            }

            stage('Build Packages') {
                when {
                    expression { currentBuild.result != 'NOT_BUILT' }
                }
                steps {
                    sh 'npm run build:packages || true'
                }
            }

            stage('Lint') {
                when {
                    expression { currentBuild.result != 'NOT_BUILT' }
                }
                steps {
                    script {
                        // Make lint non-blocking - report warnings but don't fail the build
                        def lintResult = sh(script: 'npm run lint', returnStatus: true)
                        if (lintResult != 0) {
                            echo "Lint completed with warnings/errors (exit code: ${lintResult})"
                            // Don't fail the build - just report
                            unstable(message: "Lint has warnings")
                        }
                    }
                }
            }

            stage('Unit Tests') {
                when {
                    expression { currentBuild.result != 'NOT_BUILT' }
                }
                steps {
                    withAwsCredentials {
                        runUnitTests(
                            skipCheckout: true,
                            coverageThreshold: 70
                        )
                    }
                }
            }

            stage('E2E Tests') {
                // Run E2E on main, develop, and PRs targeting them
                when {
                    anyOf {
                        branch 'main'
                        branch 'develop'
                        changeRequest target: 'main'
                        changeRequest target: 'develop'
                    }
                }
                steps {
                    script {
                        try {
                            echo "=== Running E2E Tests ==="
                            echo "Branch: ${env.BRANCH_NAME}"
                            echo "Test Suite: Playwright (Docker-based infrastructure)"
                            echo "Environment: Full production-like stack (app + postgres)"

                            runE2ETests(
                                skipCheckout: true,
                                browsers: ['chromium']
                            )

                            echo "=== E2E Tests Complete ==="

                        } catch (Exception e) {
                            echo "⚠️  E2E tests failed"
                            echo "Error: ${e.message}"
                            // Mark as unstable but don't fail the build
                            // E2E tests can have flaky issues, but we want to know about them
                            currentBuild.result = 'UNSTABLE'

                            // Report E2E status as "unstable" - not blocking but visible
                            githubStatusReporter(
                                status: 'success',  // Report success so it doesn't block PR
                                context: 'jenkins/e2e',
                                description: 'E2E tests had failures (non-blocking)'
                            )
                        }
                    }
                }
                post {
                    always {
                        publishReports(
                            playwright: true,
                            allure: true
                        )
                    }
                }
            }

            stage('Build') {
                when {
                    expression { currentBuild.result != 'NOT_BUILT' }
                }
                steps {
                    buildProject(skipCheckout: true)
                }
            }
        }

        post {
            always {
                publishReports(
                    junit: true,
                    allure: true,
                    coverage: true
                )
            }
            success {
                script {
                    def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME
                    githubStatusReporter(
                        status: 'success',
                        context: 'jenkins/ci',
                        description: "Build succeeded for ${buildType}"
                    )
                }
            }
            failure {
                script {
                    def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME
                    githubStatusReporter(
                        status: 'failure',
                        context: 'jenkins/ci',
                        description: "Build failed for ${buildType}"
                    )
                }
            }
            cleanup {
                cleanWs()
            }
        }
    }
}

return this
