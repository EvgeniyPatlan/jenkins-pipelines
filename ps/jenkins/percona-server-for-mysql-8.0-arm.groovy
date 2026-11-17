/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@d5b1fcc62821af27c544b5caf17d84ed7275f44b', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

@Field final Map BUILD_IMAGES = [
    'oraclelinux:8'    : 'oraclelinux:8@sha256:9225108d00fb6611ebc4a5eced166f27d9dcfeaa539fb127be79425f0da5e745',
    'oraclelinux:9'    : 'oraclelinux:9@sha256:06f1c40f41e4026172cc2065103e313e454747ca48d40d165097c5297d764b38',
    'amazonlinux:2023' : 'amazonlinux:2023@sha256:8ee95c4096d455555cb208d1dcea7b759235afab02253351d566d22d904f2198',
    'ubuntu:focal'     : 'ubuntu:focal@sha256:8feb4d8ca5354def3d8fce243717141ce31e2c428701f6682bd2fafe15388214',
    'ubuntu:jammy'     : 'ubuntu:jammy@sha256:9abcaffc92a5279fff3a6a41635c0800ea33e528425fb389658806476e7defde',
    'ubuntu:noble'     : 'ubuntu:noble@sha256:73b999393d07f2bf65a40373778a44dc9a6d54ad7718d29247d8ff9413c4d6a8',
    'debian:bullseye'  : 'debian:bullseye@sha256:6f519a81440354a85eb592c5f32109ab80605f6b892455983a6f618bf87fabe9',
    'debian:bookworm'  : 'debian:bookworm@sha256:268fb24c39bca92d326dd83ea3abebe4a3f7751c804ffef23bb6d857dd343ff8',
    'centos:7'         : 'centos:7@sha256:be65f488b7764ad3638f236b7b515b3678369a5124c47b8d32916d6487418ea4',
    'debian:buster'    : 'debian:buster@sha256:58ce6f1271ae1c8a2006ff7d3e54e9874d839f573d8009c20154ad0f2fb0a225',
]

// Filled from the output of the ps-build-images job, keyed '<series>/<base image>'.
@Field final Map PREPARED_IMAGES = [:]

@Field String PS_SERIES = ''

// Test job overlay (branch test-build-hardening only): nothing below publishes.
@Field final boolean TEST_MODE = true

void testSkip(String step) {
    echo "TEST_MODE: skipped ${step}"
}
void testSlackNotify(def a0, def a1, def a2) { testSkip("slackNotify") }
void testUploadRPMfromAWS(def a0, def a1) { testSkip("uploadRPMfromAWS") }
void testUploadDEBfromAWS(def a0, def a1) { testSkip("uploadDEBfromAWS") }
void testUploadTarballfromAWS(def a0, def a1, def a2) { testSkip("uploadTarballfromAWS") }
void testSignRPM() { testSkip("signRPM") }
void testSignDEB() { testSkip("signDEB") }
void testSync2ProdAutoBuild(def a0, def a1) { testSkip("sync2ProdAutoBuild") }
void testSync2PrivateProdAutoBuild(def a0, def a1) { testSkip("sync2PrivateProdAutoBuild") }
void testUploadTarballToDownloadsTesting(def a0, def a1) { testSkip("uploadTarballToDownloadsTesting") }

