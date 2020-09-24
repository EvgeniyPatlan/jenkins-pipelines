library changelog: false, identifier: 'lib@psjen', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/EvgeniyPatlan/jenkins-pipelines.git'
]) _

void checkBranches(String GIT_REPO) {
    sh """
        set -o xtrace
        mkdir test
        pwd -P
        ls -laR
        git -c 'versionsort.suffix=-' ls-remote --tags --sort='v:refname' $GIT_REPO
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/percona-server.git',
            description: 'URL for percona-server repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-server repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: '8.0.20-11',
            description: 'VERSION value',
            name: 'VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Get release branches') {
            steps {
                checkBranches(GIT_REPO)
            }
        }

    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
