#!/usr/bin/env groovy
/**
 * Pipeline Helper Functions
 * Common utility functions used across all pipeline stages
 *
 * Usage:
 *   def project = pipelineHelpers.getProjectName()
 *   def workspace = pipelineHelpers.getWorkspacePath()
 *   def envVars = pipelineHelpers.getDefaultEnvironment()
 */

/**
 * Extract project name from JOB_NAME
 * e.g., "MachShop/main" -> "machshop"
 */
def getProjectName() {
    return env.JOB_NAME?.split('/')[0]?.toLowerCase() ?: 'unknown'
}

/**
 * Build project-specific workspace path
 * Uses persistent workspaces for warm builds
 */
def getWorkspacePath() {
    return "/home/jenkins/agent/workspace-${getProjectName()}"
}

/**
 * Get default environment variables map
 * Returns common env vars used across pipelines
 */
def getDefaultEnvironment() {
    return [
        GITHUB_OWNER: 'steiner385',
        GITHUB_REPO: env.JOB_NAME?.split('/')[0] ?: '',
        CI: 'true',
        NODE_ENV: 'test',
        NPM_CONFIG_CACHE: '/home/jenkins/agent/.npm-cache',
        PLAYWRIGHT_BROWSERS_PATH: '/home/jenkins/agent/.playwright-cache'
    ]
}

/**
 * Get default MachShop service ports
 * Returns list of ports used by microservices and frontend
 */
def getServicePorts() {
    return [
        // Microservices (3001-3020)
        3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008,
        3009, 3010, 3011, 3012, 3013, 3014, 3015, 3020,
        // Frontend dev servers (5178-5180)
        5178, 5179, 5180
    ]
}

/**
 * Get agent label for a specific test type
 */
def getAgentLabel(String testType) {
    switch (testType) {
        case 'unit':
            return 'unit'
        case 'integration':
            return 'integration'
        case 'e2e':
            return 'e2e'
        default:
            return 'linux'
    }
}

return this
