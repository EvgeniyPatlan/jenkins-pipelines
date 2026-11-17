import groovy.transform.Field

// Same pins as BUILD_IMAGES in the percona-server-for-mysql-* pipelines.
@Field final Map BASE_IMAGES = [
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

@Field final List AMD64_ONLY = ['centos:7', 'debian:buster']

@Field final Map ARCH_LABELS = [
    'amd64': 'docker-32gb',
    'arm64': 'docker-32gb-aarch64',
]

@Field final Map SERIES = [
    '8.0': [branch: 'sec-build-hardening-8.0', builder: 'build-ps/percona-server-8.0_builder.sh'],
    '8.4': [branch: 'sec-build-hardening-8.4', builder: 'build-ps/percona-server-8.0_builder.sh'],
    '9.7': [branch: 'sec-build-hardening-9.7', builder: 'build-ps/percona-server-9.0_builder.sh'],
]

@Field final String BUILDER_REPO = 'https://github.com/EvgeniyPatlan/percona-server'
@Field final String IMAGE_REPO = 'local/ps-build-test'

List archesFor(String os) {
    return AMD64_ONLY.contains(os) ? ['amd64'] : ['amd64', 'arm64']
}

String imageTag(String series, String os, String stamp) {
    return "${IMAGE_REPO}:${series}-${os.replace(':', '-')}-${stamp}"
}

void dockerLogin() {
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh '''
            set +x
            echo "${PASS}" | docker login -u "${USER}" --password-stdin
        '''
    }
}

void buildOne(String series, String commit, String os, String arch, String tag) {
    node(ARCH_LABELS[arch]) {
        deleteDir()
        checkout scm
        withEnv([
            "BASE_IMAGE=${BASE_IMAGES[os]}",
            "BUILDER_URL=https://raw.githubusercontent.com/EvgeniyPatlan/percona-server/${commit}/${SERIES[series].builder}",
            "BUILDER_COMMIT=${commit}",
            "BUILDER_REPO=${BUILDER_REPO}",
            "ARCH_TAG=${tag}-${arch}",
        ]) {
            sh '''
                set -o errexit
                rm -rf context
                mkdir context
                cp ps/build-images/Dockerfile context/
                curl -fsSL --proto '=https' -o context/ps_builder.sh "${BUILDER_URL}"
                docker build --pull \
                    --build-arg BASE_IMAGE="${BASE_IMAGE}" \
                    --build-arg BUILDER_REPO="${BUILDER_REPO}" \
                    --build-arg BUILDER_COMMIT="${BUILDER_COMMIT}" \
                    -t "${ARCH_TAG}" context
                docker run --rm "${ARCH_TAG}" sh -c 'test -s /usr/share/ps-build/installed-packages.txt && command -v cmake && command -v make'
                docker run --rm "${ARCH_TAG}" cat /usr/share/ps-build/installed-packages.txt > "installed-packages-${ARCH_TAG##*:}.txt"
                docker image rm "${ARCH_TAG}"
            '''
        }
        archiveArtifacts artifacts: 'installed-packages-*.txt'
        sh 'docker logout || true'
    }
}

String publishManifest(String tag, List arches) {
    String digest = ''
    node(ARCH_LABELS['amd64']) {
        dockerLogin()
        withEnv(["TAG=${tag}", "SOURCES=${arches.collect { "${tag}-${it}" }.join(' ')}"]) {
            digest = sh(returnStdout: true, script: '''
                set -o errexit
                docker manifest rm "${TAG}" >/dev/null 2>&1 || true
                docker manifest create "${TAG}" ${SOURCES} >&2
                docker manifest push --purge "${TAG}" | tail -n1
            ''').trim()
        }
        sh 'docker logout || true'
    }
    if (!(digest ==~ /sha256:[0-9a-f]{64}/)) {
        error("Unexpected manifest digest for ${tag}: '${digest}'")
    }
    return digest
}

pipeline {
    agent none
    parameters {
        choice(name: 'PS_SERIES', choices: ['8.4', '8.0', '9.7', 'all'], description: 'PS series to build images for')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
        timestamps()
    }
    stages {
        stage('Build and push images') {
            steps {
                script {
                    List seriesList = params.PS_SERIES == 'all' ? SERIES.keySet() as List : [params.PS_SERIES]
                    String stamp = ''
                    node(ARCH_LABELS['amd64']) {
                        stamp = sh(returnStdout: true, script: 'date -u +%Y%m%d').trim() + "-b${env.BUILD_NUMBER}"
                    }
                    List entries = []
                    for (String series : seriesList) {
                        String commit = ''
                        node(ARCH_LABELS['amd64']) {
                            commit = sh(returnStdout: true,
                                script: "git ls-remote ${BUILDER_REPO}.git refs/heads/${SERIES[series].branch} | cut -f1").trim()
                        }
                        if (!(commit ==~ /[0-9a-f]{40}/)) {
                            error("Cannot resolve ${SERIES[series].branch} in ${BUILDER_REPO}: '${commit}'")
                        }
                        echo "PS ${series}: builder from ${BUILDER_REPO} at ${commit}"
                        Map builds = [:]
                        BASE_IMAGES.keySet().each { String os ->
                            archesFor(os).each { String arch ->
                                builds["${series} ${os} ${arch}"] = { buildOne(series, commit, os, arch, imageTag(series, os, stamp)) }
                            }
                        }
                        parallel builds
                        BASE_IMAGES.keySet().each { String os ->
                            String tag = imageTag(series, os, stamp)
                            entries << "    // ${series}/${os}: built and checked ${tag} (${archesFor(os).join(', ')}), not pushed"
                        }
                    }
                    String block = "@Field final Map PREPARED_IMAGES = [\n${entries.join('\n')}\n]\n"
                    writeFile file: 'PREPARED_IMAGES.groovy.txt', text: block
                    archiveArtifacts artifacts: 'PREPARED_IMAGES.groovy.txt'
                    echo "Review, then replace PREPARED_IMAGES in the percona-server-for-mysql-* pipelines with:\n${block}"
                }
            }
        }
    }
}
