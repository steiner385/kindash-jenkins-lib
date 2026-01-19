#!/usr/bin/env groovy
/**
 * Allure Test Failure Analyzer
 * Creates targeted GitHub issues from Allure test results
 *
 * Works with ANY test framework (Jest, Playwright, Vitest, etc.) since
 * Allure normalizes all results into a consistent format.
 *
 * Features:
 * - Groups failures by error signature (same error = same issue)
 * - Checks for existing open issues to avoid duplicates
 * - Creates one issue per unique error type with all affected tests listed
 * - Extracts error messages from Allure statusDetails
 *
 * Usage:
 *   analyzeAllureFailures(allureResultsDir: 'allure-results')
 *   analyzeAllureFailures(allureResultsDir: 'allure-results', maxIssues: 10)
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def call(Map config = [:]) {
    def allureResultsDir = config.allureResultsDir ?: 'allure-results'
    def maxIssues = config.maxIssues ?: 10
    def testType = config.testType ?: 'Test'

    def owner = env.GITHUB_OWNER ?: 'steiner385'
    def repo = env.GITHUB_REPO ?: 'MachShop'
    def buildUrl = env.BUILD_URL ?: ''
    def buildNumber = env.BUILD_NUMBER ?: ''
    def branch = env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'main'

    echo "Analyzing Allure test failures from: ${allureResultsDir}"

    if (!fileExists(allureResultsDir)) {
        echo "Allure results directory not found: ${allureResultsDir}"
        return []
    }

    def failures = parseAllureResults(allureResultsDir)

    if (failures.isEmpty()) {
        echo "No test failures found in Allure results"
        return []
    }

    echo "Found ${failures.size()} failed/broken test(s)"

    def groupedFailures = groupFailuresByError(failures)
    echo "Grouped into ${groupedFailures.size()} unique error type(s)"

    def existingIssues = []
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        existingIssues = fetchOpenIssues(owner, repo)
    }
    echo "Found ${existingIssues.size()} existing open issue(s)"

    def createdIssues = []
    def skippedDuplicates = 0
    def issueCount = 0

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        for (entry in groupedFailures) {
            if (issueCount >= maxIssues) {
                echo "Reached max issues limit (${maxIssues}), stopping"
                break
            }

            def errorSignature = entry[0]
            def errorGroup = entry[1]

            def title = generateIssueTitle(testType, errorSignature, errorGroup)

            def existingIssue = findMatchingIssue(existingIssues, errorSignature, title)
            if (existingIssue) {
                echo "Issue already exists for '${errorSignature.take(50)}...': #${existingIssue.number}"
                addCommentToIssue(owner, repo, existingIssue.number, errorGroup, buildUrl, buildNumber, branch)
                skippedDuplicates++
                continue
            }

            def issueNumber = createIssue(owner, repo, title, testType, errorGroup, buildUrl, buildNumber, branch)
            if (issueNumber) {
                createdIssues.add(issueNumber)
                issueCount++
            }
        }
    }

    echo "Created ${createdIssues.size()} issue(s), updated ${skippedDuplicates} existing issue(s)"
    if (createdIssues) {
        echo "New issues: ${createdIssues.collect { '#' + it }.join(', ')}"
    }
    return createdIssues
}

def parseAllureResults(String allureDir) {
    def failures = []

    try {
        def resultFiles = sh(
            script: "find ${allureDir} -name '*-result.json' -type f 2>/dev/null || true",
            returnStdout: true
        ).trim().split('\n')

        for (resultFile in resultFiles) {
            if (!resultFile || resultFile.isEmpty()) continue

            try {
                def jsonContent = readFile(resultFile)
                def result = new JsonSlurper().parseText(jsonContent)

                if (result.status == 'failed' || result.status == 'broken') {
                    def errorMessage = extractErrorMessage(result)
                    def errorSignature = extractErrorSignature(errorMessage)

                    failures.add([
                        testName: result.name ?: 'Unknown test',
                        fullName: result.fullName ?: result.name,
                        status: result.status,
                        errorMessage: errorMessage,
                        errorSignature: errorSignature,
                        suite: extractSuiteName(result),
                        package: extractPackageName(result),
                        framework: extractFramework(result),
                        duration: calculateDuration(result)
                    ])
                }
            } catch (Exception e) {
                echo "WARNING: Could not parse ${resultFile}: ${e.message}"
            }
        }
    } catch (Exception e) {
        echo "WARNING: Could not read Allure results: ${e.message}"
    }

    return failures
}

def extractErrorMessage(Map result) {
    def statusDetails = result.statusDetails ?: [:]
    if (statusDetails.message) return statusDetails.message
    if (statusDetails.trace) return statusDetails.trace
    return "Test ${result.status}: ${result.name ?: 'Unknown'}"
}

def extractSuiteName(Map result) {
    def labels = result.labels ?: []
    def suite = labels.find { it.name == 'suite' }
    def parentSuite = labels.find { it.name == 'parentSuite' }
    if (suite && parentSuite) return "${parentSuite.value} > ${suite.value}"
    if (suite) return suite.value
    if (parentSuite) return parentSuite.value
    return 'Unknown Suite'
}

def extractPackageName(Map result) {
    def labels = result.labels ?: []
    def packageLabel = labels.find { it.name == 'package' }
    return packageLabel?.value ?: 'Unknown'
}

def extractFramework(Map result) {
    def labels = result.labels ?: []
    def framework = labels.find { it.name == 'framework' }
    return framework?.value ?: 'unknown'
}

def calculateDuration(Map result) {
    if (result.start && result.stop) return result.stop - result.start
    return 0
}

def extractErrorSignature(String errorMessage) {
    if (!errorMessage) return 'unknown_error'

    def signature = errorMessage

    def patterns = [
        [~/Target page.*closed/, 'browser:page_closed'],
        [~/Target.*closed/, 'browser:target_closed'],
        [~/Timeout.*exceeded/, 'timeout_error'],
        [~/waiting for.*timeout/, 'timeout:waiting_for'],
        [~/locator.*timeout/, 'timeout:locator'],
        [~/browserType\.launch.*closed/, 'browser:launch_failed'],
        [~/error while loading shared libraries/, 'browser:missing_library'],
        [~/Cannot find module '([^']+)'/, 'missing_module:$1'],
        [~/Module not found.*'([^']+)'/, 'missing_module:$1'],
        [~/TypeError: (.+?) is not a function/, 'type_error:not_a_function'],
        [~/TypeError: Cannot read propert(y|ies) (.+?) of (undefined|null)/, 'type_error:property_of_$3'],
        [~/TypeError: (.+)/, 'type_error:$1'],
        [~/ReferenceError: (.+?) is not defined/, 'reference_error:$1_not_defined'],
        [~/Expected (.+?) to (be|equal|match|contain)/, 'assertion:expected_$2'],
        [~/expect\(received\)\.(toEqual|toBe|toMatch|toContain)/, 'assertion:$1_failed'],
        [~/ECONNREFUSED/, 'network:connection_refused'],
        [~/ETIMEDOUT/, 'network:timeout'],
        [~/fetch failed/, 'network:fetch_failed'],
        [~/SQLITE_ERROR/, 'db:sqlite_error'],
        [~/Can't reach database server/, 'db:connection_failed'],
        [~/relation.*does not exist/, 'db:missing_relation'],
        [~/Process.*webServer.*not able to start/, 'server:startup_failed'],
        [~/Error: P1001/, 'db:connection_failed'],
    ]

    for (pattern in patterns) {
        def matcher = signature =~ pattern[0]
        if (matcher.find()) {
            try {
                return pattern[1].replaceAll(/\$(\d+)/) { match, group ->
                    matcher.group(group.toInteger()) ?: ''
                }.take(100)
            } catch (Exception e) {
                return pattern[1].replaceAll(/\$\d+/, '').take(100)
            }
        }
    }

    def firstLine = signature.split('\n')[0]
        .replaceAll(/at .+:\d+:\d+/, '')
        .replaceAll(/\/.+\//, '')
        .replaceAll(/\d+/, 'N')
        .replaceAll(/\s+/, ' ')
        .trim()
        .take(100)

    return firstLine ?: 'unknown_error'
}

def groupFailuresByError(List failures) {
    def groups = [:]

    for (failure in failures) {
        def signature = failure.errorSignature ?: 'unknown'
        if (!groups.containsKey(signature)) {
            groups[signature] = [
                errorSignature: signature,
                errorMessage: failure.errorMessage,
                status: failure.status,
                framework: failure.framework,
                failures: []
            ]
        }
        groups[signature].failures.add(failure)
    }

    def entries = groups.collect { sig, data -> [sig, data] }
    entries.sort { a, b -> b[1].failures.size() <=> a[1].failures.size() }
    return entries
}

def generateIssueTitle(String testType, String errorSignature, Map errorGroup) {
    def failureCount = errorGroup.failures.size()
    def plural = failureCount > 1 ? 's' : ''
    def framework = errorGroup.framework ?: 'Test'

    if (errorSignature.startsWith('missing_module:')) {
        def moduleName = errorSignature.replace('missing_module:', '')
        return "${testType} Failure: Missing module '${moduleName}' (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('browser:')) {
        def detail = errorSignature.replace('browser:', '').replaceAll('_', ' ')
        return "${testType} Failure: Browser ${detail} (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('timeout')) {
        return "${testType} Failure: Timeout errors (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('type_error:')) {
        def detail = errorSignature.replace('type_error:', '')
        return "${testType} Failure: TypeError - ${detail} (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('network:')) {
        return "${testType} Failure: Network errors (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('db:')) {
        return "${testType} Failure: Database errors (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('server:')) {
        return "${testType} Failure: Server startup errors (${failureCount} test${plural})"
    }

    def shortSig = errorSignature.take(50)
    return "${testType} Failure: ${shortSig} (${failureCount} test${plural})"
}

def fetchOpenIssues(String owner, String repo) {
    try {
        def response = sh(
            script: """
                curl -s \\
                    -H "Authorization: token \${GITHUB_TOKEN}" \\
                    -H "Accept: application/vnd.github.v3+json" \\
                    "https://api.github.com/repos/${owner}/${repo}/issues?state=open&labels=test-failure&per_page=100"
            """,
            returnStdout: true
        ).trim()

        return new JsonSlurper().parseText(response)
    } catch (Exception e) {
        echo "WARNING: Could not fetch existing issues: ${e.message}"
        return []
    }
}

def findMatchingIssue(List existingIssues, String errorSignature, String title) {
    for (issue in existingIssues) {
        if (issue.body?.contains("Error Signature: `${errorSignature}`")) {
            return issue
        }

        def existingTitleCore = issue.title?.replaceAll(/\(\d+ test(s)?\)/, '').trim()
        def newTitleCore = title.replaceAll(/\(\d+ test(s)?\)/, '').trim()
        if (existingTitleCore == newTitleCore) {
            return issue
        }
    }
    return null
}

def createIssue(String owner, String repo, String title, String testType, Map errorGroup, String buildUrl, String buildNumber, String branch) {
    def failureCount = errorGroup.failures.size()
    def errorSignature = errorGroup.errorSignature
    def errorMessage = errorGroup.errorMessage?.take(2000) ?: 'No error message available'

    def affectedFiles = errorGroup.failures.collect { it.package }.unique().sort()

    def body = """## ${testType} Failure Report

**Branch:** `${branch}`
**Build:** [#${buildNumber}](${buildUrl})
**Error Signature:** `${errorSignature}`
**Affected Tests:** ${failureCount}
**Framework:** ${errorGroup.framework}
**Status:** ${errorGroup.status}

### Error Details
```
${errorMessage}
```

### Affected Tests (${failureCount})
${errorGroup.failures.collect { "- ${it.fullName}" }.join('\n')}

### Affected Files (${affectedFiles.size()})
${affectedFiles.collect { "- `${it}`" }.join('\n')}

### Build Details
- [Console Output](${buildUrl}console)
- [Allure Report](${buildUrl}allure)

---
*Auto-generated from Allure test results*
"""

    def payload = JsonOutput.toJson([
        title: title,
        body: body,
        labels: ['test-failure', 'automated', testType.toLowerCase(), errorGroup.framework]
    ])

    try {
        def response = sh(
            script: """
                curl -s -w '\\n%{http_code}' \\
                    -X POST \\
                    -H "Authorization: token \${GITHUB_TOKEN}" \\
                    -H "Accept: application/vnd.github.v3+json" \\
                    -H "Content-Type: application/json" \\
                    -d '${payload.replace("'", "'\\''")}' \\
                    "https://api.github.com/repos/${owner}/${repo}/issues"
            """,
            returnStdout: true
        ).trim()

        def lines = response.split('\n')
        def statusCode = lines[-1]
        def jsonResponse = lines[0..-2].join('\n')

        if (statusCode == '201') {
            def issueData = new JsonSlurper().parseText(jsonResponse)
            echo "Created issue #${issueData.number}: ${title}"
            return issueData.number
        } else {
            echo "Failed to create issue (HTTP ${statusCode}): ${jsonResponse}"
            return null
        }
    } catch (Exception e) {
        echo "ERROR creating issue: ${e.message}"
        return null
    }
}

def addCommentToIssue(String owner, String repo, int issueNumber, Map errorGroup, String buildUrl, String buildNumber, String branch) {
    def failureCount = errorGroup.failures.size()

    def comment = """### New failure occurrence

**Build:** [#${buildNumber}](${buildUrl})
**Branch:** `${branch}`
**Affected Tests:** ${failureCount}

${errorGroup.failures.collect { "- ${it.fullName}" }.join('\n')}

[Console Output](${buildUrl}console)
"""

    def payload = JsonOutput.toJson([body: comment])

    try {
        sh(
            script: """
                curl -s -X POST \\
                    -H "Authorization: token \${GITHUB_TOKEN}" \\
                    -H "Accept: application/vnd.github.v3+json" \\
                    -H "Content-Type: application/json" \\
                    -d '${payload.replace("'", "'\\''")}' \\
                    "https://api.github.com/repos/${owner}/${repo}/issues/${issueNumber}/comments" \\
                    > /dev/null
            """,
            returnStdout: false
        )
        echo "Updated issue #${issueNumber} with new occurrence"
    } catch (Exception e) {
        echo "WARNING: Could not add comment to issue #${issueNumber}: ${e.message}"
    }
}
