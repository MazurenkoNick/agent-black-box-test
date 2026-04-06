/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.msa;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.extensions.cpsuite.ClasspathSuite;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.thingsboard.server.msa.utils.CleanLogConsumer;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;

import static org.thingsboard.server.msa.AbstractContainerTest.AGENT_CONFIGURATION;
import static org.thingsboard.server.msa.AbstractContainerTest.TEST_CONFIGURATION;
import static org.thingsboard.server.msa.config.TestConfiguration.DIND_SERVICE_NAME;
import static org.thingsboard.server.msa.config.TestConfiguration.DOCKER_SOURCE_DIR;
import static org.thingsboard.server.msa.config.TestConfiguration.TB_AGENT_SERVICE_NAME;
import static org.thingsboard.server.msa.config.TestConfiguration.TB_MONOLITH_SERVICE_NAME;

@RunWith(ClasspathSuite.class)
@ClasspathSuite.ClassnameFilters({"org.thingsboard.server.msa.agent.AutoInstallTest"})
@Slf4j
public class ContainerTestSuite {

    private static final Logger THINGSBOARD_LOG = LoggerFactory.getLogger("THINGSBOARD_LOG");
    private static final Logger TB_AGENT_LOG = LoggerFactory.getLogger("TB_AGENT_LOG");
    private static final HashMap<String, String> env = new HashMap<>();

    public static DockerComposeContainer<?> testContainer;

    @ClassRule
    public static ThingsBoardDbInstaller installTb = new ThingsBoardDbInstaller();

    @ClassRule
    public static DockerComposeContainer<?> initializeTestContainer() {
        env.put("AGENT_ROUTING_KEY", AGENT_CONFIGURATION.getRoutingKey());
        env.put("AGENT_ROUTING_SECRET", AGENT_CONFIGURATION.getRoutingSecret());

        if (testContainer == null) {
            try {
                final String targetDir = FileUtils.getTempDirectoryPath() + "/" + "ContainerTestSuite-" + UUID.randomUUID() + "/";
                log.info("Target directory: {}", targetDir);
                FileUtils.copyDirectory(new File(DOCKER_SOURCE_DIR), new File(targetDir));

                final String testResourcesDir = "src/test/resources";
                FileUtils.copyDirectory(new File(testResourcesDir), new File(targetDir));

                class DockerComposeContainerImpl<SELF extends DockerComposeContainer<SELF>> extends DockerComposeContainer<SELF> {
                    public DockerComposeContainerImpl(java.util.List<File> composeFiles) {
                        super(composeFiles);
                    }

                    @Override
                    public void stop() {
                        super.stop();
                        tryDeleteDir(targetDir);
                    }
                }

                testContainer = new DockerComposeContainerImpl<>(TEST_CONFIGURATION.getComposeFiles())
                        .withPull(false)
                        .withLocalCompose(true)
                        .withOptions("--compatibility")
                        .withTailChildContainers(false)
                        .withEnv(installTb.getEnv())
                        .withEnv(env);

                exposeServiceWithLogging(TB_MONOLITH_SERVICE_NAME, 8080, THINGSBOARD_LOG);
                exposeServiceWithLogging(TB_AGENT_SERVICE_NAME, null, TB_AGENT_LOG);
                exposeService(DIND_SERVICE_NAME, 2375);

            } catch (Exception e) {
                log.error("Failed to create test container", e);
                Assert.fail("Failed to create test container");
            }
        }
        return testContainer;
    }

    private static void exposeServiceWithLogging(String serviceName, Integer port, Logger logger) {
        if (port != null) {
            WaitStrategy waitStrategy = Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(10));
            testContainer.withExposedService(serviceName, port, waitStrategy)
                    .withLogConsumer(serviceName, new CleanLogConsumer(logger, serviceName));
        } else {
            testContainer.withLogConsumer(serviceName, new CleanLogConsumer(logger, serviceName));
        }
    }

    private static void exposeService(String serviceName, int port) {
        WaitStrategy waitStrategy = Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(10));
        testContainer.withExposedService(serviceName, port, waitStrategy);
    }

    private static void tryDeleteDir(String targetDir) {
        try {
            log.info("Trying to delete temp dir: {}", targetDir);
            FileUtils.deleteDirectory(new File(targetDir));
        } catch (IOException e) {
            log.error("Can't delete temp directory: {}", targetDir, e);
        }
    }
}
