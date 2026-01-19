#!/usr/bin/env groovy
/**
 * KinDash CI Pipeline - Master Definition
 *
 * This is the primary Jenkins pipeline definition for KinDash.
 * Jenkins is configured to reference this Jenkinsfile directly from:
 * https://github.com/steiner385/kindash-jenkins-lib
 *
 * Shared library functions are defined in the vars/ directory of this repository.
 *
 * Webhook Setup:
 *   URL: https://jenkins.kindash.com/generic-webhook-trigger/invoke?token=kindash-ci
 *   Content type: application/json
 *   Events: Push events only (pull_request events are filtered out to prevent duplicates)
 *
 * For more details, see README.md in this repository.
 */

// Load shared library directly from GitHub (self-contained, no global config required)
library identifier: 'kindash-lib@main',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/steiner385/kindash-jenkins-lib.git',
        credentialsId: 'github-credentials'
    ])

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds(abortPrevious: true)
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'ref', value: '$.ref'],
                [key: 'after', value: '$.after'],
                [key: 'before', value: '$.before'],
                [key: 'pusher_name', value: '$.pusher.name'],
                [key: 'repository_name', value: '$.repository.name'],
                [key: 'repository_full_name', value: '$.repository.full_name'],
                [key: 'pr_action', value: '$.action'],
                [key: 'pr_head_ref', value: '$.pull_request.head.ref'],
                [key: 'pr_head_sha', value: '$.pull_request.head.sha']
            ],
            causeString: 'Triggered by push to $ref by $pusher_name',
            token: 'kindash-ci',
            printContributedVariables: true,
            printPostContent: false,
            silentResponse: false,
            regexpFilterText: '$ref',
            regexpFilterExpression: 'refs/heads/.*'
        )
    }

    environment {
        GITHUB_OWNER = 'steiner385'
        GITHUB_REPO = 'KinDash'
        CI = 'true'
        NODE_ENV = 'test'
        NODE_OPTIONS = '--max-old-space-size=6144'
    }

    stages {
        stage('Initialize') {
            steps {
                // Checkout KinDash repo (triggered by webhook with $ref and $after variables)
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: env.ref ?: '*/main']],
                    userRemoteConfigs: [[
                        url: "https://github.com/${env.GITHUB_OWNER}/${env.GITHUB_REPO}.git",
                        credentialsId: 'github-credentials'
                    ]]
                ])
                script {
                    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                }
                echo "Building KinDash commit: ${env.GIT_COMMIT_SHORT}"
            }
        }

        stage('Install Dependencies') {
            steps {
                installDependencies()
            }
        }

        stage('Lint') {
            steps {
                runLintChecks(skipCheckout: true)
            }
        }

        stage('Unit Tests') {
            steps {
                runUnitTests(skipCheckout: true)
            }
        }

        stage('Build') {
            steps {
                buildProject(skipCheckout: true)
            }
        }
    }

    post {
        always {
            publishReports(
                junit: true,
                allure: true,
                coverage: true
            )
        }
        success {
            echo 'Pipeline succeeded!'
            githubStatusReporter(
                status: 'success',
                context: 'jenkins/ci',
                description: 'CI pipeline passed'
            )
        }
        failure {
            echo 'Pipeline failed!'
            githubStatusReporter(
                status: 'failure',
                context: 'jenkins/ci',
                description: 'CI pipeline failed'
            )
            script {
                if (fileExists('test-results.json')) {
                    analyzeTestFailures(
                        testResultsFile: 'test-results.json',
                        maxIssues: 10
                    )
                }
            }
        }
    }
}
