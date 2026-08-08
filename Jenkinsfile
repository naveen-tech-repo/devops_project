/*
 * E-commerce microservices CI/CD pipeline.
 *
 * Flow: checkout -> resolve environment from branch -> unit tests -> maven build
 *       -> docker build -> push to Docker Hub -> (manual approval for production)
 *       -> deploy to the matching Kubernetes namespace.
 *
 * BRANCH -> ENVIRONMENT -> NAMESPACE mapping (used when ENVIRONMENT=auto):
 *
 *   develop            -> dev         -> namespace dev         (1 replica)
 *   release/*          -> staging     -> namespace staging     (2 replicas)
 *   main | master      -> production  -> namespace production  (3 replicas, manual approval)
 *   feature/* | PR-*   -> none        -> build + test only, nothing is pushed or deployed
 *
 * Set the ENVIRONMENT parameter to something other than 'auto' to override the mapping
 * (e.g. deploy a release branch to dev for a one-off investigation).
 *
 * Jenkins prerequisites:
 *   - Credentials 'dockerhub-credentials'  (Username with password) — Docker Hub login
 *   - Credentials 'kubeconfig'             (Secret file)            — kubeconfig for the target cluster
 *   - Agent tools: git, docker, kubectl, kustomize
 *     (Maven and the JDK are not needed on the agent — they run inside a Maven container.)
 */

