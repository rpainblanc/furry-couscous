node('built-in') {
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    archiveArtifacts artifacts: '*.txt', allowEmptyArchive: true
}
