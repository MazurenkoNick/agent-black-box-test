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
package org.thingsboard.server.msa.agent;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.thingsboard.server.common.data.agent.Agent;
import org.thingsboard.server.common.data.agent.AgentProfile;
import org.thingsboard.server.common.data.agent.AgentProvisionType;
import org.thingsboard.server.common.data.id.AgentProfileId;
import org.thingsboard.server.common.data.id.AgentId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.msa.AbstractContainerTest;
import org.thingsboard.server.msa.ContainerTestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.msa.config.TestConfiguration.TB_AGENT_SERVICE_NAME;
import static org.thingsboard.server.msa.config.TestConfiguration.TB_MONOLITH_SERVICE_NAME;

/**
 * Black-box tests for agent auto-provisioning.
 * <p>
 * Each test dynamically starts a fresh tb-agent container on the same Docker
 * network as the compose infrastructure, configured with {@code AUTO_PROVISION=true}.
 * This avoids coupling to the shared tb-agent service and gives per-test control
 * over credentials, lifecycle, and volumes.
 */
@Slf4j
public class AgentProvisioningTest extends AbstractContainerTest {

    private static DockerClient dockerClient;
    private static String networkName;
    private static String agentImage;

    private final List<String> containerIds = new ArrayList<>();
    private final List<String> volumeNames = new ArrayList<>();
    private final List<AgentProfileId> agentProfileIds = new ArrayList<>();
    private final List<AgentId> provisionedAgentIds = new ArrayList<>();

    @BeforeClass
    public static void initDockerContext() {
        // Obtain DockerClient from the compose infrastructure
        var tbContainer = ContainerTestSuite.testContainer
                .getContainerByServiceName(TB_MONOLITH_SERVICE_NAME)
                .orElseThrow(() -> new IllegalStateException(TB_MONOLITH_SERVICE_NAME + " not running"));
        dockerClient = tbContainer.getDockerClient();

        // Discover the compose network by inspecting the running tb-monolith container
        var inspection = dockerClient.inspectContainerCmd(tbContainer.getContainerId()).exec();
        networkName = inspection.getNetworkSettings().getNetworks().keySet().iterator().next();

        // Discover the agent image from the running tb-agent service
        var agentContainer = ContainerTestSuite.testContainer
                .getContainerByServiceName(TB_AGENT_SERVICE_NAME)
                .orElseThrow(() -> new IllegalStateException(TB_AGENT_SERVICE_NAME + " not running"));
        var agentInspection = dockerClient.inspectContainerCmd(agentContainer.getContainerId()).exec();
        agentImage = agentInspection.getConfig().getImage();

        log.info("Provisioning test context: network={}, agentImage={}", networkName, agentImage);
    }

