# KinDash Jenkins Shared Library

Jenkins shared library for KinDash CI/CD pipelines.

## Overview

This repository contains reusable Groovy functions for Jenkins pipelines. It follows the decoupled architecture pattern where pipeline logic is separated from application code, allowing:

- **Pipeline independence**: CI/CD changes don't require app PR reviews
- **Break the chicken-egg**: Fix broken pipelines without app CI passing
- **Reusability**: Library usable across multiple projects
- **Faster iteration**: Direct push for infrastructure fixes

## Directory Structure

```
kindash-jenkins-lib/
├── vars/                    # Global pipeline functions
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
├── src/                     # Reserved for Groovy classes
└── resources/               # Reserved for non-Groovy resources
```

## Usage

### In Jenkinsfile

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

    post {
        always {
            publishReports()
        }
        failure {
            createGitHubIssue()
        }
    }
}
```

## Available Functions

### Core Pipeline Functions

| Function | Description |
|----------|-------------|
| `installDependencies()` | Smart package manager detection (pnpm/yarn/npm) and dependency installation |
| `buildProject()` | Build and archive artifacts |
| `runUnitTests()` | Execute Jest/Vitest unit tests with coverage |
| `runLintChecks()` | Run ESLint and type checking |
| `runE2ETests()` | Execute Playwright E2E tests with infrastructure |
| `runIntegrationTests()` | Run integration tests with database setup |

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

## Jenkins Configuration

### Global Pipeline Library Setup

Configure in Jenkins → Manage Jenkins → System → Global Pipeline Libraries:

| Setting | Value |
|---------|-------|
| Name | `kindash-lib` |
| Default version | `main` |
| Retrieval method | Modern SCM |
| Source Code Management | Git |
| Project Repository | `https://github.com/steiner385/kindash-jenkins-lib.git` |
| Credentials | `github-credentials` |

### Required Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `github-token` | Secret text | GitHub API access |
| `github-credentials` | Username/Password | Git authentication |
| `aws-access-key-id` | Secret text | AWS Bedrock access |
| `aws-secret-access-key` | Secret text | AWS Bedrock secret |
| `aws-region` | Secret text | AWS region (us-east-1) |

## Development

### Making Changes

1. Clone this repository
2. Make changes to functions in `vars/`
3. Push directly to `main` (no PR required for infrastructure)
4. Changes take effect on next build of any project using this library

### Testing

Test changes by:
1. Pushing to this repo
2. Triggering a build in any project that uses `@Library('kindash-lib@main')`
3. Checking Jenkins console output for `Loading library kindash-lib@main`

## Related Repositories

- [KinDash](https://github.com/steiner385/KinDash) - Main application
- [jenkins-config](https://github.com/steiner385/jenkins-config) - JCasC configuration

## License

MIT