void verifyTestArtifacts(String stashPath, String arch) {
    for (String folder : ['source_tarball/', 'srpm/', 'source_deb/', 'rpm/', 'deb/', 'tarball/']) {
        popArtifactFolder(folder, stashPath)
    }
    withEnv(["ARCH=${arch}",
             "RPM_IMAGE=${BUILD_IMAGES['oraclelinux:9']}",
             "DEB_IMAGE=${BUILD_IMAGES['ubuntu:jammy']}"]) {
        writeFile file: 'verify-test-artifacts.sh', text: '''#!/bin/bash
set -o nounset -o pipefail
failures=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
pass() { echo "PASS: $*"; }
case "${ARCH}" in
    amd64) RPM_ARCH=x86_64 ;;
    arm64) RPM_ARCH=aarch64 ;;
    *) echo "unknown arch ${ARCH}"; exit 1 ;;
esac
shopt -s nullglob

if [ "${ARCH}" = amd64 ]; then
    for d in source_tarball srpm source_deb; do
        n=$(find "${d}" -type f 2>/dev/null | wc -l)
        [ "${n}" -gt 0 ] && pass "${d}: ${n} files" || fail "${d} is empty"
    done
fi
rpms=(rpm/*."${RPM_ARCH}".rpm)
debs=(deb/*_"${ARCH}".deb)
tarballs=(tarball/*."${RPM_ARCH}".*.tar.gz)
[ ${#rpms[@]} -gt 0 ] && pass "${#rpms[@]} ${RPM_ARCH} rpms" || fail "no ${RPM_ARCH} rpms"
[ ${#debs[@]} -gt 0 ] && pass "${#debs[@]} ${ARCH} debs" || fail "no ${ARCH} debs"
[ ${#tarballs[@]} -gt 0 ] && pass "${#tarballs[@]} ${RPM_ARCH} tarballs" || fail "no ${RPM_ARCH} tarballs"

for t in "${tarballs[@]}"; do
    name=$(basename "${t}")
    if [ -f "${t}.sha256" ] && (cd tarball && sha256sum --quiet -c "${name}.sha256"); then
        pass "${name}: sha256 matches"
    else
        fail "${name}: missing or wrong .sha256"
    fi
    tar -tzf "${t}" > tarball-list.txt
    grep -q '/lib/private/BUNDLED-LIBS.txt$' tarball-list.txt && pass "${name}: BUNDLED-LIBS.txt present" || fail "${name}: no BUNDLED-LIBS.txt"
done
rm -f tarball-list.txt

cat > check-callhome.sh <<'EOF'
check_scriptlet() {
    if grep -q 'mktemp -d' "$2" && grep -q 'rm -rf "$(dirname "$tfn")"' "$2"; then
        echo "PASS: $1 runs call-home from a private temp dir and removes it"
    else
        echo "FAIL: $1 call-home temp handling"; return 1
    fi
    if grep -q '/tmp/call-home.sh' "$2"; then
        echo "FAIL: $1 still references /tmp/call-home.sh"; return 1
    fi
}
# The server packages depend on percona-telemetry-agent, which is not built here; an empty
# package of that name keeps the install offline and the agent from running.
placeholder_telemetry_deb() {
    mkdir -p /root/pta/DEBIAN
    printf 'Package: percona-telemetry-agent\\nVersion: 0.0-test\\nArchitecture: all\\nMaintainer: test\\nDescription: placeholder\\n' > /root/pta/DEBIAN/control
    dpkg-deb -b /root/pta /root/percona-telemetry-agent.deb >/dev/null
}
placeholder_telemetry_rpm() {
    dnf -y -q install rpm-build >/dev/null || return 1
    printf 'Name: percona-telemetry-agent\\nVersion: 0.0\\nRelease: test\\nSummary: placeholder\\nLicense: none\\nBuildArch: noarch\\n%%description\\nplaceholder\\n%%files\\n' > /root/pta.spec
    rpmbuild -bb --quiet /root/pta.spec >/dev/null
}
tmp_before() { find /tmp -mindepth 1 -maxdepth 1 | sort > /root/tmp-before.txt; }
tmp_after() {
    find /tmp -mindepth 1 -maxdepth 1 | sort > /root/tmp-after.txt
    if [ -e /tmp/call-home.sh ] || comm -13 /root/tmp-before.txt /root/tmp-after.txt | grep -q .; then
        echo "FAIL: install left files in /tmp:"; comm -13 /root/tmp-before.txt /root/tmp-after.txt; return 1
    fi
    echo "PASS: install left nothing in /tmp"
}
smoke_mysqld() {
    mysqld --version || { echo "FAIL: mysqld --version"; return 1; }
    rm -rf /var/tmp/verify-datadir
    if mysqld --no-defaults --initialize-insecure --user=root --datadir=/var/tmp/verify-datadir >/var/tmp/init.log 2>&1; then
        echo "PASS: mysqld --initialize-insecure"
    else
        cat /var/tmp/init.log; echo "FAIL: mysqld --initialize-insecure"; return 1
    fi
}
EOF

if [ ${#rpms[@]} -gt 0 ]; then
    docker run --rm --platform "linux/${ARCH}" -e PERCONA_TELEMETRY_DISABLE=1 -e RPM_ARCH="${RPM_ARCH}" \\
        -v "${PWD}/rpm:/rpm:ro" -v "${PWD}/check-callhome.sh:/check-callhome.sh:ro" "${RPM_IMAGE}" bash -c '
        set -o pipefail
        . /check-callhome.sh
        rc=0
        shopt -s nullglob
        srv=(/rpm/percona-server-server-[0-9]*.el9.${RPM_ARCH}.rpm)
        [ ${#srv[@]} -gt 0 ] || { echo "FAIL: no el9 ${RPM_ARCH} server rpm"; exit 1; }
        rpm -qp --scripts "${srv[0]}" > /root/scripts.txt
        check_scriptlet "$(basename "${srv[0]}") %post" /root/scripts.txt || rc=1
        pkgs=$(ls /rpm/*.el9.${RPM_ARCH}.rpm | grep -E "/percona-(server-(server|client|shared)|icu-data-files)-[0-9]")
        placeholder_telemetry_rpm || exit 1
        tmp_before
        dnf -y -q install ${pkgs} /root/rpmbuild/RPMS/noarch/percona-telemetry-agent-*.rpm || { echo "FAIL: dnf install"; exit 1; }
        echo "PASS: el9 ${RPM_ARCH} packages installed"
        tmp_after || rc=1
        smoke_mysqld || rc=1
        exit ${rc}
    ' || fail "el9 ${RPM_ARCH} package checks"
fi

if [ ${#debs[@]} -gt 0 ]; then
    docker run --rm --platform "linux/${ARCH}" -e PERCONA_TELEMETRY_DISABLE=1 -e ARCH="${ARCH}" -e DEBIAN_FRONTEND=noninteractive \\
        -v "${PWD}/deb:/deb:ro" -v "${PWD}/check-callhome.sh:/check-callhome.sh:ro" "${DEB_IMAGE}" bash -c '
        set -o pipefail
        . /check-callhome.sh
        rc=0
        shopt -s nullglob
        srv=(/deb/percona-server-server_*.jammy_${ARCH}.deb)
        [ ${#srv[@]} -gt 0 ] || { echo "FAIL: no jammy ${ARCH} server deb"; exit 1; }
        dpkg-deb -e "${srv[0]}" /root/ctl
        check_scriptlet "$(basename "${srv[0]}") postinst" /root/ctl/postinst || rc=1
        apt-get update -qq
        placeholder_telemetry_deb || exit 1
        tmp_before
        apt-get install -y -qq /root/percona-telemetry-agent.deb /deb/percona-server-common_*.jammy_${ARCH}.deb \\
            /deb/percona-server-client_*.jammy_${ARCH}.deb /deb/percona-server-server_*.jammy_${ARCH}.deb \\
            || { echo "FAIL: apt-get install"; exit 1; }
        echo "PASS: jammy ${ARCH} packages installed"
        tmp_after || rc=1
        smoke_mysqld || rc=1
        exit ${rc}
    ' || fail "jammy ${ARCH} package checks"
fi

echo "failures: ${failures}"
[ "${failures}" -eq 0 ]
'''
        sh "#!/bin/bash\nset -o pipefail\nbash verify-test-artifacts.sh 2>&1 | tee verify-${arch}.txt"
    }
}

