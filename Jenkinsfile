// The base name for DKU QA Docker images
def dku_qa_docker_base_name = 'dku-qa-docker-images'

// The name of the AWS registry where the Docker images are pushed
def dku_qa_aws_ecr_registry = '236706865914.dkr.ecr.eu-west-1.amazonaws.com'
 
// The list of Docker images, in the format: ["name": "<image name>", "tags": ["latest", "38"]]
def docker_images = []

pipeline {
    agent {
        label 'docker-builder-low && dku30-low'
    }
    environment {
        DKU_QA_DOCKER_PREFIX = "${dku_qa_docker_base_name}"
        DKU_QA_AWS_ECR_REGISTRY = "${dku_qa_aws_ecr_registry}"
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
                                "name": env.DKU_QA_DOCKER_IMAGE,
                                "tags": ["latest", env.BUILD_NUMBER]]
                        docker_images.add(new_image)
                    }

                    def image_and_tag_lines = []
                    def image_lines = []
                    for (image in docker_images) {
                        image_lines.add(image.name)
                        for (tag in image.tags) {
                            image_and_tag_lines.add("${image.name}:${tag}")
                        }
                    }
                    writeFile file: 'docker-images-and-tags.csv', text: image_and_tag_lines.join('\n')
                    writeFile file: 'docker-images.csv', text: image_lines.join('\n')
                    stash includes: 'docker-images*.csv', name: 'docker-images', allowEmpty: true

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
                                    unstash 'docker-images'
    
                                    sh 'printenv | sort'
                                    sh 'printenv > content.txt'
                                    sh 'pwd && ls -al'
                                    if (env.PULL_IMAGES == 'true') {
                                        println("PULLING IMAGES")
                                        sh '''
                                            for image in $(cat 'docker-images-and-tags.csv'); do
                                                echo docker pull ${DKU_QA_AWS_ECR_REGISTRY}/${DKU_QA_DOCKER_PREFIX}/${image}
                                            done
                                            for image in $(cat 'docker-images.csv'); do
                                                echo aws ecr describe-images --region eu-west-1 --output json --repository-name ${DKU_QA_AWS_ECR_REGISTRY}/${DKU_QA_DOCKER_PREFIX}/${image} > aws-ecr-registry-${image}.json
                                            done
                                        '''
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

