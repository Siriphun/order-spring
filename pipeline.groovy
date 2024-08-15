pipeline {
    agent any
    tools {
        maven 'maven_3_9'
    }
    environment {
        // Define variables for Docker paths and timeouts
        DOCKER_HOME = '/usr/local/bin/docker'
        DOCKER_CLIENT_TIMEOUT = '1000'
        COMPOSE_HTTP_TIMEOUT = '1000'
        KUBECTL_HOME = '/opt/homebrew/bin/kubectl'
        BUILD_DATE = new Date().format('yyyy-MM-dd')
        IMAGE_TAG = "${BUILD_DATE}-${BUILD_NUMBER}"
        IMAGE_NAME = 'orders' // Variable for the image name
        DOCKER_USERNAME = 'palmsiriphun' // Variable for Docker Hub username
        K8S_NAMESPACE = 'minikube-local'
    }
    stages {
        stage('Clean Workspace') {
            steps {
                deleteDir() // Clean the workspace before starting
            }
        }
        stage('Set Environment Variables') {
            steps {
                script {
                    sh '''
                        export DOCKER_CLIENT_TIMEOUT=12000
                        export COMPOSE_HTTP_TIMEOUT=12000
                        echo "Docker client timeout: $DOCKER_CLIENT_TIMEOUT"
                        echo "Compose HTTP timeout: $COMPOSE_HTTP_TIMEOUT"
                    '''
                }
            }
        }
        stage('Build Maven') {
            steps {
                checkout([$class: 'GitSCM', credentialsId: 'githubpwd', branches: [[name: '*/main']], extensions: [], userRemoteConfigs: [[url: 'https://github.com/Siriphun/order-spring.git']]])
                sh 'mvn clean install'
            }
        }
        stage('Clean Docker State') {
            steps {
                script {
                    sh '${DOCKER_HOME} system prune -af --volumes' // Clean all Docker resources
                }
            }
        }
        stage('Build Image') {
            steps {
                script {
                    sh '${DOCKER_HOME} pull openjdk:23-rc-jdk-slim'
                    sh '${DOCKER_HOME} network prune --force'
                    sh '${DOCKER_HOME} build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
                }
            }
        }
        stage('Push Image to Hub') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'dockerpwd', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
                        sh '${DOCKER_HOME} login -u ${DOCKER_USERNAME} -p ${DOCKER_PASSWORD}'
                        sh '${DOCKER_HOME} tag ${IMAGE_NAME}:${IMAGE_TAG} ${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}'
                        sh '${DOCKER_HOME} push ${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}'

                        // Check for dangling images and remove them if any are found
                        def danglingImages = sh(script: "${DOCKER_HOME} images -f 'dangling=true' -q", returnStdout: true).trim()
                        if (danglingImages) {
                            // Stop and remove any container using the dangling images
                            sh '''
                                for img in ${danglingImages}; do
                                    container_id=$(${DOCKER_HOME} ps -q --filter ancestor=$img)
                                    if [ ! -z "$container_id" ]; then
                                        ${DOCKER_HOME} stop $container_id
                                        ${DOCKER_HOME} rm $container_id
                                    fi
                                    ${DOCKER_HOME} rmi -f $img
                                done
                            '''
                        } else {
                            echo 'No dangling images to remove.'
                        }
                    }
                }
            }
        }
        stage('Deploy to k8s') {
            steps {
                withKubeConfig([credentialsId: 'kubectlpwd', serverUrl: 'https://127.0.0.1:53582']) {
                    script {
                        // Replace the image tag in the deployment YAML file
                        sh "sed -i '' 's/\$IMAGE_TAG/$IMAGE_TAG/g' k8s/deployment.yaml"
                        sh 'cat k8s/deployment.yaml'
                    }
                    sh '${KUBECTL_HOME} get pods -n ${K8S_NAMESPACE}'
                    sh '${KUBECTL_HOME} apply -f k8s/deployment.yaml -n ${K8S_NAMESPACE}'
                    sh '${KUBECTL_HOME} apply -f k8s/service.yaml -n ${K8S_NAMESPACE}'
                }
            }
        }
    }
    post {
        always {
            // Archive package version for reference
            writeFile file: 'version.txt', text: "${IMAGE_TAG}"
            archiveArtifacts artifacts: 'version.txt'
            buildName("Build #${BUILD_NUMBER} - Version ${env.IMAGE_TAG}")
        }
    }
}
