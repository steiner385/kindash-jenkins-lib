#!/usr/bin/env groovy
/**
 * Run Integration Tests Stage
 * Executes integration tests with Docker cleanup and resource locking
 *
 * Features:
 * - Pre/post Docker cleanup
 * - Resource locking (test-infrastructure)
 * - GitHub status reporting
 * - JUnit report publishing
 *
 * Usage:
 *   runIntegrationTests()  // Use defaults
 *   runIntegrationTests(testCommand: 'npm run test:integration')
 *   runIntegrationTests(lockResource: 'custom-lock')
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/integration'
    def pm = pipelineHelpers.getPackageManager()
    def testCommand = config.testCommand ?: "${pm.run} test:integration"
    def lockResource = config.lockResource ?: 'test-infrastructure'
    def composeFile = config.composeFile ?: 'deployment/docker/docker-compose.test.yml'
    def ports = config.ports ?: pipelineHelpers.getServicePorts()
    def skipLock = config.skipLock ?: false
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Pre-cleanup
    dockerCleanup(
        composeFile: composeFile,
        ports: ports,
        cleanLockfiles: true
    )

    // Clean previous results
    sh 'rm -rf allure-results'

    // Report pending status
    githubStatusReporter(
        status: 'pending',
        context: statusContext,
        description: 'Integration tests running'
    )

    try {
        // Run tests with or without lock
        if (skipLock) {
            sh testCommand
        } else {
            lock(resource: lockResource, inversePrecedence: true) {
                sh testCommand
            }
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'Integration tests passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'Integration tests failed'
        )
        throw e

    } finally {
        // Always publish reports and cleanup
        // Note: Allure results are archived here, but Allure report is published
        // once in the global post block to avoid duplicate trend charts
        publishReports(junit: true, allure: false)

        // Archive allure-results so they're available for final report
        if (fileExists('allure-results')) {
            archiveArtifacts artifacts: 'allure-results/**', allowEmptyArchive: true
        }

        dockerCleanup(
            composeFile: composeFile,
            ports: ports,
            cleanLockfiles: false
        )
    }
}

return this
