pipeline {
    agent {
        label 'built-in'
    }
    stages {
        stage('init') {
            steps {
                script {
                    sh 'printenv | sort'
                }
            }
        }
    }
}
