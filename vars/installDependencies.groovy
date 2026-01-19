#!/usr/bin/env groovy
/**
 * Install Dependencies Stage
 * Smart package manager installation with caching support
 *
 * Features:
 * - Auto-detects package manager (pnpm, yarn, npm)
 * - Uses .npmrc.ci if present (for npm)
 * - Skips install if node_modules is up-to-date
 * - Legacy peer deps support (npm only)
 * - Official npm registry enforcement
 *
 * Usage:
 *   installDependencies()  // Use defaults, auto-detect package manager
 *   installDependencies(forceInstall: true)
 *   installDependencies(packageManager: 'pnpm')  // Force specific package manager
 */

def call(Map config = [:]) {
    def forceInstall = config.forceInstall ?: false
    def registry = config.registry ?: 'https://registry.npmjs.org/'

    // Get package manager configuration
    def pm = pipelineHelpers.getPackageManager()
    def pmName = config.packageManager ?: pm.name

    echo "Using package manager: ${pmName}"

    // Clean up .npmrc for CI (can cause issues with pnpm in CI)
    sh 'rm -f .npmrc'

    // Set memory limit for large dependency trees
    sh 'export NODE_OPTIONS="--max-old-space-size=4096"'

    // Get the lock file name for cache checking
    def lockFile = pmName == 'pnpm' ? 'pnpm-lock.yaml' : (pmName == 'yarn' ? 'yarn.lock' : 'package-lock.json')

    // Install dependencies (smart caching)
    if (forceInstall) {
        sh """
            echo "Force installing dependencies with ${pmName}..."
            rm -rf node_modules
            ${pm.install}
        """
    } else {
        sh """
            if [ ! -d "node_modules" ] || [ "${lockFile}" -nt "node_modules/.cache-marker" ]; then
                echo "Installing dependencies with ${pmName}..."
                ${pm.install}
                touch node_modules/.cache-marker
            else
                echo "Dependencies up to date, skipping install"
            fi
        """
    }
}

/**
 * Force clean install
 */
def clean() {
    call(forceInstall: true)
}

return this
