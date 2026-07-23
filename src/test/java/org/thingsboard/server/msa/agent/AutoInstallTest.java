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
import org.thingsboard.server.common.data.agent.AgentApplicationInfo;
import org.thingsboard.server.common.data.agent.AgentApplicationOrigin;
import org.thingsboard.server.common.data.agent.AgentApplicationType;
import org.thingsboard.server.common.data.agent.AgentProfile;
import org.thingsboard.server.common.data.agent.AgentProvisionType;
import org.thingsboard.server.common.data.agent.config.AgentAppConfigType;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.id.AgentAppProfileId;
import org.thingsboard.server.common.data.id.AgentProfileId;
import org.thingsboard.server.common.data.id.AgentId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
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
 * agent's profile, including creating related entities (Devices for GATEWAY profiles)
 * with the correct credential type.
 */
@Slf4j
public class AutoInstallTest extends AbstractContainerTest {

    private static final String LEGACY_GATEWAY_IMAGE = "thingsboard/tb-gateway:3.6.3";

    private static DockerClient dockerClient;
    private static String networkName;
    private static String agentImage;
    private static String tbMonolithIp;

    private final List<String> containerIds = new ArrayList<>();
    private final List<String> volumeNames = new ArrayList<>();
    private final List<AgentProfileId> agentProfileIds = new ArrayList<>();
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
        // Containers inside DinD cannot resolve outer compose network aliases (e.g. 'tb-monolith'),
        // so tests that need an app to reach the platform pass its network IP instead.
        tbMonolithIp = inspection.getNetworkSettings().getNetworks().get(networkName).getIpAddress();

        var agentContainer = ContainerTestSuite.testContainer
                .getContainerByServiceName(TB_AGENT_SERVICE_NAME)
                .orElseThrow(() -> new IllegalStateException(TB_AGENT_SERVICE_NAME + " not running"));
        var agentInspection = dockerClient.inspectContainerCmd(agentContainer.getContainerId()).exec();
        agentImage = agentInspection.getConfig().getImage();

        // Remove containers left over from earlier tests. When an agent connects,
        // it picks up every compose project it finds in DinD, and such leftover
        // apps can break the auto-install checks in these tests.
        dockerVerifier.removeAllComposeProjects();

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

        for (AgentProfileId agentProfileId : agentProfileIds) {
            try { cloudRestClient.deleteAgentProfile(agentProfileId); } catch (Exception ignored) {}
        }
        agentProfileIds.clear();

        for (AgentAppProfileId profileId : profileIds) {
            try { cloudRestClient.deleteAgentAppProfile(profileId); } catch (Exception ignored) {}
        }
        profileIds.clear();

        for (DeviceId deviceId : createdDeviceIds) {
            try { cloudRestClient.deleteDevice(deviceId); } catch (Exception ignored) {}
        }
        createdDeviceIds.clear();

