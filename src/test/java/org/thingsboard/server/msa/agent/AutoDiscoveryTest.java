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
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.agent.AgentAppEvent;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppProfile;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.AgentApplicationOrigin;
import org.thingsboard.server.common.data.agent.AgentApplicationType;
import org.thingsboard.server.common.data.agent.AgentProfile;
import org.thingsboard.server.common.data.agent.AgentProvisionType;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.id.AgentAppProfileId;
import org.thingsboard.server.common.data.id.AgentProfileId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Black-box tests for agent application auto-discovery (compose project state sync).
 * <p>
 * A compose project is started directly in DinD (not via the cloud API). The agent
 * picks it up within TB_COMPOSE_SYNC_INTERVAL_SEC and reports it to the server, which
 * creates an AgentApplication with origin DISCOVERED (ComposeAgentAppCreator).
 * <p>
 * Verifies that:
 * - A discovered edge compose project creates an EDGE app bound to the matching template,
 *   and the edge credentials from the compose are resolved into a ManagedByAgentApp
 *   relation to the Edge entity
 * - When the agent's profile has an application profile assigned with
 *   relates-on-auto-discovery enabled, the discovered app is created with that
 *   application profile and an UPDATE event is generated to push the profile config
 *   to the agent (ComposeAgentAppCreator#sendUpdateEventToAgent)
 */
@Slf4j
public class AutoDiscoveryTest extends AbstractContainerTest {

    private static final String EDGE_TEMPLATE_VERSION = "4.2.1.2EDGEPE";
    private static final String EDGE_IMAGE = "thingsboard/tb-edge-pe:" + EDGE_TEMPLATE_VERSION;
    private static final String EDGE_SERVICE_NAME = "mytbedge";
    private static final String STUB_BASE_IMAGE = "nginx:alpine";

    @BeforeClass
    public static void prepareEdgeImage() {
        // Auto-discovery only inspects container metadata (image string + env vars),
        // so a lightweight image tagged as tb-edge-pe avoids pulling the real edge
        // image into DinD. It also lets the profile-driven UPDATE in the second test
        // succeed: the agent skips the pull because the image already exists locally.
        dockerVerifier.ensureImageTag(STUB_BASE_IMAGE, EDGE_IMAGE);
    }

    @Test
    public void testAutoDiscoverEdgeAppRelatesToEdgeEntity() {
        Edge edge = createEdge();
        String projectName = "autodiscoveredge-" + System.currentTimeMillis();
        AgentApplication app = null;
        try {
            dockerVerifier.createStubComposeProject(projectName, EDGE_SERVICE_NAME, EDGE_IMAGE, Map.of(
                    "CLOUD_ROUTING_KEY", edge.getRoutingKey(),
                    "CLOUD_ROUTING_SECRET", edge.getSecret(),
                    "CLOUD_RPC_HOST", "tb-monolith"
            ));

            app = awaitDiscoveredApp(projectName);
            Assert.assertEquals(AgentApplicationOrigin.DISCOVERED, app.getOrigin());
            Assert.assertEquals(AgentApplicationType.EDGE, app.getAppType());
            Assert.assertEquals("Discovered app should be bound to the edge template matching the image version",
                    getEdgeTemplate(EDGE_TEMPLATE_VERSION).getId(), app.getTemplateId());
            Assert.assertNull("Discovered app without matching profile should not be profile-managed",
                    app.getApplicationProfileId());

            // The discovered compose (reconstructed from container inspection) is stored as the app config
            Assert.assertTrue("Config should be DockerComposeConfig", app.getConfig() instanceof DockerComposeConfig);
            DockerComposeConfig config = (DockerComposeConfig) app.getConfig();
            Assert.assertEquals("Edge routing key should be resolvable from the discovered compose",
                    edge.getRoutingKey(), config.getEdgeRoutingKey());

            // Edge credentials from the compose must be resolved into a relation: (edge) -> (app)
            List<EntityRelation> managedByApp = cloudRestClient.findByTo(
                    app.getId(), EntityRelation.MANAGED_BY_AGENT_APP_TYPE, RelationTypeGroup.AGENT);
            Assert.assertEquals("Discovered EDGE app should relate to the Edge entity", 1, managedByApp.size());
            Assert.assertEquals(edge.getId(), managedByApp.getFirst().getFrom());

            log.info("Discovered EDGE app {} related to edge {}", app.getId(), edge.getId());
        } finally {
            deleteAppQuietly(app, projectName);
            dockerVerifier.removeComposeProject(projectName);
            try {
                cloudRestClient.deleteEdge(edge.getId());
            } catch (Exception e) {
                log.warn("Cleanup: failed to delete edge {}", edge.getId(), e);
            }
        }
    }

    @Test
    public void testAutoDiscoveryAssignsAppProfileAndSendsUpdateEvent() {
        AgentAppTemplate template = getEdgeTemplate(EDGE_TEMPLATE_VERSION);

        // Application profile for the edge template. validateForProfile(EDGE) requires
        // the credential env keys to be present; actual values are not resolved here
        // because the discovered app has no related Edge entity.
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName("auto-discovery-profile-" + System.currentTimeMillis());
        profile.setAppType(AgentApplicationType.EDGE);
        profile.setTemplateId(template.getId());
        DockerComposeConfig profileConfig = new DockerComposeConfig();
        profileConfig.setCompose(buildEdgeCompose());
        profile.setConfig(profileConfig);
        profile = cloudRestClient.saveAgentAppProfile(profile);
        AgentAppProfileId profileId = profile.getId();

        AgentProfile agentProfile = new AgentProfile();
        agentProfile.setName("auto-discovery-agentProfile-" + System.currentTimeMillis());
        agentProfile.setProvisionType(AgentProvisionType.DISABLED);
        agentProfile = cloudRestClient.saveAgentProfile(agentProfile);
        AgentProfileId agentProfileId = agentProfile.getId();

        cloudRestClient.assignAppProfileToAgentProfile(agentProfileId, profileId);
        cloudRestClient.setAppProfileRelatesOnAutoDiscovery(agentProfileId, profileId, true);

        // Move the shared agent into the agent profile so the discovered app matches it
        agent.setAgentProfileId(agentProfileId);
        agent = cloudRestClient.saveAgent(agent);

        String projectName = "autodiscoverprofile-" + System.currentTimeMillis();
        AgentApplication app = null;
        try {
            dockerVerifier.createStubComposeProject(projectName, EDGE_SERVICE_NAME, EDGE_IMAGE, Map.of(
                    "CLOUD_ROUTING_KEY", "placeholder",
                    "CLOUD_ROUTING_SECRET", "placeholder",
                    "CLOUD_RPC_HOST", "tb-monolith"
            ));

            app = awaitDiscoveredApp(projectName);
            Assert.assertEquals(AgentApplicationOrigin.DISCOVERED, app.getOrigin());
            Assert.assertEquals(AgentApplicationType.EDGE, app.getAppType());
            Assert.assertEquals("Discovered app should be created with the auto-discovery application profile",
                    profileId, app.getApplicationProfileId());

            // ComposeAgentAppCreator#sendUpdateEventToAgent must generate an UPDATE event
            // that pushes the profile config to the agent
            AgentApplication finalApp = app;
            Awaitility.await("UPDATE event generated for discovered app " + app.getId())
                    .pollInterval(1, TimeUnit.SECONDS)
                    .atMost(30, TimeUnit.SECONDS)
                    .until(() -> getLatestEvent(finalApp.getId()) != null);
            AgentAppEvent event = getLatestEvent(app.getId());
            Assert.assertEquals(AgentAppEventActionType.UPDATE, event.getActionType());

            // The agent applies the profile compose (image already present in DinD)
            awaitEventFinished(app.getId());
            awaitContainersRunning(projectName, 1);

            log.info("Discovered EDGE app {} assigned profile {} and processed UPDATE event", app.getId(), profileId);
        } finally {
            // Detach the shared agent from the test agent profile before deleting it
            try {
                agent.setAgentProfileId(null);
                agent = cloudRestClient.saveAgent(agent);
            } catch (Exception e) {
                log.warn("Cleanup: failed to unassign agent from agentProfile {}", agentProfileId, e);
            }
            deleteAppQuietly(app, projectName);
            dockerVerifier.removeComposeProject(projectName);
            try {
                cloudRestClient.deleteAgentProfile(agentProfileId);
            } catch (Exception e) {
                log.warn("Cleanup: failed to delete agentProfile {}", agentProfileId, e);
            }
            try {
                cloudRestClient.deleteAgentAppProfile(profileId);
            } catch (Exception e) {
                log.warn("Cleanup: failed to delete app profile {}", profileId, e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Edge createEdge() {
        Edge edge = new Edge();
        edge.setName("auto-discovery-edge-" + System.currentTimeMillis());
        edge.setType("default");
        edge.setRoutingKey(UUID.randomUUID().toString());
        edge.setSecret(UUID.randomUUID().toString().replace("-", ""));
        // Required by PE edge validation; values are irrelevant for discovery
        edge.setEdgeLicenseKey("test-license-key");
        edge.setCloudEndpoint(tbUrl);
        return cloudRestClient.saveEdge(edge);
    }

    private JsonNode buildEdgeCompose() {
        return JacksonUtil.toJsonNode("{\"services\":{\"" + EDGE_SERVICE_NAME + "\":{" +
                "\"image\":\"" + EDGE_IMAGE + "\"," +
                "\"environment\":{" +
                "\"CLOUD_ROUTING_KEY\":\"placeholder\"," +
                "\"CLOUD_ROUTING_SECRET\":\"placeholder\"," +
                "\"CLOUD_RPC_HOST\":\"tb-monolith\"" +
                "}}}}");
    }

    private AgentApplication awaitDiscoveredApp(String projectName) {
        Awaitility.await("discovered app for project " + projectName)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> findAppByProjectName(projectName).isPresent());
        return findAppByProjectName(projectName).get();
    }

    private Optional<AgentApplication> findAppByProjectName(String projectName) {
        var page = cloudRestClient.getAgentApplicationsByAgentId(agent.getId(), new PageLink(100));
        if (page == null || page.getData() == null) return Optional.empty();
        return page.getData().stream()
                .filter(a -> projectName.equals(a.getProjectName()))
                .findFirst();
    }
}
