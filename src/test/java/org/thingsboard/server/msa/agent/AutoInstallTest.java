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

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.agent.Agent;
import org.thingsboard.server.common.data.agent.AgentAppProfile;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.AgentApplicationOrigin;
import org.thingsboard.server.common.data.agent.AgentApplicationType;
import org.thingsboard.server.common.data.agent.AgentGroup;
import org.thingsboard.server.common.data.agent.AgentProvisionType;
import org.thingsboard.server.common.data.agent.config.AgentAppConfigType;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.id.AgentAppProfileId;
import org.thingsboard.server.common.data.id.AgentGroupId;
import org.thingsboard.server.common.data.id.AgentId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.msa.AbstractContainerTest;
import org.thingsboard.server.msa.ContainerTestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.msa.config.TestConfiguration.TB_AGENT_SERVICE_NAME;
import static org.thingsboard.server.msa.config.TestConfiguration.TB_MONOLITH_SERVICE_NAME;

/**
 * Black-box tests for agent auto-install on InitialSyncComplete.
 * <p>
 * Verifies that when a provisioned agent connects and completes its initial sync,
 * the server automatically creates applications for each profile assigned to the
 * agent's group, including creating related entities (Devices for GATEWAY profiles)
 * with the correct credential type.
 */
@Slf4j
public class AutoInstallTest extends AbstractContainerTest {

    private static DockerClient dockerClient;
    private static String networkName;
    private static String agentImage;

    private final List<String> containerIds = new ArrayList<>();
    private final List<String> volumeNames = new ArrayList<>();
    private final List<AgentGroupId> groupIds = new ArrayList<>();
    private final List<AgentAppProfileId> profileIds = new ArrayList<>();
    private final List<AgentId> provisionedAgentIds = new ArrayList<>();
    private final List<DeviceId> createdDeviceIds = new ArrayList<>();

    @BeforeClass
    public static void initDockerContext() {
        var tbContainer = ContainerTestSuite.testContainer
                .getContainerByServiceName(TB_MONOLITH_SERVICE_NAME)
                .orElseThrow(() -> new IllegalStateException(TB_MONOLITH_SERVICE_NAME + " not running"));
        dockerClient = tbContainer.getDockerClient();

        var inspection = dockerClient.inspectContainerCmd(tbContainer.getContainerId()).exec();
        networkName = inspection.getNetworkSettings().getNetworks().keySet().iterator().next();

        var agentContainer = ContainerTestSuite.testContainer
                .getContainerByServiceName(TB_AGENT_SERVICE_NAME)
                .orElseThrow(() -> new IllegalStateException(TB_AGENT_SERVICE_NAME + " not running"));
        var agentInspection = dockerClient.inspectContainerCmd(agentContainer.getContainerId()).exec();
        agentImage = agentInspection.getConfig().getImage();

        log.info("AutoInstall test context: network={}, agentImage={}", networkName, agentImage);
    }

