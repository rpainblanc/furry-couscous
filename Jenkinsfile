pipeline {
    agent {
        label 'docker-builder-low && dku30-low'
    }
    stages {
        stage('init') {
            steps {
                script {
                    sh 'printenv | sort'
                    sh 'pwd && ls -al'
                }
            }
        }
        stage('pull') {
            steps {
                script {
                    def docker_builder_nodes = nodesByLabel 'docker-builder-low && internal-node'
                    for (docker_builder_node in docker_builder_nodes) {
                        if (docker_builder_node == env.NODE_NAME) {
                            println("Node ${docker_builder_node} is current node, nothing to do here")
                        } else {
                            node(docker_builder_node) {
                                sh 'printenv | sort'
                                sh 'pwd && ls -al'
                            }
                        }
                    }
                }
            }
        }
    }
}

