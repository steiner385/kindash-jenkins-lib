#!/usr/bin/env groovy
/**
 * Docker Cleanup Utility
 * Stops Docker containers and kills processes on specified ports
 *
 * Features:
 * - Docker Compose v1/v2 fallback support
 * - Port cleanup via lsof/kill
 * - Optional lockfile cleanup
 *
 * Usage:
 *   dockerCleanup()  // Use defaults
 *   dockerCleanup(composeFile: 'docker-compose.test.yml', ports: [3001, 3002])
 *   dockerCleanup(cleanLockfiles: true)
 */

def call(Map config = [:]) {
    def composeFile = config.composeFile ?: 'deployment/docker/docker-compose.test.yml'
    def ports = config.ports ?: pipelineHelpers.getServicePorts()
    def cleanLockfiles = config.cleanLockfiles != null ? config.cleanLockfiles : true
    def silent = config.silent ?: false

    def portsString = ports.join(' ')
    def lockfileCleanup = cleanLockfiles ? 'rm -f .e2e-port.json .e2e-jwt-token.json .dev-server-pid .e2e-services-pid' : ''

    sh """
        ${silent ? '' : 'echo "Cleaning up Docker and ports..."'}

        # Stop Docker containers (try v2 first, fall back to v1)
        (docker compose -f ${composeFile} down -v --remove-orphans 2>/dev/null || \
         docker-compose -f ${composeFile} down -v --remove-orphans 2>/dev/null) || true

        # Kill processes on specified ports
        # Try lsof first, fall back to fuser, then ss+kill
        for port in ${portsString}; do
            (lsof -ti :\$port 2>/dev/null || fuser \$port/tcp 2>/dev/null || ss -tlnp 2>/dev/null | grep ":\$port " | awk '{print \$NF}' | grep -oP 'pid=\\K[0-9]+') | xargs -r kill -9 2>/dev/null || true
        done

        # Clean lockfiles if requested
        ${lockfileCleanup}

        ${silent ? '' : 'echo "Cleanup complete"'}
    """
}

/**
 * Pre-test cleanup (more verbose)
 */
def preCleanup(Map config = [:]) {
    echo "Pre-test cleanup..."
    call(config + [silent: false])
}

/**
 * Post-test cleanup (quieter)
 */
def postCleanup(Map config = [:]) {
    echo "Post-test cleanup..."
    call(config + [silent: false])
}

return this
