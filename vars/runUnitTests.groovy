#!/usr/bin/env groovy
/**
 * Run Unit Tests Stage
 * Executes unit tests with coverage reporting and threshold checks
 *
 * Features:
 * - Auto-detects test framework (vitest vs jest)
 * - GitHub status reporting (pending/success/failure)
 * - Coverage threshold enforcement
 * - JUnit and coverage report publishing
 * - Allure report support
 *
 * Usage:
 *   runUnitTests()  // Use defaults, auto-detect test framework
 *   runUnitTests(testCommand: 'pnpm run test:unit -- --coverage')
 *   runUnitTests(coverageThreshold: 80)
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/unit-tests'
    def pm = pipelineHelpers.getPackageManager()
    def coverageThreshold = config.coverageThreshold ?: 70
    def enableAllure = config.enableAllure != null ? config.enableAllure : true
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Detect test framework (vitest vs jest)
    def isVitest = fileExists('vitest.config.ts') ||
                   fileExists('vitest.config.js') ||
                   fileExists('vitest.config.mjs') ||
                   fileExists('vitest.config.cjs')

    def testCommand
    def coverageDir

    if (isVitest) {
        // Vitest configuration
        echo "✓ Detected Vitest - using vitest with coverage"
        testCommand = config.testCommand ?: "${pm.run} test:coverage"
        coverageDir = config.coverageDir ?: 'coverage'
    } else {
        // Jest configuration (default/legacy)
        echo "✓ Detected Jest - using jest with coverage"
        testCommand = config.testCommand ?: "${pm.run} test:unit -- --coverage"
        coverageDir = config.coverageDir ?: 'coverage/lcov-report'
    }

    // Report pending status
    githubStatusReporter(
        status: 'pending',
        context: statusContext,
        description: 'Unit tests running'
    )

    try {
        // Clean previous results
        sh 'rm -rf allure-results coverage'

        // Run unit tests with coverage
        echo "Running: ${testCommand}"
        sh testCommand

        // Check coverage threshold (only if coverage was generated)
        script {
            def coverageSummaryFile = 'coverage/coverage-summary.json'

            if (fileExists(coverageSummaryFile)) {
                echo "Coverage report generated at ${coverageSummaryFile}"

                def coverageReport = readFile(coverageSummaryFile)
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
            } else {
                echo "⚠ Warning: Coverage summary file not found at ${coverageSummaryFile}"
                echo "This may indicate test failures prevented coverage generation"
                echo "Check test output above for failures"
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
        echo "✗ Unit tests failed"
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'Unit tests failed'
        )
        throw e

    } finally {
        // Always publish reports (even if tests fail)
        publishReports(
            junit: true,
            coverage: true,
            coverageDir: coverageDir,
            allure: enableAllure
        )
    }
}

return this
