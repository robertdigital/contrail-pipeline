#!groovy
/**
 *
 * contrail build, test, promote pipeline
 *
 * Expected parameters:
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def dockerLib = new com.mirantis.mk.Docker()

def server = Artifactory.server('mcp-ci')
def artTools = new com.mirantis.mcp.MCPArtifactory()
def artifactoryUrl = server.getUrl()
def dockerDevRepo = 'docker-test-local'
def dockerDevRegistry = "${dockerDevRepo}.docker.mirantis.net"

imageNameSpace = "tungsten"
publishRetryAttempts = 10

//node('docker') {
node('jsl07.mcp.mirantis.net') {
    try{

        def timestamp = new Date().format("yyyyMMddHHmmss", TimeZone.getTimeZone('UTC'))
        println ("timestamp ${timestamp}")

        withEnv([
            "timestamp=${timestamp}",
            "AUTOBUILD=${AUTOBUILD}",
            "EXTERNAL_REPOS=${WORKSPACE}/src",
            "SRC_ROOT=${WORKSPACE}/contrail",
            "CANONICAL_HOSTNAME=${CANONICAL_HOSTNAME}",
            "IMAGE=${IMAGE}",
            "DEVENVTAG=${DEVENVTAG}",
            "SRCVER=5.1.${timestamp}",
        ]) {

            stage("prepare") {
              currentBuild.description = "${SRCVER}"
              sh '''
                  sudo rm -rf *
                  #TODO parametrize tf-dev-env and add checkout from change request
                  git clone https://github.com/mrasskazov/tf-dev-env.git -b mcp/R5.1
                  cd tf-dev-env
                  echo "Using tf-dev-env version"
                  git log --decorate -n1
                  ./build.sh
              '''
            }

            stage("sync") {
                // TODO: checkout contrail-vnc before sync and checkout
                //  if [ "${GERRIT_PROJECT##*/}" = "contrail-vnc" ]; then
                //       checkout contrail-vnc
                //  fi
                //#cd $HOME
                //#rm -rf .repo
                //#repo init --no-clone-bundle -q -u https://github.com/mrasskazov/contrail-vnc -b $BRANCH                                         | 22     chmod a+x /usr/bin/repo && \

              sh '''
                docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory sync
              '''
            }

            stage("checkout") {
              sh '''
                  if [ -n "${GERRIT_PROJECT}" ]; then
                      # TODO excluding tf-dev-env, contrail-vnc, contrail-packages, contrail-container-builder
                      docker exec tf-developer-sandbox ./tf-dev-env/checkout.sh ${GERRIT_PROJECT##*/} ${GERRIT_CHANGE_NUMBER}/${GERRIT_PATCHSET_NUMBER}
                      if echo ${GERRIT_CHANGE_COMMIT_MESSAGE} | base64 -d | egrep -i '^depends-on:'; then
                          DEP_URL_LIST=$(echo ${GERRIT_CHANGE_COMMIT_MESSAGE} | base64 -d | egrep -i '^depends-on:' | sed -r 's|/+$||g' | egrep -o '[^ ]+$')
                          for DEP_URL in ${DEP_URL_LIST}; do
                              DEP_PROJECT_URL="${DEP_URL%/+/*}"
                              DEP_PROJECT="${DEP_PROJECT_URL##*/}"
                              DEP_CHANGE_ID="${DEP_URL##*/}"
                              docker exec tf-developer-sandbox ./tf-dev-env/checkout.sh ${DEP_PROJECT} ${DEP_CHANGE_ID}
                          done
                      else
                          echo "There are no depends-on"
                      fi
                  else
                      echo "Skipping checkout because GERRIT_PROJECT does not specified"
                  fi
              '''
            }

            stage("fetch packages") {
              // TODO: downstream third parties
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory fetch_packages'
            }

            stage("setup") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory setup'
            }

            stage("dep") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory dep'
            }

            stage("info") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory info'
            }

            stage("rpm") {
              sh '''
                  #TODO: implement versioning
                  # Following Environment variables can be used for controlling Makefile behavior:
                  # DEBUGINFO = TRUE/FALSE - build debuginfo packages (default: TRUE)
                  # TOPDIR - control where packages will be built (default: SB_TOP)
                  # SCONSOPT = debug/production - select optimization level for scons (default: production)
                  # SRCVER - specify source code version (default from controller/src/base/version.info)
                  # KVERS - kernel version to build against (default: installed version of kernel-devel)
                  # BUILDTAG - additional tag for versioning (default: date +%m%d%Y%H%M)
                  # SKUTAG - OpenStack SKU (default: ocata)

                  #export VERSION="5.1.${timestamp}"
                  #//  def version = SOURCE_BRANCH.replace('R', '') + "~${timestamp}"
                  #//  def python_version = 'python'
                  #//  if (SOURCE_BRANCH == "master")
                  #//      version = "666~${timestamp}"
                  #export BUILDTAG=
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory rpm
                  #TODO: parse errors and archive logs. possible with junit
              '''
            }

            List listContainers
            List listDeployers

            stage("containers") {
                // TODO: update tf-dev-env/scripts/prepare-containers and prepare-deployers for
                // checkout to change request if needed
                // TODO: implement versioning
              sh '''
                  echo "INFO: make create-repo prepare-containers prepare-deployers   $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory -j 3 create-repo prepare-containers prepare-deployers 
              '''
              listContainers = sh(script: "docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory list-containers", returnStdout: true).trim().tokenize()
              listDeployers = sh(script: "docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory list-deployers", returnStdout: true).trim().tokenize()

              sh '''
                  echo "INFO: make container-general-base $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory container-general-base

                  echo "INFO: make containers-only deployers-only   $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory -j 2 containers-only deployers-only || EXIT_CODE=$?
                  #TODO: parse errors and archive logs. possible with junit
              '''
            }


            List brokenList
            List containerLogList
            String containerBuilderDir = "src/${CANONICAL_HOSTNAME}/tungsten/contrail-container-builder"

            stage("Upload images") {
                dir("tf-dev-env") {
                    List imageList = (listContainers + listDeployers).collect {
                        it.replaceAll(/^container/, 'contrail').replaceAll(/^deployer/, 'contrail').replaceAll('_' , '-')
                    }
                    common.infoMsg("imageList = ${imageList}")
                    docker.withRegistry("http://${dockerDevRegistry}/", 'artifactory') {
                        List commonEnv = readFile("common.env").split('\n')
                        common.infoMsg(commonEnv)
                        withEnv(commonEnv) {
                            dir ("../" + containerBuilderDir + "/containers/") {
                                containerLogList = findFiles (glob: "build-*.log")
                            }
                            brokenList = containerLogList.collect {
                                it.getName().replaceFirst(/^build-/, '').replaceAll(/.log$/ , '')
                            }
                            common.infoMsg("brokenList = ${brokenList}")
                            imageList.each {
                                if (! (it in brokenList)) {
                                    localTag = "${CONTRAIL_REGISTRY}/${it}:${CONTRAIL_VERSION}"
                                    pubTag="${dockerDevRegistry}/${imageNameSpace}/${it}:${SRCVER}"
                                    sh "docker tag ${localTag} ${pubTag}"
                                    retry(publishRetryAttempts) {
                                        artTools.uploadImageToArtifactory(
                                            server,
                                            dockerDevRegistry,
                                            "${imageNameSpace}/${it}",
                                            "${SRCVER}",
                                            dockerDevRepo)
                                        sh "docker rmi ${pubTag}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage("Process results") {
                if(!brokenList.isEmpty()) {
                    common.errorMsg("Failed to build some containers:\n${brokenList}\nSee log files at artifacts")
                    archiveArtifacts artifacts: containerBuilderDir + '/containers/build-*.log'
                    currentBuild.result = "FAILURE"
                }
            }

        }

    } catch (Throwable e) {
       // If there was an exception thrown, the build failed
       currentBuild.result = "FAILURE"
       throw e
    } finally {
       //common.sendNotification(currentBuild.result,"",["slack"])
       //sh("rm -rf src buildresult-*")
    }
}
