#!/usr/bin/env groovy
/**
 * Test Failure Analyzer
 * Groups test failures by error type and creates targeted GitHub issues
 *
 * Features:
 * - Groups failures by error signature (same error = same issue)
 * - Checks for existing open issues to avoid duplicates
 * - Creates one issue per unique error type with all affected tests listed
 *
 * Usage:
 *   analyzeTestFailures(testResultsFile: 'test-results.json')
 *   analyzeTestFailures(testResultsFile: 'test-results.json', maxIssues: 10)
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.cloudbees.groovy.cps.NonCPS

def call(Map config = [:]) {
    def testResultsFile = config.testResultsFile
    def testOutput = config.testOutput
    def maxIssues = config.maxIssues ?: 10

    // Get repository info from environment
    def owner = env.GITHUB_OWNER ?: 'steiner385'
    def repo = env.GITHUB_REPO ?: 'MachShop'
    def buildUrl = env.BUILD_URL ?: ''
    def buildNumber = env.BUILD_NUMBER ?: ''
    def branch = env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'main'

    echo "Analyzing test failures..."

    // Get test failures
    def failures = []
    if (testResultsFile && fileExists(testResultsFile)) {
        def jsonContent = readFile(testResultsFile)
        failures = parseJestJson(jsonContent)
    } else if (testOutput) {
        failures = parseJestOutput(testOutput)
    } else {
        echo "No test results provided, skipping analysis"
        return []
    }

    if (failures.isEmpty()) {
        echo "No test failures found"
        return []
    }

    echo "Found ${failures.size()} test failure(s)"

    // Group failures by error signature
    def groupedFailures = groupFailuresByError(failures)
    echo "Grouped into ${groupedFailures.size()} unique error type(s)"

    // Fetch existing open issues once
    def existingIssues = []
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        existingIssues = fetchOpenIssues(owner, repo)
    }
    echo "Found ${existingIssues.size()} existing open issue(s)"

    // Create issues for each error group
    def createdIssues = []
    def skippedDuplicates = 0
    def issueCount = 0

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        for (entry in groupedFailures) {
            if (issueCount >= maxIssues) {
                echo "Reached max issues limit (${maxIssues}), stopping"
                break
            }

            // entry is [signature, groupData]
            def errorSignature = entry[0]
            def errorGroup = entry[1]

            // Generate issue title based on error type
            def title = generateIssueTitle(errorSignature, errorGroup)

            // Check for existing similar issue
            def existingIssue = findMatchingIssue(existingIssues, errorSignature, title)
            if (existingIssue) {
                echo "Issue already exists for '${errorSignature.take(50)}...': #${existingIssue.number} - skipping"
                skippedDuplicates++
                continue
            }

            // Create the issue
            def issueNumber = createIssue(owner, repo, title, errorGroup, buildUrl, buildNumber, branch)
            if (issueNumber) {
                createdIssues.add(issueNumber)
                issueCount++
            }
        }
    }

    echo "Created ${createdIssues.size()} issue(s), skipped ${skippedDuplicates} duplicate(s)"
    if (createdIssues) {
        echo "New issues: ${createdIssues.collect { '#' + it }.join(', ')}"
    }
    return createdIssues
}

/**
 * Parse Jest JSON output format
 */
