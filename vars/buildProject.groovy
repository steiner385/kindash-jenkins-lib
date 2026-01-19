#!/usr/bin/env groovy
/**
 * Build Project Stage
 * Builds the project and archives artifacts
 *
 * Features:
 * - Configurable build command
 * - Artifact archival
 * - Build caching support
 *
 * Usage:
 *   buildProject()  // Use defaults
 *   buildProject(buildCommand: 'npm run build:prod')
 *   buildProject(artifacts: 'dist/**,build/**')
 */

def call(Map config = [:]) {
    def buildCommand = config.buildCommand ?: 'npm run build'
    def artifacts = config.artifacts ?: 'dist/**'
    def skipIfExists = config.skipIfExists ?: false
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    if (skipIfExists) {
        // Use cached build if available
        script {
            def hasDistDir = fileExists('dist')
            def hasNextDir = fileExists('.next')
            def hasBuildDir = fileExists('build')

            if (hasDistDir || hasNextDir || hasBuildDir) {
                echo "Using cached build artifacts"
                return
            }
        }
    }

    // Run build
    sh buildCommand

    // Archive artifacts
    if (artifacts) {
        archiveArtifacts artifacts: artifacts, allowEmptyArchive: true
    }
}

/**
 * Build for production
 */
def production(Map config = [:]) {
    call(config + [buildCommand: config.buildCommand ?: 'npm run build:prod'])
}

/**
 * Build for staging
 */
def staging(Map config = [:]) {
    call(config + [buildCommand: config.buildCommand ?: 'npm run build:staging'])
}

return this
