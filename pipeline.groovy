pipeline {
    agent any
    tools {
        maven 'maven_3_9'
    }
    
    parameters {
        string(name: 'GIT_URL', defaultValue: 'https://github.com/Siriphun/order-spring.git', description: 'Git repository URL')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to build')
        string(name: 'SONAR_PROJECT_KEY', defaultValue: 'order', description: 'SonarQube project key')
        booleanParam(name: 'SKIP_TESTS', defaultValue: true, description: 'Skip Maven tests stage?')
    }
    
    environment {
        // Define variables for Docker paths and timeouts
        DOCKER_HOME = '/usr/local/bin/docker'
        DOCKER_CLIENT_TIMEOUT = '1000'
        COMPOSE_HTTP_TIMEOUT = '1000'
        KUBECTL_HOME = '/opt/homebrew/bin/kubectl'
        BUILD_DATE = new Date().format('yyyy-MM-dd')
        IMAGE_TAG = "${BUILD_DATE}-${BUILD_NUMBER}"
        IMAGE_NAME = 'orders'
        DOCKER_USERNAME = 'palmsiriphun'
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
        stage('Get Minikube Server URL') {
            steps {
                script {
                    // Run the command to get Minikube server URL using kubectl
                    def serverUrl = sh(script: "${KUBECTL_HOME} config view --minify -o jsonpath='{.clusters[0].cluster.server}'", returnStdout: true).trim()
                    // Print the server URL for debugging
                    echo "Minikube Kubernetes API Server URL: ${serverUrl}"

                    // Set the server URL for use in the pipeline
                    env.KUBE_SERVER_URL = serverUrl
                }
            }
        }
        stage('Build Maven') {
            steps {
                checkout([$class: 'GitSCM', credentialsId: 'githubpwd', branches: [[name: "*/${params.GIT_BRANCH}"]], extensions: [], userRemoteConfigs: [[url: "${params.GIT_URL}"]]])
                
                sh 'mvn clean install'
                sh 'ls -ahl target'
            }
        }
        stage('Run Maven test') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                sh 'mvn test'
            }
        }
        stage('Run SonarQube') {
            environment {
                scannerHome = tool 'sonar_tool'
                SONAR_JAVA_BINARIES = 'target/classes'
            }
            steps {
                withSonarQubeEnv(credentialsId: 'sonarpwd', installationName: 'sonar_server') {
                    sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${params.SONAR_PROJECT_KEY} -Dsonar.java.binaries=${env.SONAR_JAVA_BINARIES}"
                }
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
                    sh 'ls -lah target'
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
                            sh "${DOCKER_HOME} rmi -f ${danglingImages}"
                        } else {
                            echo 'No dangling images to remove.'
                        }
                    }
                }
            }
        }
        stage('Trigger DevSecOps Pipeline') {
            steps {
                build job: 'DevSecOps-Pipeline', 
                parameters: [
                    string(name: 'GIT_URL', value: "${params.GIT_URL}", description: 'Git repository URL'),
                    string(name: 'GIT_BRANCH', value: "${params.GIT_BRANCH}", description: 'Git branch to build'),
                    string(name: 'SONAR_PROJECT_KEY', value: "${params.SONAR_PROJECT_KEY}", description: 'SonarQube project key'),
                    string(name: 'DOCKER_IMAGE', value: "${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}", description: 'Docker image with tag') // Pass the Docker image and tag
                ],
                wait: false
            }
        }
        stage('Deploy to k8s') {
            steps {
                withKubeConfig([credentialsId: 'kubectlpwd', serverUrl: "${env.KUBE_SERVER_URL}"]) {
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