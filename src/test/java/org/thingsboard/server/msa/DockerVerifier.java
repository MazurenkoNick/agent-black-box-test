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
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
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
        return createStubComposeProject(projectName, serviceName, image, Map.of());
    }

    /**
     * Same as {@link #createStubComposeProject(String, String, String)}, but with environment
     * variables set on the container. The agent reconstructs the compose definition (including
     * the env section) from container inspection, so env vars set here end up in the compose
     * JSON the server receives on auto-discovery.
     */
    public String createStubComposeProject(String projectName, String serviceName, String image, Map<String, String> env) {
        log.info("Creating compose project '{}' with service '{}' image '{}'", projectName, serviceName, image);
        pullImageIfMissing(image);
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(projectName + "-" + serviceName + "-1")
                .withLabels(Map.of(
                        COMPOSE_PROJECT_LABEL, projectName,
                        COMPOSE_SERVICE_LABEL, serviceName
                ))
                .withEnv(env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())
                .withHostConfig(HostConfig.newHostConfig().withAutoRemove(false))
                .exec();
        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Started container {} for project '{}'", container.getId(), projectName);
        return container.getId();
    }

    /**
     * Tags an existing (or pulled) image with another name inside DinD. Lets tests fake
     * a heavyweight image (e.g. tb-edge) with a lightweight one: auto-discovery only
     * looks at the image string of the container, not at its content.
     */
    public void ensureImageTag(String sourceImage, String targetImage) {
        if (imageExists(targetImage)) {
            return;
        }
        pullImageIfMissing(sourceImage);
        int colonIdx = targetImage.lastIndexOf(':');
        String repository = colonIdx >= 0 ? targetImage.substring(0, colonIdx) : targetImage;
        String tag = colonIdx >= 0 ? targetImage.substring(colonIdx + 1) : "latest";
        dockerClient.tagImageCmd(sourceImage, repository, tag).exec();
        log.info("Tagged image '{}' as '{}' in DinD", sourceImage, targetImage);
    }

    private void pullImageIfMissing(String image) {
        if (imageExists(image)) {
            return;
        }
        try {
            dockerClient.pullImageCmd(image)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while pulling image " + image, e);
        }
    }

    private boolean imageExists(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes every compose-labelled container from DinD, regardless of project.
     * Used to guarantee a pristine DinD for adoption-sensitive tests: any leftover
     * project (e.g. leaked by a previously failed test) would be adopted as an
     * AgentApplication by every agent that connects, polluting auto-install logic.
     */
    public void removeAllComposeProjects() {
        List<Container> containers;
        try {
            containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(List.of(COMPOSE_PROJECT_LABEL))
                    .exec();
        } catch (Exception e) {
            log.warn("Failed to list compose containers for sweep: {}", e.getMessage());
            return;
        }
        for (Container c : containers) {
            try {
                dockerClient.stopContainerCmd(c.getId()).withTimeout(5).exec();
            } catch (Exception e) {
                log.debug("Stop failed for {}: {}", c.getId(), e.getMessage());
            }
            try {
                dockerClient.removeContainerCmd(c.getId()).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Remove failed for {}: {}", c.getId(), e.getMessage());
            }
        }
        if (!containers.isEmpty()) {
            log.info("DinD sweep removed {} leftover compose container(s)", containers.size());
        }
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

    /**
     * Returns the environment variables (KEY -> VALUE) of the container backing the given
     * compose service, read via container inspection. Used to assert that server-side
     * ${tb.<name>} argument substitution reached the deployed container.
     */
    public Map<String, String> getContainerEnv(String projectName, String serviceName) {
        return listContainersByProject(projectName).stream()
                .filter(c -> {
                    String svc = c.getLabels() != null ? c.getLabels().get(COMPOSE_SERVICE_LABEL) : null;
                    return serviceName.equals(svc);
                })
                .findFirst()
                .map(c -> {
                    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(c.getId()).exec();
                    Map<String, String> env = new HashMap<>();
                    String[] envEntries = inspect.getConfig() != null ? inspect.getConfig().getEnv() : null;
                    if (envEntries != null) {
                        for (String entry : envEntries) {
                            int idx = entry.indexOf('=');
                            if (idx > 0) {
                                env.put(entry.substring(0, idx), entry.substring(idx + 1));
                            }
                        }
                    }
                    return env;
                })
                .orElse(Collections.emptyMap());
    }

    /**
     * Returns the published ports of the container backing the given compose service, keyed by
     * "containerPort/protocol" (e.g. "8080/tcp") mapped to the bound host port. Used to assert that a
     * JSON-format argument was injected raw into the structured ports list.
     */
    public Map<String, String> getPublishedPorts(String projectName, String serviceName) {
        return listContainersByProject(projectName).stream()
                .filter(c -> {
                    String svc = c.getLabels() != null ? c.getLabels().get(COMPOSE_SERVICE_LABEL) : null;
                    return serviceName.equals(svc);
                })
                .findFirst()
                .map(c -> {
                    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(c.getId()).exec();
                    Map<String, String> result = new HashMap<>();
                    Ports ports = inspect.getNetworkSettings() != null ? inspect.getNetworkSettings().getPorts() : null;
                    if (ports != null && ports.getBindings() != null) {
                        for (Map.Entry<ExposedPort, Ports.Binding[]> entry : ports.getBindings().entrySet()) {
                            Ports.Binding[] bindings = entry.getValue();
                            if (bindings != null && bindings.length > 0) {
                                result.put(entry.getKey().toString(), bindings[0].getHostPortSpec());
                            }
                        }
                    }
                    return result;
                })
                .orElse(Collections.emptyMap());
    }
}
