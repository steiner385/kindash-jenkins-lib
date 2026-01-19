#!/usr/bin/env groovy
/**
 * Playwright Browser Setup
 * Installs Playwright browsers with caching support
 *
 * Features:
 * - Cache check via marker files (more reliable than directory glob)
 * - Only installs if browsers not cached
 * - Supports multiple browsers
 *
 * Usage:
 *   playwrightSetup()  // Install chromium (default)
 *   playwrightSetup(browsers: ['chromium', 'firefox'])
 *   playwrightSetup(browsersPath: '/custom/path')
 */

def call(Map config = [:]) {
    def browsersPath = config.browsersPath ?: env.PLAYWRIGHT_BROWSERS_PATH ?: '/home/jenkins/agent/.playwright-cache'
    def browsers = config.browsers ?: ['chromium']
    def withDeps = config.withDeps != null ? config.withDeps : true

    def browsersString = browsers.join(' ')
    def depsFlag = withDeps ? '--with-deps' : ''

    sh """
        echo "Ensuring Playwright browsers are installed..."

        # Create cache directory if doesn't exist
        mkdir -p "${browsersPath}"

        # Check for marker file (more reliable than directory glob)
        if [ ! -f "${browsersPath}/.browsers-installed" ]; then
            echo "Installing Playwright browsers: ${browsersString}..."
            npx playwright install ${depsFlag} ${browsersString}
            touch "${browsersPath}/.browsers-installed"
            echo "Browsers installed"
        else
            echo "Browsers already installed (using cache)"
        fi
    """
}

/**
 * Force reinstall browsers (ignores cache)
 */
def forceInstall(Map config = [:]) {
    def browsersPath = config.browsersPath ?: env.PLAYWRIGHT_BROWSERS_PATH ?: '/home/jenkins/agent/.playwright-cache'

    sh "rm -f '${browsersPath}/.browsers-installed'"
    call(config)
}

return this
