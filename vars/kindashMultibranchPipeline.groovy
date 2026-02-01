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
 * Branch Filtering:
 *   - Only PRs and protected branches (main, develop, staging, deploy/*) are built
 *   - Other branch pushes are skipped immediately without allocating an executor
 *   - To prevent build entries entirely, configure Branch Source in Jenkins UI:
 *     Configure > Branch Sources > GitHub > Behaviors > Filter by name (with regular expression)
 *     Include: (main|develop|staging|deploy/.*)
 *
 * Webhook Setup:
 *   URL: https://jenkins.kindash.com/github-webhook/
 *   Content type: application/json
 *   Events: Push, Pull requests
 */

def call() {
    // Pre-flight branch check (runs without allocating an agent)
    def isPR = env.CHANGE_ID != null
    def isProtectedBranch = env.BRANCH_NAME in ['main', 'develop', 'staging'] || env.BRANCH_NAME?.startsWith('deploy/')

    if (!isPR && !isProtectedBranch) {
        echo "⏭️ Skipping build for branch: ${env.BRANCH_NAME}"
        echo "This branch will be built when a PR is created."
        echo ""
        echo "To prevent these build entries entirely, configure Branch Source filtering in Jenkins:"
        echo "  Configure > Branch Sources > Behaviors > Filter by name (with regular expression)"
        echo "  Include: (main|develop|staging|deploy/.*)"
        currentBuild.result = 'NOT_BUILT'
        currentBuild.description = "Skipped: feature branch (no PR)"
        return
    }

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
                steps {
                    installDependencies()
                }
            }

            stage('Build Packages') {
                steps {
                    sh 'npm run build:packages || true'
                }
            }

            stage('Lint') {
                steps {
                    // Use runLintChecks for GitHub status reporting
                    // Wrap in catchError to make lint non-blocking
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        runLintChecks(
                            skipCheckout: true,
                            skipTypeCheck: true
                        )
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    withAwsCredentials {
                        runUnitTests(
                            skipCheckout: true,
                            coverageThreshold: 70
                        )
                    }
                }
            }

            stage('Integration Tests') {
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "=== Running Integration Tests ==="
                            runIntegrationTests(
                                testCommand: 'npm run test:integration',
                                statusContext: 'jenkins/integration'
                            )
                        }
                    }
                }
            }

            stage('E2E Tests') {
                // Run E2E on main, develop, and PRs targeting them
                // E2E tests are REQUIRED - failures will block PR merges
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
                        echo "=== Running E2E Tests (REQUIRED) ==="
                        echo "Branch: ${env.BRANCH_NAME}"
                        echo "Test Suite: Playwright (Docker-based infrastructure)"
                        echo "Environment: Full production-like stack (app + postgres)"
                        echo "Note: E2E tests must pass for PR to be mergeable"

                        // E2E tests are strictly enforced - failures will fail the build
                        runE2ETests(
                            skipCheckout: true,
                            browsers: ['chromium']
                        )

                        echo "=== E2E Tests Passed ==="
                    }
                }
                post {
                    always {
                        // Note: runE2ETests handles all test artifact archiving
                        // including allure-results. Allure report is published once
                        // in the global post block to avoid duplicate trend charts.
                        echo "E2E test artifacts archived by runE2ETests"
                    }
                }
            }

            stage('Build') {
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
