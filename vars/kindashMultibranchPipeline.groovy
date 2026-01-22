#!/usr/bin/env groovy
/**
 * KinDash Multi-Branch Pipeline (Global Variable)
 *
 * This is the actual pipeline definition, called from the minimal stub Jenkinsfile
 * in the main KinDash repo.
 *
 * Full CI pipeline: Lint, Unit Tests, Build
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
                        def isProtectedBranch = env.BRANCH_NAME in ['main', 'staging'] || env.BRANCH_NAME?.startsWith('deploy/')

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
