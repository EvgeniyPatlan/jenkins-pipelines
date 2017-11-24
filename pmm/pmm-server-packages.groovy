pipeline {
    environment {
        specName = '3rd-party'
        repo     = 'percona/pmm-server-packaging'
    }
    agent {
        label 'centos7-64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/pmm-server-packaging repository checkout',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm-experimental',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
        string(
            defaultValue: '1.5.1',
            description: 'version of result package',
            name: 'VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        pollSCM '* * * * *'
    }

    stages {
        stage('Fetch spec files') {
            steps {
                script {
                    try {
                        git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
                    } catch (error) {
                        deleteDir()
                        sh """
                            git clone https://github.com/${repo}.git ./
                            git checkout ${GIT_BRANCH}
                        """
                    }
                }
                sh '''
                    git rev-parse HEAD         > gitCommit
                    git rev-parse --short HEAD > shortCommit
                '''
                stash includes: 'gitCommit,shortCommit', name: 'gitCommit'
            }
        }

        stage('Fetch sources') {
            steps {
                sh """
                    rm -rf  rhel/SPECS/percona-dashboards*.spec \
                            rhel/SPECS/rds_exporter*.spec \
                            rhel/SPECS/pmm-server*.spec \
                            rhel/SPECS/pmm-manage*.spec \
                            rhel/SPECS/pmm-update*.spec \
                            rhel/SPECS/percona-qan-api*.spec \
                            rhel/SPECS/percona-qan-app*.spec
                    ls rhel/SPECS/*.spec | xargs -n 1 spectool -g -C rhel/SOURCES
                """
            }
        }

        stage('Build SRPMs') {
            steps {
                sh """
                    sed -i -e 's/.\\/run.bash/#.\\/run.bash/' rhel/SPECS/golang.spec
                    rpmbuild --define "_topdir rhel" -bs rhel/SPECS/*.spec
                """
            }
        }

        stage('Build Golang') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/golang-1.*.src.rpm'
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/go-srpm-macros-*.src.rpm'
            }
        }

        stage('Build RPMs') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            steps {
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
                stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
            }
        }

        stage('Upload to repo.ci.percona.com') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            agent { label 'master' }
            steps {
                deleteDir()
                unstash 'rpms'
                unstash 'gitCommit'
                sh """
                    export path_to_build="${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}"

                    ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com \
                    mkdir -p UPLOAD/\${path_to_build}/source/redhat \
                             UPLOAD/\${path_to_build}/binary/redhat/7/x86_64

                    scp -i ~/.ssh/id_rsa \
                        `find result-repo -name '*.src.rpm'` \
                        uploader@repo.ci.percona.com:UPLOAD/\${path_to_build}/source/redhat/

                    scp -i ~/.ssh/id_rsa \
                        `find result-repo -name '*.noarch.rpm' -o -name '*.x86_64.rpm'` \
                        uploader@repo.ci.percona.com:UPLOAD/\${path_to_build}/binary/redhat/7/x86_64/
                """
            }
        }

        stage('Sign RPMs') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            agent { label 'master' }
            steps {
                unstash 'gitCommit'
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    sh """
                        export path_to_build="${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}"

                        ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com " \
                            /bin/bash -xc ' \
                                ls UPLOAD/\${path_to_build}/binary/redhat/7/x86_64/*.rpm \
                                    | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm \
                            '"
                    """
                }
            }
        }

        stage('Push to RPM repository') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            agent any
            steps {
                unstash 'gitCommit'
                script {
                    def path_to_build = sh(returnStdout: true, script: "echo ${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}").trim()
                    build job: 'push-to-rpm-repository', parameters: [string(name: 'PATH_TO_BUILD', value: "${path_to_build}"), string(name: 'DESTINATION', value: "${DESTINATION}")]
                    build job: 'sync-repos-to-production', parameters: [booleanParam(name: 'REVERSE', value: false)]
                }
            }
        }
    }

    post {
        success {
            slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished"
            deleteDir()
        }
        unstable {
            slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build skipped"
            deleteDir()
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
            archiveArtifacts "result-repo/results/epel-7-x86_64/*/*.log"
            deleteDir()
        }
    }
}