String pinnedImage(String image) {
    if (image == 'none') {
        return image
    }
    if (!BUILD_IMAGES.containsKey(image)) {
        error("No pinned digest for build image ${image}")
    }
    return PREPARED_IMAGES["${PS_SERIES}/${image}"] ?: BUILD_IMAGES[image]
}

String psSeries(String propertiesFile) {
    return sh(returnStdout: true, script: "grep -E '^MYSQL_VERSION_(MAJOR|MINOR)=' ${propertiesFile} | cut -d= -f2 | paste -sd. -").trim()
}

void checksumTarballs() {
    sh '''
        cd tarball
        for f in *.tar.gz; do
            sha256sum "${f}" > "${f}.sha256"
        done
    '''
}

void installCli(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip
        elif [ ${PLATFORM} = "rpm" ]; then
            export RHVER=\$(rpm --eval %rhel)
            if [ \${RHVER} = "7" ]; then
                sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || true
                sudo sed -i 's|#\\s*baseurl=http://mirror.centos.org|baseurl=https://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || true
                if [ -e "/etc/yum.repos.d/CentOS-SCLo-scl.repo" ]; then
                    cat /etc/yum.repos.d/CentOS-SCLo-scl.repo
                fi
            fi
            sudo yum -y install wget curl unzip
        fi
        AWSCLI_VERSION=2.40.4
        AWSCLI_ARCH=\$(uname -m)
        case \${AWSCLI_ARCH} in
            x86_64) AWSCLI_SHA256=81f573c2a0ace70cfb97aa4d014c17cbd605cb04cbad09adbc7aaf9c0979ae8e ;;
            aarch64) AWSCLI_SHA256=88961950fb3ef0b7190b40a1bccad0308b69186dbc575cb7c999b8c18959c635 ;;
            *) echo "Unsupported architecture for awscli: \${AWSCLI_ARCH}"; exit 1 ;;
        esac
        curl -fsSL --proto '=https' -o awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-\${AWSCLI_ARCH}-\${AWSCLI_VERSION}.zip
        echo "\${AWSCLI_SHA256}  awscliv2.zip" | sha256sum -c -
        unzip awscliv2.zip
        sudo ./aws/install || true
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    String image = pinnedImage(DOCKER_OS)
    String prepared = image.startsWith('perconalab/ps-build:') ? 'yes' : 'no'
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        export build_dir=\$(pwd -P)
        if [ "$DOCKER_OS" = "none" ]; then
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-8.0.properties ]; then
                . ./test/percona-server-8.0.properties
            fi
            sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            if [ ${BUILD_TOKUDB_TOKUBACKUP} = "ON" ]; then
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            else
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            fi
        else
            docker run -u root -v \${build_dir}:\${build_dir} ${image} sh -c "
                set -o xtrace
                cd \${build_dir}
                if [ -f ./test/percona-server-8.0.properties ]; then
                    . ./test/percona-server-8.0.properties
                fi
                if [ ${prepared} = no ]; then
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                fi
                if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                else
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                fi"
        fi
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def installDependencies(def nodeName) {
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-focal-x64', 'min-jammy-x64', 'min-noble-x64']
    def yumNodes = ['min-ol-8-x64', 'min-centos-7-x64', 'min-ol-9-x64', 'min-amazon-2-x64']
    try{
        if (aptNodes.contains(nodeName)) {
            if(nodeName == "min-bullseye-x64" || nodeName == "min-bookworm-x64"){
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y ansible git wget
                '''
            }else if(nodeName == "min-focal-x64" || nodeName == "min-jammy-x64" || nodeName == "min-noble-x64"){
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y software-properties-common
                    sudo apt-add-repository --yes --update ppa:ansible/ansible
                    sudo apt-get install -y ansible git wget
                '''
            }else {
                error "Node Not Listed in APT"
            }
        } else if (yumNodes.contains(nodeName)) {

            if(nodeName == "min-centos-7-x64" || nodeName == "min-ol-9-x64"){
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible git wget tar
                '''
            }else if(nodeName == "min-ol-8-x64"){
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible-2.9.27 git wget tar
                '''
            }else if(nodeName == "min-amazon-2-x64"){
                sh '''
                    sudo amazon-linux-extras install epel
                    sudo yum -y update
                    sudo yum install -y ansible git wget
                '''
            }
            else {
                error "Node Not Listed in YUM"
            }
        } else {
            echo "Unexpected node name: ${nodeName}"
        }
    } catch (Exception e) {
        testSlackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Server Provision for Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!")
    }

}

