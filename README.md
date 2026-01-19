# KinDash Jenkins Shared Library

Jenkins shared library and configuration for KinDash CI/CD pipelines.

## Overview

This repository contains everything needed to run KinDash CI/CD on Jenkins:
- **Shared Library Functions** - Reusable Groovy functions for pipelines
- **JCasC Configuration** - Jenkins Configuration as Code for automated setup

## Directory Structure

```
kindash-jenkins-lib/
├── vars/                    # Shared library functions
│   ├── analyzeAllureFailures.groovy
│   ├── analyzeTestFailures.groovy
│   ├── buildProject.groovy
│   ├── createGitHubIssue.groovy
│   ├── dockerCleanup.groovy
│   ├── githubStatusReporter.groovy
│   ├── installDependencies.groovy
│   ├── pipelineHelpers.groovy
│   ├── pipelineInit.groovy
│   ├── playwrightSetup.groovy
│   ├── publishReports.groovy
│   ├── runE2ETests.groovy
│   ├── runIntegrationTests.groovy
│   ├── runLintChecks.groovy
│   ├── runUnitTests.groovy
│   └── withAwsCredentials.groovy
├── config/                  # JCasC configuration
│   └── jenkins.yaml         # Full Jenkins configuration
├── src/                     # Reserved for Groovy classes
└── resources/               # Reserved for non-Groovy resources
```

## Quick Start

### 1. Configure Global Pipeline Library

In Jenkins → Manage Jenkins → System → Global Pipeline Libraries:

| Setting | Value |
|---------|-------|
| Name | `kindash-lib` |
| Default version | `main` |
| Retrieval method | Modern SCM |
| Project Repository | `https://github.com/steiner385/kindash-jenkins-lib.git` |
| Credentials | `github-credentials` |

### 2. Use in Jenkinsfile

```groovy
@Library('kindash-lib@main') _

pipeline {
    agent any

    stages {
        stage('Install') {
            steps {
                installDependencies()
            }
        }
        stage('Test') {
            steps {
                runUnitTests()
            }
        }
        stage('Build') {
            steps {
                buildProject()
            }
        }
    }
}
```

## Available Functions

### Core Pipeline Functions

| Function | Description |
|----------|-------------|
| `installDependencies()` | Auto-detects package manager (pnpm/yarn/npm) and installs |
| `buildProject()` | Build and archive artifacts |
| `runUnitTests()` | Execute unit tests with coverage |
| `runLintChecks()` | Run ESLint and type checking |
| `runE2ETests()` | Execute Playwright E2E tests |
| `runIntegrationTests()` | Run integration tests with Docker |

### Reporting Functions

| Function | Description |
|----------|-------------|
| `publishReports()` | Publish JUnit, Allure, and Playwright reports |
| `githubStatusReporter()` | Report build status to GitHub commit API |
| `createGitHubIssue()` | Create GitHub issues for test failures |
| `analyzeTestFailures()` | Group test failures by error signature |
| `analyzeAllureFailures()` | Parse Allure results and create issues |

### Utility Functions

| Function | Description |
|----------|-------------|
| `pipelineHelpers` | Helper utilities (package manager detection, paths) |
| `pipelineInit()` | Pipeline initialization and checkout |
| `playwrightSetup()` | Install and cache Playwright browsers |
| `dockerCleanup()` | Clean up Docker containers and ports |
| `withAwsCredentials()` | Inject AWS Bedrock credentials |

## JCasC Configuration

The `config/jenkins.yaml` file contains the complete Jenkins configuration:
- Global Pipeline Library setup
- Agent/node definitions
- Credentials configuration
- Job definitions (KinDash-ci, KinDash-nightly, etc.)

### Deploying JCasC

Copy to your Jenkins controller:
```bash
cp config/jenkins.yaml $JENKINS_HOME/casc_configs/kindash.yaml
```

Or reference directly in Jenkins Configuration as Code plugin settings.

### Webhook Configuration

The main CI pipeline uses GenericTrigger for webhook-based builds.

**GitHub Webhook Setup:**
1. Go to KinDash repo → Settings → Webhooks → Add webhook
2. Configure:
   - **URL**: `https://jenkins.kindash.com/generic-webhook-trigger/invoke?token=kindash-ci`
   - **Content type**: `application/json`
   - **Events**: Push events

## Required Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `github-token` | Secret text | GitHub API access |
| `github-credentials` | Username/Password | Git authentication |
| `aws-access-key-id` | Secret text | AWS Bedrock access |
| `aws-secret-access-key` | Secret text | AWS Bedrock secret |
| `aws-region` | Secret text | AWS region (us-east-1) |

## Package Manager Auto-Detection

The library automatically detects the package manager based on lock files:

| Lock File | Package Manager | Install Command |
|-----------|-----------------|-----------------|
| `pnpm-lock.yaml` | pnpm | `npx pnpm install --frozen-lockfile` |
| `yarn.lock` | yarn | `yarn install --frozen-lockfile` |
| `package-lock.json` | npm | `npm ci` |

## Development

### Making Changes

1. Clone this repository
2. Make changes to functions in `vars/`
3. Push directly to `main` (no PR required for infrastructure)
4. Changes take effect on next build of any project using this library

### Testing

Test changes by:
1. Pushing to this repo
2. Triggering a build in KinDash
3. Checking Jenkins console output for `Loading library kindash-lib@main`

## Architecture

This repository follows the decoupled CI/CD pattern:

```
┌─────────────────────────────────────────────────────────┐
│                    GitHub                                │
│  ┌─────────────────┐     ┌─────────────────────────┐   │
│  │    KinDash      │     │  kindash-jenkins-lib    │   │
│  │  (app code)     │     │  (CI/CD config)         │   │
│  │                 │     │                         │   │
│  │ .jenkins/       │     │ vars/     (functions)   │   │
│  │   Jenkinsfile*  │     │ config/   (JCasC)       │   │
│  └────────┬────────┘     └───────────┬─────────────┘   │
│           │                          │                  │
└───────────│──────────────────────────│──────────────────┘
            │                          │
            │    Webhook               │  Library Load
            ▼                          ▼
┌─────────────────────────────────────────────────────────┐
│                      Jenkins                             │
│  ┌─────────────────────────────────────────────────┐   │
│  │              KinDash-ci Job                      │   │
│  │  1. Loads kindash-lib@main                      │   │
│  │  2. Runs Jenkinsfile from KinDash repo          │   │
│  │  3. Uses shared library functions               │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Benefits

- **Pipeline independence** - CI/CD changes don't require app PR reviews
- **Break the chicken-egg** - Can fix broken pipelines without app CI passing
- **Reusability** - Library usable across multiple projects
- **Faster iteration** - Direct push for infrastructure fixes

## Related Repositories

- [KinDash](https://github.com/steiner385/KinDash) - Main application

## License

MIT
