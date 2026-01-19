#!/usr/bin/env groovy
/**
 * Run Unit Tests Stage
 * Executes unit tests with coverage reporting and threshold checks
 *
 * Features:
 * - GitHub status reporting (pending/success/failure)
 * - Coverage threshold enforcement
 * - JUnit and coverage report publishing
 * - Allure report support
 *
 * Usage:
 *   runUnitTests()  // Use defaults
 *   runUnitTests(testCommand: 'npm run test:unit -- --coverage')
 *   runUnitTests(coverageThreshold: 80)
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/unit-tests'
    def testCommand = config.testCommand ?: 'npm run test:unit -- --coverage'
    def coverageThreshold = config.coverageThreshold ?: 70
    def coverageDir = config.coverageDir ?: 'coverage/lcov-report'
    def enableAllure = config.enableAllure != null ? config.enableAllure : true
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Report pending status
    githubStatusReporter(
        status: 'pending',
        context: statusContext,
        description: 'Unit tests running'
    )

    try {
        // Clean previous results
        sh 'rm -rf allure-results'

        // Run unit tests
        sh testCommand

        // Check coverage threshold
        script {
            if (fileExists('coverage/coverage-summary.json')) {
                def coverageReport = readFile('coverage/coverage-summary.json')
                def coverage = readJSON(text: coverageReport)
                def lineCoverage = coverage.total.lines.pct

                echo "Line coverage: ${lineCoverage}%"

                if (lineCoverage < coverageThreshold) {
                    unstable("Coverage ${lineCoverage}% below ${coverageThreshold}% threshold")
                    githubStatusReporter(
                        status: 'failure',
                        context: statusContext,
                        description: "Coverage ${lineCoverage}% below threshold"
                    )
                    return
                }
            }
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'Unit tests passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'Unit tests failed'
        )
        throw e

    } finally {
        // Always publish reports
        publishReports(
            junit: true,
            coverage: true,
            coverageDir: coverageDir,
            allure: enableAllure
        )
    }
}

return this
