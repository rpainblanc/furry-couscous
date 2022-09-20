 
// The list of Docker images, in the format: ["name": "<image name>", "tags": ["latest", "38"]]
def docker_images = []

pipeline {
    agent {
        label 'docker-builder-low && dku30-low'
    }
    environment {
        DKU_QA_DOCKER_PREFIX = dku_qa_docker_base_name
        DKU_QA_AWS_ECR_REGISTRY = dku_qa_aws_ecr_registry
    }
    parameters {
        booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build the Docker images')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: false, description: 'Push the Docker images into ECR')
        booleanParam(name: 'PULL_IMAGES', defaultValue: false, description: 'Pull the Docker images from ECR')
    }
    stages {
        stage('image jenkins-builder') {
            environment {
                DKU_QA_DOCKER_IMAGE = 'new-jenkins-builder'
            }
            steps {
                script {
                    sh 'git reset --hard && git clean -xfdf'
                    sh 'printenv | sort'
                    sh 'printenv > content.txt'
                    sh 'pwd && ls -al'
                    if (env.BUILD_IMAGES == 'true') {
                        println("BUILDING IMAGES")
                        def new_image = [
                                "name": "${env.DKU_QA_AWS_ECR_REGISTRY}/${env.DKU_QA_DOCKER_PREFIX}/${env.DKU_QA_DOCKER_IMAGE}",
                                "tags": ["latest", env.BUILD_NUMBER]]
                        docker_images.add(new_image)
                    }

                    def image_lines = []
                    for (image in docker_images) {
                        for (tag in image.tags) {
                            image_lines.add("${image}:${tag}")
                        }
                    }
                    writeFile file: 'all-docker-images.csv', text: image_lines.join('\n')
                    stash includes: 'all-docker-images.csv', name: 'all-docker-images', allowEmpty: true

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
                                try {
                                    deleteDir()
                                    unstash 'all-docker-images'
    
                                    sh 'printenv | sort'
                                    sh 'printenv > content.txt'
                                    sh 'pwd && ls -al'
                                    if (env.PULL_IMAGES == 'true') {
                                        println("PULLING IMAGES")
                                    }
                                } finally {
                                    archiveArtifacts artifacts: '*.json,*.csv', allowEmptyArchive: true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