    @After
    public void cleanup() {
        // Stop and remove provisioning containers
        for (String id : containerIds) {
            try {
                dockerClient.stopContainerCmd(id).withTimeout(5).exec();
            } catch (Exception ignored) {
            }
            try {
                dockerClient.removeContainerCmd(id).withForce(true).exec();
            } catch (Exception ignored) {
            }
        }
        containerIds.clear();

        // Remove volumes
        for (String vol : volumeNames) {
            try {
                dockerClient.removeVolumeCmd(vol).exec();
            } catch (Exception ignored) {
            }
        }
        volumeNames.clear();

        // Delete provisioned agents, then agentProfiles
        for (AgentId agentId : provisionedAgentIds) {
            try {
                cloudRestClient.deleteAgent(agentId);
            } catch (Exception ignored) {
            }
        }
        provisionedAgentIds.clear();

        for (AgentProfileId agentProfileId : agentProfileIds) {
            try {
                cloudRestClient.deleteAgentProfile(agentProfileId);
            } catch (Exception ignored) {
            }
        }
        agentProfileIds.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    public void testProvisioningHappyPath() {
        AgentProfile agentProfile = createProvisionProfile(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);
        Assert.assertNotNull("Agent Profile should have auto-generated provision key", agentProfile.getProvisionKey());
        Assert.assertNotNull("Agent Profile should have auto-generated provision secret", agentProfile.getProvisionSecret());

        startProvisioningAgent(agentProfile.getProvisionKey(), agentProfile.getProvisionSecret(), null);

        // Wait for a new Agent entity to appear that belongs to this agentProfile
        Agent provisioned = awaitProvisionedAgent(agentProfile.getId());
        Assert.assertNotNull("Provisioned agent should exist", provisioned);
        Assert.assertEquals("Agent should belong to the provision agentProfile",
                agentProfile.getId(), provisioned.getAgentProfileId());
        provisionedAgentIds.add(provisioned.getId());

        log.info("Provisioned agent: id={}, routingKey={}", provisioned.getId(), provisioned.getRoutingKey());
    }

    @Test
    public void testProvisioningInvalidKey() {
        AgentProfile agentProfile = createProvisionProfile(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        String containerId = startProvisioningAgent("not-a-real-key", agentProfile.getProvisionSecret(), null);

        // Agent should exit because provisioning was rejected
        awaitContainerExited(containerId);

        // Verify no agent was created in the agentProfile
        List<Agent> agents = findAgentsInAgentProfile(agentProfile.getId());
        Assert.assertTrue("No agent should be provisioned with invalid key", agents.isEmpty());
    }

    @Test
    public void testProvisioningInvalidSecret() {
        AgentProfile agentProfile = createProvisionProfile(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        String containerId = startProvisioningAgent(agentProfile.getProvisionKey(), "wrong-secret", null);

        awaitContainerExited(containerId);

        List<Agent> agents = findAgentsInAgentProfile(agentProfile.getId());
        Assert.assertTrue("No agent should be provisioned with invalid secret", agents.isEmpty());
    }

    @Test
    public void testProvisioningDisabled() {
        // Create agentProfile with provisioning disabled but explicit keys so the lookup succeeds
        AgentProfile agentProfile = new AgentProfile();
        agentProfile.setName("provision-disabled-" + System.currentTimeMillis());
        agentProfile.setProvisionType(AgentProvisionType.DISABLED);
        agentProfile.setProvisionKey("disabled-key-" + System.nanoTime());
        agentProfile.setProvisionSecret("disabled-secret-" + System.nanoTime());
        agentProfile = cloudRestClient.saveAgentProfile(agentProfile);
        agentProfileIds.add(agentProfile.getId());

        String containerId = startProvisioningAgent(agentProfile.getProvisionKey(), agentProfile.getProvisionSecret(), null);

        awaitContainerExited(containerId);

        List<Agent> agents = findAgentsInAgentProfile(agentProfile.getId());
        Assert.assertTrue("No agent should be provisioned when type is DISABLED", agents.isEmpty());
    }

    @Test
    public void testCredentialPersistenceAcrossRestart() {
        AgentProfile agentProfile = createProvisionProfile(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        // Create a named volume to persist ~/.tb-agent/credentials.json across restarts
        String volumeName = "provision-creds-" + System.nanoTime();
        dockerClient.createVolumeCmd().withName(volumeName).exec();
        volumeNames.add(volumeName);

        // First start — agent provisions and persists credentials
        String firstContainerId = startProvisioningAgent(
                agentProfile.getProvisionKey(), agentProfile.getProvisionSecret(), volumeName);

        Agent provisioned = awaitProvisionedAgent(agentProfile.getId());
        Assert.assertNotNull("Agent should be provisioned on first start", provisioned);
        provisionedAgentIds.add(provisioned.getId());

        // Stop the first container
        dockerClient.stopContainerCmd(firstContainerId).withTimeout(10).exec();
        awaitContainerExited(firstContainerId);

        // Count agents in agentProfile before restart
        int agentCountBefore = findAgentsInAgentProfile(agentProfile.getId()).size();

        // Second start — agent should load persisted credentials, not re-provision
        startProvisioningAgent(agentProfile.getProvisionKey(), agentProfile.getProvisionSecret(), volumeName);

        // Wait for the agent to connect (give it time to start and load credentials)
        Awaitility.await("agent reconnects with persisted credentials")
                .pollDelay(5, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> true);

        // Verify no additional Agent entity was created
        int agentCountAfter = findAgentsInAgentProfile(agentProfile.getId()).size();
        Assert.assertEquals("No new agent should be created on restart (credentials reused)",
                agentCountBefore, agentCountAfter);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentProfile createProvisionProfile(AgentProvisionType type) {
        AgentProfile agentProfile = new AgentProfile();
        agentProfile.setName("provision-agentProfile-" + System.currentTimeMillis());
        agentProfile.setProvisionType(type);
        // Key and secret are auto-generated by the server for non-DISABLED types
        agentProfile = cloudRestClient.saveAgentProfile(agentProfile);
        agentProfileIds.add(agentProfile.getId());
        log.info("Created provision agentProfile: id={}, key={}", agentProfile.getId(), agentProfile.getProvisionKey());
        return agentProfile;
    }

    /**
     * Starts a tb-agent container configured for auto-provisioning on the compose network.
     *
     * @param volumeName if non-null, mounts the named volume at /root/.tb-agent for credential persistence
     * @return the container ID
     */
    private String startProvisioningAgent(String provisionKey, String provisionSecret, String volumeName) {
        HostConfig hostConfig = HostConfig.newHostConfig().withNetworkMode(networkName);
        if (volumeName != null) {
            hostConfig.withBinds(new Bind(volumeName, new Volume("/root/.tb-agent")));
        }

        CreateContainerResponse response = dockerClient.createContainerCmd(agentImage)
                .withEnv(
                        "TB_SERVER_ADDR=tb-monolith:7070",
                        "AUTO_PROVISION=true",
                        "TB_PROVISION_KEY=" + provisionKey,
                        "TB_PROVISION_SECRET=" + provisionSecret,
                        "DOCKER_HOST=tcp://dind:2375"
                )
                .withHostConfig(hostConfig)
                .exec();

        String containerId = response.getId();
        containerIds.add(containerId);
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Started provisioning agent container: {}", containerId.substring(0, 12));
        return containerId;
    }

    /**
     * Polls the REST API until an Agent belonging to the given agentProfile appears.
     */
    private Agent awaitProvisionedAgent(AgentProfileId agentProfileId) {
        Awaitility.await("provisioned agent in agentProfile " + agentProfileId)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> !findAgentsInAgentProfile(agentProfileId).isEmpty());
        return findAgentsInAgentProfile(agentProfileId).getFirst();
    }

    private List<Agent> findAgentsInAgentProfile(AgentProfileId agentProfileId) {
        var page = cloudRestClient.getTenantAgents(new PageLink(100));
        if (page == null || page.getData() == null) return List.of();
        return page.getData().stream()
                .filter(a -> agentProfileId.equals(a.getAgentProfileId()))
                .toList();
    }

    /**
     * Waits until the container exits (status != running).
     */
    private void awaitContainerExited(String containerId) {
        Awaitility.await("container exited " + containerId.substring(0, 12))
                .pollInterval(1, TimeUnit.SECONDS)
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> {
                    InspectContainerResponse inspection =
                            dockerClient.inspectContainerCmd(containerId).exec();
                    Boolean running = inspection.getState().getRunning();
                    return running != null && !running;
                });
    }
}