def parseJestJson(String jsonContent) {
    def failures = []
    try {
        def results = parseJsonToSerializable(jsonContent)

        if (results.testResults) {
            for (testFile in results.testResults) {
                def filePath = testFile.name ?: 'unknown'
                def shortFile = filePath.split('/')[-1]

                if (testFile.status == 'failed') {
                    if (testFile.message) {
                        failures.add([
                            testFile: filePath,
                            shortFile: shortFile,
                            testName: "Suite: ${shortFile}",
                            errorMessage: testFile.message,
                            errorType: 'suite_failure',
                            errorSignature: extractErrorSignature(testFile.message)
                        ])
                    }

                    if (testFile.assertionResults) {
                        for (assertion in testFile.assertionResults) {
                            if (assertion.status == 'failed') {
                                def errorMsg = assertion.failureMessages?.join('\n') ?: 'Unknown error'
                                failures.add([
                                    testFile: filePath,
                                    shortFile: shortFile,
                                    testName: assertion.fullName ?: assertion.title ?: 'Unknown test',
                                    ancestorTitles: assertion.ancestorTitles ?: [],
                                    errorMessage: errorMsg,
                                    errorType: 'assertion_failure',
                                    errorSignature: extractErrorSignature(errorMsg)
                                ])
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "WARNING: Could not parse Jest JSON: ${e.message}"
    }
    return failures
}

/**
 * Parse raw Jest console output
 * @NonCPS annotation required for .each closure
 */
@NonCPS
def parseJestOutput(String output) {
    def failures = []
    def currentFile = null
    def currentTest = null
    def errorBuffer = []

    output.split('\n').each { line ->
        def fileMatch = line =~ /^\s*FAIL\s+(.+\.tsx?|.+\.jsx?)/
        if (fileMatch) {
            currentFile = fileMatch[0][1]
        }
        def testMatch = line =~ /^\s*(X|x)\s+(.+?)(?:\s+\(\d+\s*m?s\))?$/
        if (testMatch) {
            if (currentTest) {
                currentTest.errorMessage = errorBuffer.join('\n')
                currentTest.errorSignature = extractErrorSignature(currentTest.errorMessage)
                failures.add(currentTest)
            }
            currentTest = [
                testName: testMatch[0][2].trim(),
                testFile: currentFile ?: 'unknown',
                shortFile: (currentFile ?: 'unknown').split('/')[-1],
                errorMessage: '',
                errorType: 'assertion_failure'
            ]
            errorBuffer = []
        }
        else if (currentTest && line.trim() && !line.startsWith('PASS') && !line.startsWith('FAIL')) {
            errorBuffer.add(line)
        }
    }

    if (currentTest) {
        currentTest.errorMessage = errorBuffer.join('\n')
        currentTest.errorSignature = extractErrorSignature(currentTest.errorMessage)
        failures.add(currentTest)
    }

    return failures
}

/**
 * Extract a normalized error signature for grouping
 * @NonCPS annotation required for regex closure operations
 */
@NonCPS
def extractErrorSignature(String errorMessage) {
    if (!errorMessage) return 'unknown_error'

    def signature = errorMessage

    def patterns = [
        [~/Cannot find module '([^']+)'/, 'missing_module:$1'],
        [~/Module not found.*'([^']+)'/, 'missing_module:$1'],
        [~/TypeError: (.+?) is not a function/, 'type_error:not_a_function'],
        [~/TypeError: Cannot read propert(y|ies) (.+?) of (undefined|null)/, 'type_error:property_of_$3'],
        [~/TypeError: (.+)/, 'type_error:$1'],
        [~/ReferenceError: (.+?) is not defined/, 'reference_error:$1_not_defined'],
        [~/Expected (.+?) to (be|equal|match|contain)/, 'assertion:expected_$2'],
        [~/expect\(received\)\.(toEqual|toBe|toMatch|toContain)/, 'assertion:$1_failed'],
        [~/Timeout.*exceeded/, 'timeout_error'],
        [~/(?i)async.*timeout/, 'async_timeout'],
        [~/(?i)mock.*not.*called/, 'mock_not_called'],
        [~/(?i)mock.*called.*times/, 'mock_call_count'],
        [~/ECONNREFUSED/, 'network:connection_refused'],
        [~/ETIMEDOUT/, 'network:timeout'],
        [~/SQLITE_ERROR/, 'db:sqlite_error'],
        [~/relation.*does not exist/, 'db:missing_relation'],
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

/**
 * Group failures by their error signature
 * @NonCPS annotation required for sort closure to work in Jenkins Pipeline
 */
@NonCPS
def groupFailuresByError(List failures) {
    def groups = [:]

    for (failure in failures) {
        def signature = failure.errorSignature ?: 'unknown'
        if (!groups.containsKey(signature)) {
            groups[signature] = [
                errorSignature: signature,
                errorMessage: failure.errorMessage,
                errorType: failure.errorType,
                failures: []
            ]
        }
        groups[signature].failures.add(failure)
    }

    def entries = groups.collect { sig, data -> [sig, data] }
    entries.sort { a, b -> b[1].failures.size() <=> a[1].failures.size() }
    return entries
}

/**
 * Generate a descriptive issue title based on the error type
 * @NonCPS annotation for string operations
 */
@NonCPS
def generateIssueTitle(String errorSignature, Map errorGroup) {
    def failureCount = errorGroup.failures.size()
    def plural = failureCount > 1 ? 's' : ''

    if (errorSignature.startsWith('missing_module:')) {
        def moduleName = errorSignature.replace('missing_module:', '')
        return "Test Failure: Missing module '${moduleName}' (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('type_error:')) {
        def detail = errorSignature.replace('type_error:', '')
        return "Test Failure: TypeError - ${detail} (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('reference_error:')) {
        def detail = errorSignature.replace('reference_error:', '')
        return "Test Failure: ReferenceError - ${detail} (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('assertion:')) {
        def detail = errorSignature.replace('assertion:', '')
        return "Test Failure: Assertion failed - ${detail} (${failureCount} test${plural})"
    }
    if (errorSignature.contains('timeout')) {
        return "Test Failure: Timeout errors (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('network:')) {
        return "Test Failure: Network errors (${failureCount} test${plural})"
    }
    if (errorSignature.startsWith('db:')) {
        return "Test Failure: Database errors (${failureCount} test${plural})"
    }

    def shortSig = errorSignature.take(50)
    return "Test Failure: ${shortSig} (${failureCount} test${plural})"
}

/**
 * Fetch all open issues from the repository
 */
def fetchOpenIssues(String owner, String repo) {
    try {
        def response = sh(
            script: """
                curl -s \
                    -H "Authorization: token \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    "https://api.github.com/repos/${owner}/${repo}/issues?state=open&labels=testing&per_page=100"
            """,
            returnStdout: true
        ).trim()

        // Parse and convert to serializable format to avoid LazyMap issues
        return parseJsonToSerializable(response)
    } catch (Exception e) {
        echo "WARNING: Could not fetch existing issues: ${e.message}"
        return []
    }
}

/**
 * Parse JSON and convert to serializable Java objects
 * JsonSlurper returns LazyMap which is not serializable in Jenkins Pipeline
 * @NonCPS annotation required for JSON parsing
 */
@NonCPS
def parseJsonToSerializable(String jsonText) {
    def parsed = new JsonSlurper().parseText(jsonText)
    return convertToSerializable(parsed)
}

/**
 * Recursively convert LazyMap/LazyList to HashMap/ArrayList
 * @NonCPS annotation required for recursive operations
 */
@NonCPS
def convertToSerializable(obj) {
    if (obj instanceof Map) {
        def result = new HashMap()
        obj.each { k, v -> result.put(k, convertToSerializable(v)) }
        return result
    } else if (obj instanceof List) {
        def result = new ArrayList()
        obj.each { item -> result.add(convertToSerializable(item)) }
        return result
    }
    return obj
}

/**
 * Find an existing issue that matches the error signature or title
 * @NonCPS annotation for iteration and string operations
 */
@NonCPS
def findMatchingIssue(List existingIssues, String errorSignature, String title) {
    for (issue in existingIssues) {
        def body = issue.body ?: ''
        def issueTitle = issue.title ?: ''

        if (body.contains("Error Signature: `${errorSignature}`")) {
            return issue
        }

        def titlePattern = title.replaceAll(/\(\d+ tests?\)/, '').trim()
        def existingPattern = issueTitle.replaceAll(/\(\d+ tests?\)/, '').trim()

        if (titlePattern == existingPattern) {
            return issue
        }

        if (errorSignature.startsWith('missing_module:')) {
            def moduleName = errorSignature.replace('missing_module:', '')
            if (issueTitle.contains(moduleName) && issueTitle.contains('Missing module')) {
                return issue
            }
        }
    }

    return null
}

/**
 * Create a GitHub issue for an error group
 */
def createIssue(String owner, String repo, String title, Map errorGroup, String buildUrl, String buildNumber, String branch) {
    def failures = errorGroup.failures
    def errorSignature = errorGroup.errorSignature
    def errorMessage = errorGroup.errorMessage

    def labels = ['bug', 'testing', 'jenkins']
    if (errorSignature.contains('missing_module')) {
        labels.add('dependencies')
    }
    if (errorSignature.contains('type_error') || errorSignature.contains('reference_error')) {
        labels.add('typescript')
    }
    if (errorSignature.contains('timeout') || errorSignature.contains('async')) {
        labels.add('async')
    }
    if (errorSignature.contains('mock')) {
        labels.add('testing-infrastructure')
    }

    def affectedFiles = failures.collect { it.shortFile }.unique().sort()
    def affectedTests = failures.collect { it.testName }.unique().take(50)

    def body = """## Test Failure Report

**Branch:** ${branch}
**Build:** [#${buildNumber}](${buildUrl})
**Error Signature:** `${errorSignature}`
**Affected Tests:** ${failures.size()}

### Error Details

```
${errorMessage?.take(2000) ?: 'No error message available'}
```

### Affected Files (${affectedFiles.size()})

${affectedFiles.take(20).collect { "- `${it}`" }.join('\n')}
${affectedFiles.size() > 20 ? "\n_...and ${affectedFiles.size() - 20} more files_" : ''}

### Affected Tests (${affectedTests.size()})

${affectedTests.take(30).collect { "- ${it}" }.join('\n')}
${affectedTests.size() > 30 ? "\n_...and ${affectedTests.size() - 30} more tests_" : ''}

### Build Details

- [Console Output](${buildUrl}console)
- [Build Summary](${buildUrl})

---
_Auto-generated by Jenkins CI_
"""

    def payload = JsonOutput.toJson([
        title: title,
        body: body,
        labels: labels.unique()
    ])

    def tempFile = "${env.WORKSPACE}/issue_payload_${System.currentTimeMillis()}.json"
    writeFile(file: tempFile, text: payload)

    def response = sh(
        script: """
            curl -s -w "\\n%{http_code}" -X POST \
                -H "Authorization: token \${GITHUB_TOKEN}" \
                -H "Accept: application/vnd.github.v3+json" \
                -H "Content-Type: application/json" \
                "https://api.github.com/repos/${owner}/${repo}/issues" \
                -d @"${tempFile}"
        """,
        returnStdout: true
    ).trim()

    sh(script: "rm -f '${tempFile}'", returnStatus: true)

    def lines = response.split('\n')
    def httpCode = lines[-1]
    def responseBody = lines[0..-2].join('\n')

    if (httpCode == '201') {
        def issueData = parseJsonToSerializable(responseBody)
        echo "Created issue #${issueData.number}: ${title}"
        return issueData.number
    } else {
        echo "WARNING: Failed to create issue (HTTP ${httpCode}): ${responseBody.take(200)}"
        return null
    }
}
