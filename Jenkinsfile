node('built-in') {
    deleteDir()
    sh 'printenv > printenv.txt | sort'
    sleep 5
    archiveArtifacts artifacts: '*.txt,*.json', allowEmptyArchive: true
}
