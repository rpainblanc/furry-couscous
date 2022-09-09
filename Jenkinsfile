node('built-in') {
    def commit_id = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
    currentBuild.description = "Git hash: ${commit_id}"
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    sleep 5
    archiveArtifacts artifacts: '*.txt,*.json', allowEmptyArchive: true
}
