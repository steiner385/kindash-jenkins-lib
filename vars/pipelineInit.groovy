#!/usr/bin/env groovy
/**
 * Pipeline Initialization Stage
 * Handles checkout, git info extraction, and initial status reporting
 *
 * Features:
 * - Checkout SCM
 * - Extract git commit info (full hash, short hash, branch name)
 * - Report pending status to GitHub
 *
 * Usage:
 *   pipelineInit()  // Use defaults
 *   pipelineInit(statusContext: 'jenkins/e2e', statusDescription: 'E2E tests started')
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/ci'
    def statusDescription = config.statusDescription ?: 'Pipeline started'
    def skipCheckout = config.skipCheckout ?: false
    def skipStatus = config.skipStatus ?: false

    // Checkout source code
    if (!skipCheckout) {
        checkout scm
    }

    // Extract git information
    script {
        env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
        env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        env.GIT_BRANCH_NAME = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()

        echo "Git commit: ${env.GIT_COMMIT_SHORT} (${env.GIT_BRANCH_NAME})"
    }

    // Report pending status to GitHub
    if (!skipStatus) {
        githubStatusReporter(
            status: 'pending',
            context: statusContext,
            description: statusDescription
        )
    }
}

/**
 * Initialize for E2E tests
 */
def forE2E() {
    call(statusContext: 'jenkins/e2e', statusDescription: 'E2E tests started')
}

/**
 * Initialize for unit tests
 */
def forUnitTests() {
    call(statusContext: 'jenkins/unit-tests', statusDescription: 'Unit tests started')
}

/**
 * Initialize for integration tests
 */
def forIntegrationTests() {
    call(statusContext: 'jenkins/integration', statusDescription: 'Integration tests started')
}

return this
