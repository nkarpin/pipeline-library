package com.mirantis.mk

/**
 *
 * Tests providing functions
 *
 */

/**
 * Run e2e conformance tests
 *
 * @param k8s_api    Kubernetes api address
 * @param image      Docker image with tests
 * @param timeout    Timeout waiting for e2e conformance tests
 */
def runConformanceTests(master, k8s_api, image, timeout=2400) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
    salt.cmdRun(master, 'ctl01*', "docker rm -f ${containerName}", false)
    salt.cmdRun(master, 'ctl01*', "docker run -d --name ${containerName} --net=host -e API_SERVER=${k8s_api} ${image}")
    sleep(10)

    print("Waiting for tests to run...")
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker wait ${containerName}"], null, false, timeout)

    print("Writing test results to output file...")
    salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', ["docker logs -t ${containerName} &> ${outfile}"])

    print("Conformance test output saved in " + outfile)
}

/**
 * Copy test output to cfg node
 *
 * @param image      Docker image with tests
 */
def copyTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'cfg01*', 'cmd.run', ["scp ctl01:/root/${image}.output /home/ubuntu/"])
}

/**
 * Prepare environment for tempest tests
 *
 * @param cirrosImageLink   Qcow2 image link with embedded linux (cirros)
 * @param target            Host to run tests
 */
def prepareTempestEnv(master, target, cirrosImageLink) {
    // Get test_vm_image for tempest
    cirros_image_name = cirrosImageLink.substring(cirrosImageLink.lastIndexOf('/') + 1);
    salt.cmdRun(master, "${target}", "wget ${cirrosImageLink} -O /tmp/${cirros_image_name}")
    // Upload image to glance
    // TODO: use native salt module when it supports glance v2 api
    salt.cmdRun(master, "${target}", ". /root/keystonercv3; glance image-create " +
                                     "--name cirros --visibility public " +
                                     "--disk-format qcow2 --container-format bare " +
                                     "--file /tmp/${cirros_image_name} --progress")
    // Setup floating network and subnet
    salt.runSaltProcessStep(master, "${target}", 'neutronng.create_network', ["name=tempest_floting_network",
                                                                                  "profile=admin_identity",
                                                                                  "provider_network_type=flat",
                                                                                  "provider_physical_network=physnet1",
                                                                                  "router_external=True",
                                                                                  "shared=True"],
                                                                                  null, true)
    salt.cmdRun(master, "${target}", ". /root/keystonercv3; neutron subnet-create --gateway 10.16.0.1 tempest_floating_network 10.16.0.0/24")

    salt.cmdRun(master, "${target}", "ifconfig br-floating 10.16.0.1/24")

    salt.cmdRun(master, "${target}", ". /root/keystonercv3; nova flavor-create m1.extra_tiny auto 256 0 1")
}

/**
 * Execute tempest tests
 *
 * @param dockerImageLink   Docker image link with rally and tempest
 * @param target            Host to run tests
 * @param pattern            If not false, will run only tests matched the pattern
 */
def runTempestTests(master, dockerImageLink, target, pattern = "false") {
    def salt = new com.mirantis.mk.Salt()
    if (pattern == "false") {
        salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                         "-e TEMPEST_CONF=mcp.conf " +
                                         "-e SKIP_LIST=mcp_skip.list " +
                                         "-e SOURCE_FILE=keystonercv3 " +
                                         "-v /root/:/home/rally ${dockerImageLink} >> docker-tempest.log")
    }
    else {
        salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                         "-e TEMPEST_CONF=mcp.conf " +
                                         "-e SKIP_LIST=mcp_skip.list " +
                                         "-e SOURCE_FILE=keystonercv3 " +
                                         "-e CUSTOM='--pattern ${pattern}' " +
                                         "-v /root/:/home/rally ${dockerImageLink} >> docker-tempest.log")
    }
}

/**
 * Upload results to worker
 *
 */
def copyTempestResults(master, target) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, "${target}", 'cmd.run', ["scp /root/docker-tempest.log cfg01:/home/ubuntu/ && " +
                                                             "find /root -name result.xml -exec scp {} cfg01:/home/ubuntu \\;"])
}

/**
 * Get test results and archive them
 *
 * @param testResultsPath   Path to test results file
 * @param artifactsDir      Directory to archive artifacts from
 * @param outputFile        File to write results to
 * @param target            Host to gather test results from
 */
def getTestResults(master, target, testResults, artifactsDir, outputFile) {
    // collect output
    file_content = salt.getFileContent(master, "${target}", "${testResultsPath}")
    sh "mkdir -p ${artifacts_dir}"
    writeFile file: "${artifacts_dir}/${outputFile}", text: file_content
    // collect artifacts
    archiveArtifacts allowEmptyArchive: true, artifacts: '${artifacts_dir}/${outputFile}', excludes: null
}



/** Store tests results on host
 *
 * @param image      Docker image name
 */
def catTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'cfg01*', 'cmd.run', ["cat /home/ubuntu/${image}.output"])
}


/** Install docker if needed
 *
 * @param target              Target node to install docker pkg
 */
def install_docker(master, target) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, "${target}", 'pkg.install', ["docker.io"])
}