    @After
    public void cleanup() {
        for (String id : containerIds) {
            try { dockerClient.stopContainerCmd(id).withTimeout(5).exec(); } catch (Exception ignored) {}
            try { dockerClient.removeContainerCmd(id).withForce(true).exec(); } catch (Exception ignored) {}
        }
        containerIds.clear();

        for (String vol : volumeNames) {
            try { dockerClient.removeVolumeCmd(vol).exec(); } catch (Exception ignored) {}
        }
        volumeNames.clear();

        for (AgentId agentId : provisionedAgentIds) {
            try { cloudRestClient.deleteAgent(agentId); } catch (Exception ignored) {}
        }
        provisionedAgentIds.clear();

        for (AgentGroupId groupId : groupIds) {
            try { cloudRestClient.deleteAgentGroup(groupId); } catch (Exception ignored) {}
        }
        groupIds.clear();

        for (AgentAppProfileId profileId : profileIds) {
            try { cloudRestClient.deleteAgentAppProfile(profileId); } catch (Exception ignored) {}
        }
        profileIds.clear();

        for (DeviceId deviceId : createdDeviceIds) {
            try { cloudRestClient.deleteDevice(deviceId); } catch (Exception ignored) {}
        }
        createdDeviceIds.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    public void testAutoInstallGenericApp() {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        Assert.assertTrue("Template should have a 'default' compose", compose.isPresent());

        AgentAppProfile profile = createProfile("auto-generic",
                AgentApplicationType.GENERIC, template, compose.get());
        AgentGroup group = createProvisionGroup();
        cloudRestClient.assignProfileToGroup(group.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(group);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        Assert.assertEquals(profile.getId(), app.getApplicationProfileId());
        log.info("Auto-installed GENERIC app: {}", app.getId());

        awaitEventFinished(app.getId());

        String projectName = getProjectName(app.getId());
        awaitContainersRunning(projectName, 1);
        log.info("GENERIC auto-install verified: containers running in project {}", projectName);
    }

    @Test
    public void testAutoInstallGatewayWithAccessToken() {
        AgentAppTemplate template = getLatestGatewayTemplate();
        JsonNode compose = buildGatewayCompose("accessToken");

        AgentAppProfile profile = createProfile("auto-gw-token",
                AgentApplicationType.GATEWAY, template, compose);
        AgentGroup group = createProvisionGroup();
        cloudRestClient.assignProfileToGroup(group.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(group);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        Assert.assertEquals(AgentApplicationType.GATEWAY, app.getAppType());
        Assert.assertNotNull("GATEWAY app should have a related entity", app.getRelatedEntityId());

        DeviceId deviceId = new DeviceId(app.getRelatedEntityId().getId());
        createdDeviceIds.add(deviceId);

        Device device = cloudRestClient.getDeviceById(deviceId)
                .orElseThrow(() -> new AssertionError("Auto-created gateway device not found"));
        log.info("Auto-created gateway device: {} (name={})", deviceId, device.getName());

        DeviceCredentials creds = cloudRestClient.getDeviceCredentialsByDeviceId(deviceId)
                .orElseThrow(() -> new AssertionError("Device credentials not found"));
        Assert.assertEquals(DeviceCredentialsType.ACCESS_TOKEN, creds.getCredentialsType());
        Assert.assertNotNull("Token should be set", creds.getCredentialsId());
        log.info("ACCESS_TOKEN gateway auto-install verified: credentialsId={}", creds.getCredentialsId());
    }

    @Test
    public void testAutoInstallGatewayWithUsernamePassword() {
        AgentAppTemplate template = getLatestGatewayTemplate();
        JsonNode compose = buildGatewayCompose("usernamePassword");

        AgentAppProfile profile = createProfile("auto-gw-mqtt",
                AgentApplicationType.GATEWAY, template, compose);
        AgentGroup group = createProvisionGroup();
        cloudRestClient.assignProfileToGroup(group.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(group);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        Assert.assertEquals(AgentApplicationType.GATEWAY, app.getAppType());
        Assert.assertNotNull("GATEWAY app should have a related entity", app.getRelatedEntityId());

        DeviceId deviceId = new DeviceId(app.getRelatedEntityId().getId());
        createdDeviceIds.add(deviceId);

        Device device = cloudRestClient.getDeviceById(deviceId)
                .orElseThrow(() -> new AssertionError("Auto-created gateway device not found"));
        log.info("Auto-created gateway device: {} (name={})", deviceId, device.getName());

        DeviceCredentials creds = cloudRestClient.getDeviceCredentialsByDeviceId(deviceId)
                .orElseThrow(() -> new AssertionError("Device credentials not found"));
        Assert.assertEquals(DeviceCredentialsType.MQTT_BASIC, creds.getCredentialsType());
        Assert.assertNotNull("Credentials value should contain MQTT basic creds", creds.getCredentialsValue());

        BasicMqttCredentials mqttCreds = JacksonUtil.fromString(creds.getCredentialsValue(), BasicMqttCredentials.class);
        Assert.assertNotNull("Should parse as BasicMqttCredentials", mqttCreds);
        Assert.assertNotNull("clientId should be set", mqttCreds.getClientId());
        Assert.assertNotNull("userName should be set", mqttCreds.getUserName());
        Assert.assertNotNull("password should be set", mqttCreds.getPassword());
        log.info("MQTT_BASIC gateway auto-install verified: clientId={}, userName={}",
                mqttCreds.getClientId(), mqttCreds.getUserName());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentAppTemplate getLatestGatewayTemplate() {
        return cloudRestClient.getLatestAgentAppTemplate(AgentApplicationType.GATEWAY, AgentAppConfigType.DOCKER_COMPOSE)
                .orElseThrow(() -> new IllegalStateException("No GATEWAY DOCKER_COMPOSE template found"));
    }

    private AgentGroup createProvisionGroup() {
        AgentGroup group = new AgentGroup();
        group.setName("auto-install-group-" + System.currentTimeMillis());
        group.setProvisionType(AgentProvisionType.ALLOW_CREATE_NEW_AGENTS);
        group = cloudRestClient.saveAgentGroup(group);
        groupIds.add(group.getId());
        return group;
    }

    private AgentAppProfile createProfile(String prefix, AgentApplicationType appType,
                                          AgentAppTemplate template, JsonNode compose) {
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName(prefix + "-" + System.currentTimeMillis());
        profile.setAppType(appType);
        profile.setTemplateId(template.getId());
        DockerComposeConfig config = new DockerComposeConfig();
        config.setCompose(compose);
        profile.setConfig(config);
        profile = cloudRestClient.saveAgentAppProfile(profile);
        profileIds.add(profile.getId());
        return profile;
    }

    /**
     * Builds a minimal gateway compose config with the required env vars.
     * Placeholder values will be replaced by MergeCredentialsToConfigRule
     * with the auto-created Device's actual credentials during auto-install.
     */
    private JsonNode buildGatewayCompose(String securityType) {
        String json;
        if ("accessToken".equals(securityType)) {
            json = "{\"services\":{\"tb-gateway\":{" +
                    "\"image\":\"thingsboard/tb-gateway:3.8-stable\"," +
                    "\"environment\":{" +
                    "\"TB_GW_SECURITY_TYPE\":\"accessToken\"," +
                    "\"TB_GW_ACCESS_TOKEN\":\"placeholder\"" +
                    "}}}}";
        } else {
            json = "{\"services\":{\"tb-gateway\":{" +
                    "\"image\":\"thingsboard/tb-gateway:3.8-stable\"," +
                    "\"environment\":{" +
                    "\"TB_GW_SECURITY_TYPE\":\"usernamePassword\"," +
                    "\"TB_GW_CLIENT_ID\":\"placeholder\"," +
                    "\"TB_GW_USERNAME\":\"placeholder\"," +
                    "\"TB_GW_PASSWORD\":\"placeholder\"" +
                    "}}}}";
        }
        return JacksonUtil.toJsonNode(json);
    }

    /**
     * Creates a provisioning agent, waits for it to register, and returns the provisioned Agent.
     */
    private Agent provisionAndConnect(AgentGroup group) {
        String volumeName = "auto-install-" + System.nanoTime();
        dockerClient.createVolumeCmd().withName(volumeName).exec();
        volumeNames.add(volumeName);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(networkName)
                .withBinds(new Bind(volumeName, new Volume("/root/.tb-agent")));

        CreateContainerResponse response = dockerClient.createContainerCmd(agentImage)
                .withEnv(
                        "TB_SERVER_ADDR=tb-monolith:7070",
                        "AUTO_PROVISION=true",
                        "TB_PROVISION_KEY=" + group.getProvisionKey(),
                        "TB_PROVISION_SECRET=" + group.getProvisionSecret(),
                        "DOCKER_HOST=tcp://dind:2375"
                )
                .withHostConfig(hostConfig)
                .exec();
        containerIds.add(response.getId());
        dockerClient.startContainerCmd(response.getId()).exec();
        log.info("Started provisioning agent container: {}", response.getId().substring(0, 12));

        Agent provisioned = awaitProvisionedAgent(group.getId());
        provisionedAgentIds.add(provisioned.getId());
        return provisioned;
    }

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

    private AgentApplication awaitAutoInstalledApp(AgentId agentId, AgentAppProfileId profileId) {
        Awaitility.await("auto-installed app for agent " + agentId + " profile " + profileId)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(90, TimeUnit.SECONDS)
                .until(() -> findAppByProfile(agentId, profileId).isPresent());
        return findAppByProfile(agentId, profileId).get();
    }

    private Optional<AgentApplication> findAppByProfile(AgentId agentId, AgentAppProfileId profileId) {
        var page = cloudRestClient.getAgentApplicationsByAgentId(agentId, new PageLink(100));
        if (page == null || page.getData() == null) return Optional.empty();
        return page.getData().stream()
                .filter(a -> profileId.equals(a.getApplicationProfileId()))
                .findFirst();
    }
}
