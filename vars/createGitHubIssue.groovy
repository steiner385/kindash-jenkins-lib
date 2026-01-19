#!/usr/bin/env groovy
/**
 * GitHub Issue Creator
 * Creates GitHub issues for build failures
 *
 * Usage:
 *   createGitHubIssue(title: 'Build Failed', labels: ['bug', 'ci-failure'])
 *   createGitHubIssue(title: 'Nightly Build Failed', labels: ['bug', 'nightly', 'jenkins'])
 *
 * Parameters:
 *   title       - Issue title (defaults to "Jenkins Build Failed: ${JOB_NAME}")
 *   body        - Issue body (auto-generated if not provided)
 *   labels      - List of labels to apply
 *   assignees   - List of GitHub usernames to assign
 *   milestone   - Milestone number to associate
 */

def call(Map config = [:]) {
    def title = config.title ?: "Jenkins Build Failed: ${env.JOB_NAME}"
    def labels = config.labels ?: ['bug', 'ci-failure', 'jenkins']
    def assignees = config.assignees ?: []
    def milestone = config.milestone

    // Get repository info from environment
    def owner = env.GITHUB_OWNER ?: 'steiner385'
    def repo = env.GITHUB_REPO ?: env.JOB_NAME?.split('/')[0]

    // Generate body if not provided
    def body = config.body ?: generateIssueBody()

    echo "Creating GitHub issue: ${title}"

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        // Check for existing open issue with same title to avoid duplicates
        def existingIssue = checkExistingIssue(owner, repo, title)
        if (existingIssue) {
            echo "Issue already exists: #${existingIssue.number} - adding comment instead"
            addCommentToIssue(owner, repo, existingIssue.number, body)
            return existingIssue.number
        }

        def payload = [
            title: title,
            body: body,
            labels: labels
        ]

        if (assignees) {
            payload.assignees = assignees
        }

        if (milestone) {
            payload.milestone = milestone
        }

        def payloadJson = groovy.json.JsonOutput.toJson(payload)

        def response = sh(
            script: """
                curl -s -w "\\n%{http_code}" -X POST \
                    -H "Authorization: token \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    -H "Content-Type: application/json" \
                    "https://api.github.com/repos/${owner}/${repo}/issues" \
                    -d '${payloadJson.replace("'", "'\\''")}'
            """,
            returnStdout: true
        ).trim()

        def lines = response.split('\n')
        def httpCode = lines[-1]
        def responseBody = lines[0..-2].join('\n')

        if (httpCode == '201') {
            def issueData = new groovy.json.JsonSlurper().parseText(responseBody)
            echo "GitHub issue created successfully: #${issueData.number}"
            echo "URL: ${issueData.html_url}"
            return issueData.number
        } else {
            echo "WARNING: Failed to create GitHub issue (HTTP ${httpCode}): ${responseBody}"
            return null
        }
    }
}

/**
 * Generate issue body with build information
 */
def generateIssueBody() {
    def branch = env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'unknown'
    def commit = env.GIT_COMMIT?.take(7) ?: 'unknown'
    def buildUrl = env.BUILD_URL ?: 'N/A'
    def jobName = env.JOB_NAME ?: 'unknown'
    def buildNumber = env.BUILD_NUMBER ?: 'unknown'

    return """## Jenkins Build Failed

| Field | Value |
|-------|-------|
| **Job** | ${jobName} |
| **Build** | #${buildNumber} |
| **Branch** | ${branch} |
| **Commit** | ${commit} |
| **URL** | ${buildUrl} |

### Build Console Output
See the [full console output](${buildUrl}console) for details.

---
_Auto-created by Jenkins CI_
"""
}

/**
 * Check if an issue with the same title already exists
 */
def checkExistingIssue(String owner, String repo, String title) {
    def encodedTitle = java.net.URLEncoder.encode(title, 'UTF-8')

    def response = sh(
        script: """
            curl -s \
                -H "Authorization: token \${GITHUB_TOKEN}" \
                -H "Accept: application/vnd.github.v3+json" \
                "https://api.github.com/repos/${owner}/${repo}/issues?state=open&per_page=100"
        """,
        returnStdout: true
    ).trim()

    try {
        def issues = new groovy.json.JsonSlurper().parseText(response)
        return issues.find { it.title == title }
    } catch (Exception e) {
        echo "WARNING: Could not check existing issues: ${e.message}"
        return null
    }
}

/**
 * Add a comment to an existing issue
 */
def addCommentToIssue(String owner, String repo, int issueNumber, String body) {
    def commentBody = """## Build Failed Again

${body}

---
_This is a recurring failure. The original issue was updated with this comment._
"""

    def payload = groovy.json.JsonOutput.toJson([body: commentBody])

    sh(
        script: """
            curl -s -X POST \
                -H "Authorization: token \${GITHUB_TOKEN}" \
                -H "Accept: application/vnd.github.v3+json" \
                -H "Content-Type: application/json" \
                "https://api.github.com/repos/${owner}/${repo}/issues/${issueNumber}/comments" \
                -d '${payload.replace("'", "'\\''")}'
        """,
        returnStdout: true
    )

    echo "Added comment to issue #${issueNumber}"
}

/**
 * Convenience method for nightly build failures
 */
def nightly(String title = null) {
    call(
        title: title ?: "Nightly Build Failed: ${env.JOB_NAME}",
        labels: ['bug', 'nightly', 'ci-failure', 'jenkins']
    )
}

/**
 * Convenience method for deployment failures
 */
def deployment(String environment, String title = null) {
    call(
        title: title ?: "Deployment Failed: ${environment}",
        labels: ['bug', 'deployment', 'ci-failure', 'jenkins', environment]
    )
}
