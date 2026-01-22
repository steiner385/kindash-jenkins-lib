#!/usr/bin/env groovy
/**
 * Docker Compose Helper
 * Executes docker compose commands using V2 (preferred) or V1 as fallback
 *
 * Features:
 * - Prefers docker compose (V2) plugin - more stable with container metadata
 * - Only falls back to docker-compose (V1) if V2 isn't installed
 * - IMPORTANT: V1 (python) crashes on corrupted container metadata (KeyError: 'ContainerConfig')
 *   so we avoid using V1 when V2 is available
 * - Configurable compose file path
 * - Optional COMPOSE_PROJECT_NAME for consistent network naming
 *
 * Usage:
 *   dockerCompose('up -d')
 *   dockerCompose('build --parallel', 'docker/docker-compose.e2e.yml')
 *   dockerCompose('ps', 'docker/docker-compose.e2e.yml')
 *   dockerCompose('down -v', 'docker/docker-compose.e2e.yml', 'kindash')
 */

def call(String command, String composeFile = 'docker-compose.yml', String projectName = '') {
    def prefix = projectName ? "COMPOSE_PROJECT_NAME=${projectName} " : ''
    // Check if V2 exists FIRST, only fall back to V1 if V2 isn't installed
    // Don't fall back if V2 fails - V1 crashes on corrupted container metadata
    sh """
        if docker compose version >/dev/null 2>&1; then
            echo "[dockerCompose] Using Docker Compose V2"
            ${prefix}docker compose -f ${composeFile} ${command}
        elif command -v docker-compose >/dev/null 2>&1; then
            echo "[dockerCompose] V2 not available, using Docker Compose V1"
            ${prefix}docker-compose -f ${composeFile} ${command}
        else
            echo "[dockerCompose] ERROR: Neither docker compose nor docker-compose is available"
            exit 1
        fi
    """
}

/**
 * Execute docker compose command but ignore errors (useful for cleanup)
 */
def safe(String command, String composeFile = 'docker-compose.yml', String projectName = '') {
    def prefix = projectName ? "COMPOSE_PROJECT_NAME=${projectName} " : ''
    sh """
        if docker compose version >/dev/null 2>&1; then
            echo "[dockerCompose.safe] Using Docker Compose V2"
            ${prefix}docker compose -f ${composeFile} ${command} || true
        elif command -v docker-compose >/dev/null 2>&1; then
            echo "[dockerCompose.safe] V2 not available, using Docker Compose V1"
            ${prefix}docker-compose -f ${composeFile} ${command} || true
        else
            echo "[dockerCompose.safe] No docker compose available, skipping"
        fi
    """
}

return this