pipeline {

    agent any

    parameters {
        choice(
            name: 'ENVIRONMENT',
            choices: ['auto', 'dev', 'staging', 'production'],
            description: 'auto = derive from the branch name (see mapping at the top of the Jenkinsfile). Anything else forces that environment.'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Optional explicit image tag. Leave blank to use <env>-<build>-<git-sha>.'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip the unit test stage (not allowed for production).'
        )
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        // ---- change this to your Docker Hub account ----
        DOCKERHUB_USERNAME = 'CHANGE_ME'
        REGISTRY           = 'docker.io'

        MAVEN_IMAGE = 'maven:3.9-eclipse-temurin-17'
        MAVEN_CACHE = 'jenkins-maven-cache'
        // Java services that go through Maven (test + package).
        JAVA_SERVICES = 'product-service,order-service,user-service'
        // Everything that gets a Docker image + deploy. frontend is static (no Maven).
        SERVICES      = 'product-service,order-service,user-service,frontend'
    }

    stages {

        stage('Checkout & Resolve Environment') {
            steps {
                checkout scm
                script {
                    env.GIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()

                    // BRANCH_NAME is set automatically by a Multibranch Pipeline job.
                    // Fall back to the actual git branch for plain Pipeline jobs.
                    def branch = env.BRANCH_NAME ?:
                        sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    env.SOURCE_BRANCH = branch

                    // ---- branch -> environment mapping ----
                    def derived
                    if (branch == 'main' || branch == 'master') {
                        derived = 'production'
                    } else if (branch == 'develop' || branch == 'dev') {
                        derived = 'dev'
                    } else if (branch.startsWith('release/')) {
                        derived = 'staging'
                    } else {
                        derived = 'none'   // feature/*, bugfix/*, PR-*: CI only
                    }

                    env.TARGET_ENV = (params.ENVIRONMENT == 'auto') ? derived : params.ENVIRONMENT
                    env.DEPLOY_ENABLED = (env.TARGET_ENV != 'none').toString()

                    if (env.DEPLOY_ENABLED == 'true') {
                        env.NAMESPACE   = env.TARGET_ENV
                        env.OVERLAY_DIR = "k8s/overlays/${env.TARGET_ENV}"
                    }

                    def tagPrefix = (env.TARGET_ENV == 'none') ? 'ci' : env.TARGET_ENV
                    env.RESOLVED_TAG = params.IMAGE_TAG?.trim() ?
                        params.IMAGE_TAG.trim() :
                        "${tagPrefix}-${env.BUILD_NUMBER}-${env.GIT_SHA}"

                    if (env.TARGET_ENV == 'production' && params.SKIP_TESTS) {
                        error('SKIP_TESTS is not permitted for production deployments.')
                    }

                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${branch} -> ${env.TARGET_ENV}"
                }
                echo """
                    Branch      : ${env.SOURCE_BRANCH}
                    Environment : ${env.TARGET_ENV}${params.ENVIRONMENT == 'auto' ? ' (derived from branch)' : ' (forced by parameter)'}
                    Namespace   : ${env.DEPLOY_ENABLED == 'true' ? env.NAMESPACE : '— no deployment —'}
                    Image tag   : ${env.RESOLVED_TAG}
                """.stripIndent()
            }
        }

        stage('Unit Tests') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    // Each Java service is an independent Maven project — test in parallel.
                    // The frontend is static (nginx) and has no Maven build.
                    def branches = [:]
                    env.JAVA_SERVICES.split(',').each { svc ->
                        branches[svc] = {
                            mvnRun(svc, 'test')
                        }
                    }
                    parallel branches
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '*/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Build (Maven)') {
            steps {
                script {
                    env.JAVA_SERVICES.split(',').each { svc ->
                        mvnRun(svc, 'clean package -DskipTests')
                    }
                }
                archiveArtifacts artifacts: '*/target/app.jar', fingerprint: true
            }
        }

        stage('Build Docker Images') {
            steps {
                script {
                    env.SERVICES.split(',').each { svc ->
                        def image = "${REGISTRY}/${DOCKERHUB_USERNAME}/${svc}"
                        sh """
                            docker build \
                              --build-arg BUILD_TAG=${env.RESOLVED_TAG} \
                              -t ${image}:${env.RESOLVED_TAG} \
                              ./${svc}
                        """
                        // Only deployable branches get the moving <env>-latest tag.
                        if (env.DEPLOY_ENABLED == 'true') {
                            sh "docker tag ${image}:${env.RESOLVED_TAG} ${image}:${env.TARGET_ENV}-latest"
                        }
                    }
                }
            }
        }

        stage('Push to Docker Hub') {
            when {
                expression { env.DEPLOY_ENABLED == 'true' }
            }
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'dockerhub-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS')]) {
                    sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'
                }
                script {
                    env.SERVICES.split(',').each { svc ->
                        def image = "${REGISTRY}/${DOCKERHUB_USERNAME}/${svc}"
                        sh "docker push ${image}:${env.RESOLVED_TAG}"
                        sh "docker push ${image}:${env.TARGET_ENV}-latest"
                    }
                }
            }
        }

        stage('Approval') {
            when {
                expression { env.TARGET_ENV == 'production' }
            }
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input(
                        message: "Deploy ${env.RESOLVED_TAG} from '${env.SOURCE_BRANCH}' to PRODUCTION (namespace: production, 3 replicas)?",
                        ok: 'Deploy to production'
                    )
                }
            }
        }

        stage('Deploy to Kubernetes') {
            when {
                expression { env.DEPLOY_ENABLED == 'true' }
            }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                    // Point the overlay at the images this build just pushed.
                    dir(env.OVERLAY_DIR) {
                        script {
                            env.SERVICES.split(',').each { svc ->
                                sh "kustomize edit set image ecommerce/${svc}=${REGISTRY}/${DOCKERHUB_USERNAME}/${svc}:${env.RESOLVED_TAG}"
                            }
                        }
                        sh 'cat kustomization.yaml'
                    }

                    sh "kustomize build ${env.OVERLAY_DIR} > rendered-${env.TARGET_ENV}.yaml"
                    sh "kubectl apply -f rendered-${env.TARGET_ENV}.yaml"

                    // Wait for every workload to become healthy; a failed rollout fails the build.
                    sh "kubectl -n ${env.NAMESPACE} rollout status statefulset/mongodb --timeout=300s"
                    script {
                        env.SERVICES.split(',').each { svc ->
                            sh "kubectl -n ${env.NAMESPACE} rollout status deployment/${svc} --timeout=300s"
                        }
                    }
                }
            }
        }

        stage('Smoke Test') {
            when {
                expression { env.DEPLOY_ENABLED == 'true' }
            }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                    script {
                        // svc -> [servicePort, healthPath]. The frontend serves /healthz
                        // on 80; the Java services expose the actuator readiness probe.
                        def endpoints = [
                            'product-service': [8081, '/actuator/health/readiness'],
                            'order-service'  : [8082, '/actuator/health/readiness'],
                            'user-service'   : [8083, '/actuator/health/readiness'],
                            'frontend'       : [80,   '/healthz'],
                        ]
                        env.SERVICES.split(',').each { svc ->
                            def (port, path) = endpoints[svc]
                            sh """
                                kubectl -n ${env.NAMESPACE} run smoke-${svc}-${env.BUILD_NUMBER} \
                                  --rm -i --restart=Never --image=curlimages/curl:8.8.0 --quiet -- \
                                  curl -fsS http://${svc}:${port}${path}
                            """
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'docker logout || true'
            sh 'rm -f rendered-*.yaml || true'
            // Undo the local kustomization edit so the workspace/repo stays clean.
            script {
                if (env.OVERLAY_DIR) {
                    sh "git checkout -- ${env.OVERLAY_DIR}/kustomization.yaml || true"
                }
            }
        }
        success {
            script {
                if (env.DEPLOY_ENABLED == 'true') {
                    echo "Deployed ${env.RESOLVED_TAG} from '${env.SOURCE_BRANCH}' to namespace '${env.NAMESPACE}'."
                } else {
                    echo "CI passed for '${env.SOURCE_BRANCH}'. No deployment (not a mapped branch)."
                }
            }
        }
        failure {
            echo "Build failed for branch '${env.SOURCE_BRANCH}' (target: ${env.TARGET_ENV})."
        }
    }
}

/**
 * Runs Maven inside a container so the Jenkins agent only needs Docker.
 * A named volume keeps the ~/.m2 cache warm between builds.
 */
def mvnRun(String serviceDir, String goals) {
    sh """
        docker run --rm \
          -v "\$(pwd)/${serviceDir}":/workspace \
          -v ${env.MAVEN_CACHE}:/root/.m2 \
          -w /workspace \
          ${env.MAVEN_IMAGE} mvn -B ${goals}
    """
}