        // Auto-installed apps keep running in DinD after their agent is deleted,
        // and nothing is left to remove them. Their fixed host port (8080) would
        // block installs in the test classes that run after this one.
        dockerVerifier.removeAllComposeProjects();
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
        AgentProfile agentProfile = createProvisionAgentProfile();
        cloudRestClient.assignAppProfileToAgentProfile(agentProfile.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(agentProfile);

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
        AgentProfile agentProfile = createProvisionAgentProfile();
        cloudRestClient.assignAppProfileToAgentProfile(agentProfile.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(agentProfile);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        Assert.assertEquals(AgentApplicationType.GATEWAY, app.getAppType());
        List<EntityRelation> managedByAgent = cloudRestClient.findByTo(app.getId(), EntityRelation.MANAGED_BY_AGENT_APP_TYPE, RelationTypeGroup.AGENT);
        Assert.assertEquals("GATEWAY app should have a related entity", 1, managedByAgent.size());

        DeviceId deviceId = new DeviceId(managedByAgent.getFirst().getFrom().getId());
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
        AgentProfile agentProfile = createProvisionAgentProfile();
        cloudRestClient.assignAppProfileToAgentProfile(agentProfile.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(agentProfile);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        List<EntityRelation> managedByAgent = cloudRestClient.findByTo(app.getId(), EntityRelation.MANAGED_BY_AGENT_APP_TYPE, RelationTypeGroup.AGENT);
        Assert.assertEquals("GATEWAY app should have a related entity", 1, managedByAgent.size());

        DeviceId deviceId = new DeviceId(managedByAgent.getFirst().getFrom().getId());
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

    /**
     * Auto-installs a GATEWAY app whose profile compose uses the legacy env schema
     * (pre-3.6 gateway images read unprefixed 'host'/'port'/'accessToken' instead of TB_GW_*)
     * and verifies the gateway is functional post-install: the real legacy image starts in DinD,
     * connects to the platform over MQTT with the injected token, and the auto-created
     * device becomes active.
     */
    @Test
    public void testAutoInstallLegacyGatewayIsFunctional() {
        // pre-pull the real gateway image so the container-start await doesn't burn its timeout on the pull
        dockerVerifier.ensureImage(LEGACY_GATEWAY_IMAGE);

        AgentAppTemplate template = getLatestGatewayTemplate();
        JsonNode compose = buildLegacyGatewayCompose(tbMonolithIp);

        AgentAppProfile profile = createProfile("auto-gw-legacy",
                AgentApplicationType.GATEWAY, template, compose);
        AgentProfile agentProfile = createProvisionAgentProfile();
        cloudRestClient.assignAppProfileToAgentProfile(agentProfile.getId(), profile.getId());

        Agent provisioned = provisionAndConnect(agentProfile);

        AgentApplication app = awaitAutoInstalledApp(provisioned.getId(), profile.getId());
        Assert.assertEquals(AgentApplicationOrigin.AUTO_PROVISIONED, app.getOrigin());
        Assert.assertEquals(AgentApplicationType.GATEWAY, app.getAppType());
        List<EntityRelation> managedByAgent = cloudRestClient.findByTo(app.getId(), EntityRelation.MANAGED_BY_AGENT_APP_TYPE, RelationTypeGroup.AGENT);
        Assert.assertEquals("GATEWAY app should have a related entity", 1, managedByAgent.size());

        DeviceId deviceId = new DeviceId(managedByAgent.getFirst().getFrom().getId());
        createdDeviceIds.add(deviceId);

        DeviceCredentials creds = cloudRestClient.getDeviceCredentialsByDeviceId(deviceId)
                .orElseThrow(() -> new AssertionError("Device credentials not found"));
        Assert.assertEquals("Legacy compose without username/password implies ACCESS_TOKEN",
                DeviceCredentialsType.ACCESS_TOKEN, creds.getCredentialsType());

        // Credentials must be injected under the legacy env names, not TB_GW_*
        AgentApplication fullApp = getAgentApplicationById(app.getId())
                .orElseThrow(() -> new AssertionError("App not found: " + app.getId()));
        JsonNode env = ((DockerComposeConfig) fullApp.getConfig()).getCompose()
                .get("services").get("tb-gateway").get("environment");
        Assert.assertEquals(creds.getCredentialsId(), env.get("accessToken").asText());
        Assert.assertEquals(tbMonolithIp, env.get("host").asText());
        Assert.assertFalse("Legacy compose must not receive TB_GW_* env vars", env.has("TB_GW_ACCESS_TOKEN"));
        Assert.assertFalse(env.has("TB_GW_SECURITY_TYPE"));

        // The real legacy gateway container must start in DinD...
        String projectName = getProjectName(app.getId());
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Legacy gateway image should be running in DinD",
                dockerVerifier.hasRunningContainerWithImage(projectName, LEGACY_GATEWAY_IMAGE));

        // ...and do its job: connect over MQTT with the injected token -> device becomes active
        awaitDeviceActive(deviceId);
        log.info("Legacy gateway auto-install verified: device {} is active", deviceId);
    }

    private void awaitDeviceActive(DeviceId deviceId) {
        Awaitility.await("gateway device " + deviceId + " connected and active")
                .pollInterval(3, TimeUnit.SECONDS)
                .atMost(180, TimeUnit.SECONDS)
                .until(() -> cloudRestClient.getAttributeKvEntries(deviceId, List.of("active")).stream()
                        .anyMatch(entry -> Boolean.TRUE.toString().equals(entry.getValueAsString())));
    }

    /**
     * Compose using the legacy (pre-3.6) gateway env schema: unprefixed 'host'/'port'/'accessToken'.
     * The token placeholder is replaced by MergeCredentialsToConfigRule under the same legacy names.
     */
    private JsonNode buildLegacyGatewayCompose(String tbHost) {
        String json = "{\"services\":{\"tb-gateway\":{" +
                "\"image\":\"" + LEGACY_GATEWAY_IMAGE + "\"," +
                "\"environment\":{" +
                "\"host\":\"" + tbHost + "\"," +
                "\"port\":\"1883\"," +
                "\"accessToken\":\"placeholder\"" +
                "}}}}";
        return JacksonUtil.toJsonNode(json);
    }

    private AgentProfile createProvisionAgentProfile() {
        AgentProfile agentProfile = new AgentProfile();
        agentProfile.setName("auto-install-agentProfile-" + System.currentTimeMillis());
        agentProfile.setProvisionType(AgentProvisionType.AUTO_INSTALL_PER_APP_PROFILE);
        agentProfile = cloudRestClient.saveAgentProfile(agentProfile);
        agentProfileIds.add(agentProfile.getId());
        return agentProfile;
    }

    private AgentAppProfile createProfile(String prefix, AgentApplicationType appType,
                                          AgentAppTemplate template, JsonNode compose) {
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName(prefix + "-" + System.currentTimeMillis());
        profile.setAppType(appType);
        profile.setTemplateVersion(template.getCurrentVersion());
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
    private Agent provisionAndConnect(AgentProfile agentProfile) {
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
                        "TB_PROVISION_KEY=" + agentProfile.getProvisionKey(),
                        "TB_PROVISION_SECRET=" + agentProfile.getProvisionSecret(),
                        "DOCKER_HOST=tcp://dind:2375"
                )
                .withHostConfig(hostConfig)
                .exec();
        containerIds.add(response.getId());
        dockerClient.startContainerCmd(response.getId()).exec();
        log.info("Started provisioning agent container: {}", response.getId().substring(0, 12));

        Agent provisioned = awaitProvisionedAgent(agentProfile.getId());
        provisionedAgentIds.add(provisioned.getId());
        return provisioned;
    }

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

    private AgentApplication awaitAutoInstalledApp(AgentId agentId, AgentAppProfileId profileId) {
        Awaitility.await("auto-installed app for agent " + agentId + " profile " + profileId)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(90, TimeUnit.SECONDS)
                .until(() -> findAppByProfile(agentId, profileId).isPresent());
        return findAppByProfile(agentId, profileId).get();
    }

    private Optional<AgentApplicationInfo> findAppByProfile(AgentId agentId, AgentAppProfileId profileId) {
        var page = cloudRestClient.getAgentApplicationsByAgentId(agentId, new PageLink(100));
        if (page == null || page.getData() == null) return Optional.empty();
        return page.getData().stream()
                .filter(a -> profileId.equals(a.getApplicationProfileId()))
                .findFirst();
    }
}
