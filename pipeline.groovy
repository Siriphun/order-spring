pipeline {
    agent any
    tools {
        maven 'maven_3_9'
    }
    
    parameters {
        string(name: 'GIT_URL', defaultValue: 'https://github.com/Siriphun/order-spring.git', description: 'Git repository URL')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to build')
        string(name: 'SONAR_PROJECT_KEY', defaultValue: 'orders', description: 'SonarQube project key')
        booleanParam(name: 'SKIP_TESTS', defaultValue: true, description: 'Skip Maven tests stage?')
    }
    
    environment {
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
                    try {
                        def serverUrl = sh(script: "${KUBECTL_HOME} config view --minify -o jsonpath='{.clusters[0].cluster.server}'", returnStdout: true).trim()
                        echo "Minikube Kubernetes API Server URL: ${serverUrl}"
                        env.KUBE_SERVER_URL = serverUrl
                    } catch (Exception e) {
                        error "Failed to get Minikube server URL: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Build Maven') {
            steps {
                script {
                    try {
                        checkout([$class: 'GitSCM', credentialsId: 'githubpwd', branches: [[name: "*/${params.GIT_BRANCH}"]], extensions: [], userRemoteConfigs: [[url: "${params.GIT_URL}"]]])
                        sh 'mvn clean install'
                        sh 'ls -ahl target'
                    } catch (Exception e) {
                        error "Maven build failed: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Run Maven test') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    try {
                        sh 'mvn test'
                    } catch (Exception e) {
                        error "Maven tests failed: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Run SonarQube') {
            environment {
                scannerHome = tool 'sonar_tool'
                SONAR_JAVA_BINARIES = 'target/classes'
            }
            steps {
                script {
                    try {
                        withSonarQubeEnv(credentialsId: 'sonarpwd', installationName: 'sonar_server') {
                            sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${params.SONAR_PROJECT_KEY} -Dsonar.java.binaries=${env.SONAR_JAVA_BINARIES}"
                        }
                    } catch (Exception e) {
                        error "SonarQube analysis failed: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Clean Docker State') {
            steps {
                script {
                    try {
                        sh '${DOCKER_HOME} system prune -af --volumes'
                    } catch (Exception e) {
                        error "Failed to clean Docker state: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Build Image') {
            steps {
                script {
                    try {
                        sh '${DOCKER_HOME} pull openjdk:23-rc-jdk-slim'
                        sh '${DOCKER_HOME} network prune --force'
                        sh 'ls -lah target'
                        sh '${DOCKER_HOME} build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
                    } catch (Exception e) {
                        error "Docker image build failed: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Push Image to Hub') {
            steps {
                script {
                    try {
                        withCredentials([usernamePassword(credentialsId: 'dockerpwd', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
                            sh '${DOCKER_HOME} login -u ${DOCKER_USERNAME} -p ${DOCKER_PASSWORD}'
                            sh '${DOCKER_HOME} tag ${IMAGE_NAME}:${IMAGE_TAG} ${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}'
                            sh '${DOCKER_HOME} push ${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}'

                            def danglingImages = sh(script: "${DOCKER_HOME} images -f 'dangling=true' -q", returnStdout: true).trim()
                            if (danglingImages) {
                                sh "${DOCKER_HOME} rmi -f ${danglingImages}"
                            } else {
                                echo 'No dangling images to remove.'
                            }
                        }
                    } catch (Exception e) {
                        error "Failed to push Docker image: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Trigger DevSecOps Pipeline') {
            steps {
                script {
                    try {
                        build job: 'DevSecOps-Pipeline', 
                        parameters: [
                            string(name: 'GIT_URL', value: "${params.GIT_URL}", description: 'Git repository URL'),
                            string(name: 'GIT_BRANCH', value: "${params.GIT_BRANCH}", description: 'Git branch to build'),
                            string(name: 'SONAR_PROJECT_KEY', value: "${params.SONAR_PROJECT_KEY}", description: 'SonarQube project key'),
                            string(name: 'DOCKER_IMAGE', value: "${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}", description: 'Docker image with tag')
                        ],
                        wait: false
                    } catch (Exception e) {
                        error "Failed to trigger DevSecOps pipeline: ${e.message}" // Added error handling
                    }
                }
            }
        }
        stage('Deploy to k8s') {
            steps {
                withKubeConfig([credentialsId: 'kubectlpwd', serverUrl: "${env.KUBE_SERVER_URL}"]) {
                    script {
                        try {
                            sh "sed -i '' 's/\$IMAGE_TAG/$IMAGE_TAG/g' k8s/deployment.yaml"
                            sh 'cat k8s/deployment.yaml'
                            sh '${KUBECTL_HOME} get pods -n ${K8S_NAMESPACE}'
                            sh '${KUBECTL_HOME} apply -f k8s/deployment.yaml -n ${K8S_NAMESPACE}'
                            sh '${KUBECTL_HOME} apply -f k8s/service.yaml -n ${K8S_NAMESPACE}'
                        } catch (Exception e) {
                            error "Failed to deploy to Kubernetes: ${e.message}" // Added error handling
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            writeFile file: 'version.txt', text: "${IMAGE_TAG}"
            archiveArtifacts artifacts: 'version.txt'
            buildName("Build #${BUILD_NUMBER} - Version ${env.IMAGE_TAG}")
        }
        failure {
            echo "Build failed. Please check the logs for details." // Added failure notification
        }
    }
}
