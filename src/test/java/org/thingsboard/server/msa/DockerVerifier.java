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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;

import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.HostConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Connects to the Docker-in-Docker (DinD) daemon to verify containers
 * created by the tb-agent inside DinD.
 * <p>
 * The agent labels containers with standard Docker Compose labels:
 * - com.docker.compose.project  -- project name
 * - com.docker.compose.service  -- service name within the project
 */
@Slf4j
public class DockerVerifier {

    private static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";
    private static final String COMPOSE_SERVICE_LABEL = "com.docker.compose.service";

    private final DockerClient dockerClient;

    public DockerVerifier(String dindHost, int dindPort) {
        String dockerHost = "tcp://" + dindHost + ":" + dindPort;
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withDockerTlsVerify(false)
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(dockerHost))
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
        log.info("DockerVerifier connected to DinD at {}", dockerHost);
    }

    public List<Container> listContainersByProject(String projectName) {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Collections.singletonMap(COMPOSE_PROJECT_LABEL, projectName))
                    .exec();
        } catch (Exception e) {
            log.warn("Failed to list containers for project {}: {}", projectName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public long countRunningContainers(String projectName) {
        return listContainersByProject(projectName).stream()
                .filter(c -> "running".equalsIgnoreCase(c.getState()))
                .count();
    }

    public boolean projectExists(String projectName) {
        return !listContainersByProject(projectName).isEmpty();
    }

    public boolean hasRunningContainerWithImage(String projectName, String imageSubstring) {
        return listContainersByProject(projectName).stream()
                .filter(c -> "running".equalsIgnoreCase(c.getState()))
                .anyMatch(c -> c.getImage() != null && c.getImage().contains(imageSubstring));
    }

    /**
     * Creates and starts a container in DinD with Docker Compose labels,
     * simulating a compose project that the agent should discover via state sync.
     */
    public String createStubComposeProject(String projectName, String serviceName, String image) {
        log.info("Creating compose project '{}' with service '{}' image '{}'", projectName, serviceName, image);
        try {
            dockerClient.pullImageCmd(image)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while pulling image " + image, e);
        }
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(projectName + "-" + serviceName + "-1")
                .withLabels(Map.of(
                        COMPOSE_PROJECT_LABEL, projectName,
                        COMPOSE_SERVICE_LABEL, serviceName
                ))
                .withHostConfig(HostConfig.newHostConfig().withAutoRemove(false))
                .exec();
        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Started container {} for project '{}'", container.getId(), projectName);
        return container.getId();
    }

    public void removeComposeProject(String projectName) {
        List<Container> containers = listContainersByProject(projectName);
        for (Container c : containers) {
            try {
                dockerClient.stopContainerCmd(c.getId()).withTimeout(5).exec();
            } catch (Exception e) {
                log.debug("Stop failed for {}: {}", c.getId(), e.getMessage());
            }
            dockerClient.removeContainerCmd(c.getId()).withForce(true).exec();
        }
        log.info("Removed {} container(s) for project '{}'", containers.size(), projectName);
    }

    public String getContainerState(String projectName, String serviceName) {
        return listContainersByProject(projectName).stream()
                .filter(c -> {
                    String svc = c.getLabels() != null ? c.getLabels().get(COMPOSE_SERVICE_LABEL) : null;
                    return serviceName.equals(svc);
                })
                .map(Container::getState)
                .findFirst()
                .orElse(null);
    }
}