def runPlaybook(def nodeName) {

    try {
        def playbook = "ps_lts_innovation.yml"
        def playbook_path = "package-testing/playbooks/${playbook}"

        sh '''
            set -xe
            git clone --depth 1 https://github.com/Percona-QA/package-testing
        '''
        sh """
            set -xe
            export install_repo="\${install_repo}"
            export client_to_test="ps80"
            export check_warning="\${check_warnings}"
            export install_mysql_shell="\${install_mysql_shell}"
            ansible-playbook \
            --connection=local \
            --inventory 127.0.0.1, \
            --limit 127.0.0.1 \
            ${playbook_path}
        """
    } catch (Exception e) {
        testSlackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!!")
        mini_test_error="True"
    }
}

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-centos-7-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-amazon-2-x64",
                       "min-jammy-x64",
                       "min-noble-x64",
                       "min-ol-9-x64"     ]

def package_tests_ps80(def nodes) {
    def stepsForParallel = [:]
    for (int i = 0; i < nodes.size(); i++) {
        def nodeName = nodes[i]
        stepsForParallel[nodeName] = {
            stage("Minitest run on ${nodeName}") {
                node(nodeName) {
                        installDependencies(nodeName)
                        runPlaybook(nodeName)
                }
            }
        }
    }
    parallel stepsForParallel
}

