node('built-in') {
    def commit_id = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
    currentBuild.description = "Git hash: ${commit_id}"
    dir('out') {
        deleteDir()
    }
    sh 'printenv > out/printenv.txt | sort'
    sleep 5
    archiveArtifacts artifacts: 'out/**', allowEmptyArchive: true
}
