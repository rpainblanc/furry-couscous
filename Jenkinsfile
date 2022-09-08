node('built-in') {
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    sleep 20
    archiveArtifacts artifacts: '*.txt', allowEmptyArchive: true
}
