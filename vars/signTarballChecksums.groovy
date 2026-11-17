def call(String keyFingerprint = '4D1BB29D63D98E422B2113B19334A25F8507EFA5') {
    node('master') {
        unstash 'uploadPath'
        withCredentials([
            sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER'),
            file(credentialsId: 'REPO_CI_KNOWN_HOSTS', variable: 'KNOWN_HOSTS'),
        ]) {
            withEnv(["SIGN_KEY_FPR=${keyFingerprint}"]) {
                sh '''
                    path_to_build=$(cat uploadPath)
                    ssh -o StrictHostKeyChecking=yes -o UserKnownHostsFile="${KNOWN_HOSTS}" -i "${KEY_PATH}" \
                        "${USER}@repo.ci.percona.com" bash -s -- "${path_to_build}" "${SIGN_KEY_FPR}" <<'REMOTE'
set -e
if ! gpg-connect-agent 'keyinfo --list' /bye | awk '$1 == "S" && $7 == "1" { found = 1 } END { exit !found }'; then
    echo "ERROR: no signing key passphrase is cached in gpg-agent on $(hostname)" >&2
    exit 1
fi
cd "$1/binary/tarball"
for sum in *.sha256; do
    sha256sum -c "${sum}"
    gpg --batch --yes --local-user "$2" --armor --detach-sign --output "${sum}.asc" "${sum}"
    gpg --batch --verify "${sum}.asc" "${sum}"
done
REMOTE
                '''
            }
        }
    }
}