@Field def mini_test_error = "False"
def AWS_STASH_PATH
def PS8_RELEASE_VERSION
def product_to_test = 'innovation-lts'
def install_repo = 'testing'
def action_to_test = 'install'
def check_warnings = 'yes'
def install_mysql_shell = 'no'

pipeline {
    agent {
        label 'docker'
    }
parameters {
        string(defaultValue: 'https://github.com/EvgeniyPatlan/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'sec-build-hardening-8.4', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'The TokuDB storage is no longer supported since 8.0.28',
            name: 'BUILD_TOKUDB_TOKUBACKUP')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile with ZenFS support?, only affects Ubuntu Hirsute',
            name: 'ENABLE_ZENFS')
        choice(
            choices: 'NO',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases\n#releases-ci',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {

        stage('Create PS source tarball') {
            agent {
               label 'docker-32gb'
            }
            steps {
                testSlackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("rpm")
                buildStage("ubuntu:focal", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-8.0.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=test-sec-build-hardening/$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-8.0.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-8.0.properties', name: 'properties'
                script {
                    PS_SERIES = psSeries('test/percona-server-8.0.properties')
                    echo "PS series: ${PS_SERIES}"
                }
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                testUploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PS generic source packages') {
            parallel {
/*
                stage('Build PS generic source rpm') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        testUploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
*/
                stage('Build PS generic source deb') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:buster", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:buster", "--build_source_deb=1")
                            }
                        }

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        testUploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS RPMs/DEBs/Binary tarballs') {
            parallel {
/*
                stage('Centos 7') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("centos:7", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
*/
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
/*
                stage('Centos 7 binary tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("centos:7", "--build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 7 debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("centos:7", "--debug=1 --build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 binary tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--debug=1 --build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
                        }

                        checksumTarballs()
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ZenFS tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1 --with_zenfs=1")
                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--debug=1 --build_tarball=1")
                            }
                        }

                        checksumTarballs()
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--debug=1 --build_tarball=1")

                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
                        }

                        checksumTarballs()
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ZenFS tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --with_zenfs=1")
                                checksumTarballs()
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--debug=1 --build_tarball=1")
                            }
                        }

                        checksumTarballs()
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
*/
            }
        }
        stage('Verify artifacts') {
            parallel {
                stage('amd64') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        verifyTestArtifacts(AWS_STASH_PATH, 'amd64')
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'verify-*.txt', allowEmptyArchive: true
                        }
                    }
                }
                stage('arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        verifyTestArtifacts(AWS_STASH_PATH, 'arm64')
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'verify-*.txt', allowEmptyArchive: true
                        }
                    }
                }
            }
        }
        stage('Upload packages and tarballs from S3') {
            when {
                beforeAgent true
                expression { !TEST_MODE }
            }
            agent {
                label 'min-jammy-x64'
            }
            steps {
                cleanUpWS()
                installCli("deb")
                unstash 'properties'

                // testUploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                testUploadDEBfromAWS("deb/", AWS_STASH_PATH)
                // testUploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {
            when {
                beforeAgent true
                expression { !TEST_MODE }
            }
            steps {
              //  testSignRPM()
                testSignDEB()
            }
        }
        stage('Push to public repository') {
            when {
                beforeAgent true
                expression { !TEST_MODE }
            }
            steps {
                unstash 'properties'
                script {
                    MYSQL_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    PS_MAJOR_RELEASE = sh(returnStdout: true, script: ''' echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}' ''').trim()
                    // sync packages
                    if ("${MYSQL_VERSION_MINOR}" == "0") {
                        if (env.FIPSMODE == 'YES') {
                            testSync2PrivateProdAutoBuild("ps-80-pro", COMPONENT)
                        } else {
                            testSync2ProdAutoBuild("ps-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            testSync2PrivateProdAutoBuild("ps-80-innovation-pro", COMPONENT)
                        } else {
                            testSync2ProdAutoBuild("ps-8x-innovation", COMPONENT)
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                currentBuild.description = "TEST ${GIT_REPO} ${BRANCH} - s3://percona-jenkins-artifactory/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            testSlackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
