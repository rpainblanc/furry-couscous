node('built-in') {
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    sleep 10
    archiveArtifacts artifacts: '*.txt', allowEmptyArchive: true
}
