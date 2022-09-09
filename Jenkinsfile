node('built-in') {
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    sleep 15
    archiveArtifacts artifacts: '*.txt', allowEmptyArchive: true
}
