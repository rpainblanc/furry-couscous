 
// The list of Docker images, in the format: ["name": "<image name>", "tags": ["latest", "38"]]
def docker_images = []

pipeline {
    agent {
        label 'docker-builder-low && dku30-low'
    }
    parameters {
        booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build the Docker images')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: false, description: 'Push the Docker images into ECR')
        booleanParam(name: 'PULL_IMAGES', defaultValue: false, description: 'Pull the Docker images from ECR')
    }
    stages {
        stage('init') {
            steps {
                script {
                    sh 'git reset --hard && git clean -xfdf'
                    sh 'printenv | sort'
                    sh 'printenv > content.txt'
                    sh 'pwd && ls -al'
                    if (env.BUILD_IMAGES == 'true') {
                        println("BUILDING IMAGES")
                    }
                    if (params.BUILD_IMAGES == 'true' && params.PUSH_IMAGES == 'true') {
                        println("PUSHING IMAGES")
                    }
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
                                deleteDir()
                                sh 'printenv | sort'
                                sh 'printenv > content.txt'
                                sh 'pwd && ls -al'
                                if (env.PULL_IMAGES == 'true') {
                                    println("PULLING IMAGES")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

