def call() {
    node('master') {
        unstash 'uploadPath'
        withCredentials([
            sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER'),
            file(credentialsId: 'REPO_CI_KNOWN_HOSTS', variable: 'KNOWN_HOSTS'),
        ]) {
            sh '''
                path_to_build=$(cat uploadPath)
                ssh -o StrictHostKeyChecking=yes -o UserKnownHostsFile="${KNOWN_HOSTS}" -i "${KEY_PATH}" \
                    "${USER}@repo.ci.percona.com" bash -s -- "${path_to_build}" <<'REMOTE'
set -e
if ! gpg-connect-agent 'keyinfo --list' /bye | awk '$1 == "S" && $7 == "1" { found = 1 } END { exit !found }'; then
    echo "ERROR: no signing key passphrase is cached in gpg-agent on $(hostname)" >&2
    exit 1
fi
ls "$1"/binary/redhat/*/*/*.rpm | xargs -n 1 signpackage --verbose --rpm
REMOTE
            '''
        }
    }
}
