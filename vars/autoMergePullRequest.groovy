#!/usr/bin/env groovy
/**
 * Auto-Merge Pull Request
 * Enables GitHub's auto-merge feature on a pull request after successful CI
 *
 * Usage:
 *   autoMergePullRequest()  // Uses env.CHANGE_ID for PR number
 *   autoMergePullRequest(prNumber: 123, mergeMethod: 'SQUASH')
 *
 * Parameters:
 *   prNumber    - PR number (defaults to env.CHANGE_ID)
 *   mergeMethod - 'SQUASH', 'MERGE', or 'REBASE' (defaults to 'SQUASH')
 *
 * Requirements:
 *   - Repository must have auto-merge enabled in settings
 *   - PR must have required status checks passing
 *   - GitHub token must have write access to PRs
 */

def call(Map config = [:]) {
    def prNumber = config.prNumber ?: env.CHANGE_ID
    def mergeMethod = config.mergeMethod ?: 'SQUASH'

    // Validate merge method
    def validMethods = ['SQUASH', 'MERGE', 'REBASE']
    if (!validMethods.contains(mergeMethod)) {
        error "Invalid mergeMethod '${mergeMethod}'. Must be one of: ${validMethods.join(', ')}"
    }

    if (!prNumber) {
        echo "INFO: Not a PR build (CHANGE_ID not set), skipping auto-merge"
        return [enabled: false, reason: 'Not a PR build']
    }

    def owner = env.GITHUB_OWNER ?: 'steiner385'
    def repo = env.GITHUB_REPO ?: 'KinDash'

    echo "Enabling auto-merge for PR #${prNumber} with method: ${mergeMethod}"

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        // Step 1: Get the PR's node_id (required for GraphQL)
        def prResponse = sh(
            script: """
                curl -s -w "\\n%{http_code}" \
                    -H "Authorization: token \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    "https://api.github.com/repos/${owner}/${repo}/pulls/${prNumber}"
            """,
            returnStdout: true
        ).trim()

        def prLines = prResponse.split('\n')
        def prHttpCode = prLines[-1]
        def prBody = prLines[0..-2].join('\n')

        if (prHttpCode != '200') {
            echo "WARNING: Failed to fetch PR #${prNumber}: HTTP ${prHttpCode}"
            return [enabled: false, reason: "Failed to fetch PR: HTTP ${prHttpCode}"]
        }

        def prData = readJSON(text: prBody)
        def nodeId = prData.node_id

        if (!nodeId) {
            echo "WARNING: Could not get node_id for PR #${prNumber}"
            return [enabled: false, reason: 'Could not get PR node_id']
        }

        // Step 2: Enable auto-merge via GraphQL API
        def graphqlQuery = """
            mutation EnableAutoMerge {
                enablePullRequestAutoMerge(input: {
                    pullRequestId: "${nodeId}",
                    mergeMethod: ${mergeMethod}
                }) {
                    pullRequest {
                        autoMergeRequest {
                            enabledAt
                        }
                    }
                }
            }
        """.replaceAll('\\s+', ' ').trim()

        def payload = groovy.json.JsonOutput.toJson([query: graphqlQuery])

        def gqlResponse = sh(
            script: """
                curl -s -w "\\n%{http_code}" -X POST \
                    -H "Authorization: bearer \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    -H "Content-Type: application/json" \
                    "https://api.github.com/graphql" \
                    -d '${payload}'
            """,
            returnStdout: true
        ).trim()

        def gqlLines = gqlResponse.split('\n')
        def gqlHttpCode = gqlLines[-1]
        def gqlBody = gqlLines[0..-2].join('\n')

        if (gqlHttpCode != '200') {
            echo "WARNING: GraphQL API returned HTTP ${gqlHttpCode}: ${gqlBody}"
            return [enabled: false, reason: "GraphQL API error: HTTP ${gqlHttpCode}"]
        }

        def gqlData = readJSON(text: gqlBody)

        // Check for GraphQL errors
        if (gqlData.errors) {
            def errorMsg = gqlData.errors[0]?.message ?: 'Unknown GraphQL error'
            echo "WARNING: GraphQL error: ${errorMsg}"

            // Handle common cases gracefully
            if (errorMsg.contains('Pull request is already queued to merge')) {
                echo "INFO: Auto-merge was already enabled for PR #${prNumber}"
                return [enabled: true, alreadyEnabled: true]
            }
            if (errorMsg.contains('Pull request is in clean status')) {
                echo "INFO: PR #${prNumber} is ready to merge immediately"
                return [enabled: false, reason: 'PR ready to merge immediately']
            }

            return [enabled: false, reason: errorMsg]
        }

        def enabledAt = gqlData.data?.enablePullRequestAutoMerge?.pullRequest?.autoMergeRequest?.enabledAt
        if (enabledAt) {
            echo "Auto-merge enabled successfully for PR #${prNumber} at ${enabledAt}"
            return [enabled: true, enabledAt: enabledAt]
        } else {
            echo "INFO: Auto-merge response received but no enabledAt timestamp"
            return [enabled: true, enabledAt: null]
        }
    }
}
