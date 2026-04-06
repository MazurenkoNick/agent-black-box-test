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
import org.thingsboard.server.common.data.agent.AgentGroup;
import org.thingsboard.server.common.data.agent.AgentProvisionType;
import org.thingsboard.server.common.data.id.AgentGroupId;
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
    private final List<AgentGroupId> groupIds = new ArrayList<>();
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

        // Delete provisioned agents, then groups
        for (AgentId agentId : provisionedAgentIds) {
            try {
                cloudRestClient.deleteAgent(agentId);
            } catch (Exception ignored) {
            }
        }
        provisionedAgentIds.clear();

        for (AgentGroupId groupId : groupIds) {
            try {
                cloudRestClient.deleteAgentGroup(groupId);
            } catch (Exception ignored) {
            }
        }
        groupIds.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    public void testProvisioningHappyPath() {
        AgentGroup group = createProvisionGroup(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);
        Assert.assertNotNull("Group should have auto-generated provision key", group.getProvisionKey());
        Assert.assertNotNull("Group should have auto-generated provision secret", group.getProvisionSecret());

        startProvisioningAgent(group.getProvisionKey(), group.getProvisionSecret(), null);

        // Wait for a new Agent entity to appear that belongs to this group
        Agent provisioned = awaitProvisionedAgent(group.getId());
        Assert.assertNotNull("Provisioned agent should exist", provisioned);
        Assert.assertEquals("Agent should belong to the provision group",
                group.getId(), provisioned.getAgentGroupId());
        provisionedAgentIds.add(provisioned.getId());

        log.info("Provisioned agent: id={}, routingKey={}", provisioned.getId(), provisioned.getRoutingKey());
    }

    @Test
    public void testProvisioningInvalidKey() {
        AgentGroup group = createProvisionGroup(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        String containerId = startProvisioningAgent("not-a-real-key", group.getProvisionSecret(), null);

        // Agent should exit because provisioning was rejected
        awaitContainerExited(containerId);

        // Verify no agent was created in the group
        List<Agent> agents = findAgentsInGroup(group.getId());
        Assert.assertTrue("No agent should be provisioned with invalid key", agents.isEmpty());
    }

    @Test
    public void testProvisioningInvalidSecret() {
        AgentGroup group = createProvisionGroup(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        String containerId = startProvisioningAgent(group.getProvisionKey(), "wrong-secret", null);

        awaitContainerExited(containerId);

        List<Agent> agents = findAgentsInGroup(group.getId());
        Assert.assertTrue("No agent should be provisioned with invalid secret", agents.isEmpty());
    }

    @Test
    public void testProvisioningDisabled() {
        // Create group with provisioning disabled but explicit keys so the lookup succeeds
        AgentGroup group = new AgentGroup();
        group.setName("provision-disabled-" + System.currentTimeMillis());
        group.setProvisionType(AgentProvisionType.DISABLED);
        group.setProvisionKey("disabled-key-" + System.nanoTime());
        group.setProvisionSecret("disabled-secret-" + System.nanoTime());
        group = cloudRestClient.saveAgentGroup(group);
        groupIds.add(group.getId());

        String containerId = startProvisioningAgent(group.getProvisionKey(), group.getProvisionSecret(), null);

        awaitContainerExited(containerId);

        List<Agent> agents = findAgentsInGroup(group.getId());
        Assert.assertTrue("No agent should be provisioned when type is DISABLED", agents.isEmpty());
    }

    @Test
    public void testCredentialPersistenceAcrossRestart() {
        AgentGroup group = createProvisionGroup(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);

        // Create a named volume to persist ~/.tb-agent/credentials.json across restarts
        String volumeName = "provision-creds-" + System.nanoTime();
        dockerClient.createVolumeCmd().withName(volumeName).exec();
        volumeNames.add(volumeName);

        // First start — agent provisions and persists credentials
        String firstContainerId = startProvisioningAgent(
                group.getProvisionKey(), group.getProvisionSecret(), volumeName);

        Agent provisioned = awaitProvisionedAgent(group.getId());
        Assert.assertNotNull("Agent should be provisioned on first start", provisioned);
        provisionedAgentIds.add(provisioned.getId());

        // Stop the first container
        dockerClient.stopContainerCmd(firstContainerId).withTimeout(10).exec();
        awaitContainerExited(firstContainerId);

        // Count agents in group before restart
        int agentCountBefore = findAgentsInGroup(group.getId()).size();

        // Second start — agent should load persisted credentials, not re-provision
        startProvisioningAgent(group.getProvisionKey(), group.getProvisionSecret(), volumeName);

        // Wait for the agent to connect (give it time to start and load credentials)
        Awaitility.await("agent reconnects with persisted credentials")
                .pollDelay(5, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> true);

        // Verify no additional Agent entity was created
        int agentCountAfter = findAgentsInGroup(group.getId()).size();
        Assert.assertEquals("No new agent should be created on restart (credentials reused)",
                agentCountBefore, agentCountAfter);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentGroup createProvisionGroup(AgentProvisionType type) {
        AgentGroup group = new AgentGroup();
        group.setName("provision-group-" + System.currentTimeMillis());
        group.setProvisionType(type);
        // Key and secret are auto-generated by the server for non-DISABLED types
        group = cloudRestClient.saveAgentGroup(group);
        groupIds.add(group.getId());
        log.info("Created provision group: id={}, key={}", group.getId(), group.getProvisionKey());
        return group;
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
     * Polls the REST API until an Agent belonging to the given group appears.
     */
    private Agent awaitProvisionedAgent(AgentGroupId groupId) {
        Awaitility.await("provisioned agent in group " + groupId)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> !findAgentsInGroup(groupId).isEmpty());
        return findAgentsInGroup(groupId).get(0);
    }

    private List<Agent> findAgentsInGroup(AgentGroupId groupId) {
        var page = cloudRestClient.getTenantAgents(new PageLink(100));
        if (page == null || page.getData() == null) return List.of();
        return page.getData().stream()
                .filter(a -> groupId.equals(a.getAgentGroupId()))
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
