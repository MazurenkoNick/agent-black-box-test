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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.agent.Agent;
import org.thingsboard.server.common.data.agent.AgentAppEvent;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventRequest;
import org.thingsboard.server.common.data.agent.AgentAppEventStatus;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.AgentApplicationType;
import org.thingsboard.server.common.data.agent.config.AgentAppConfigType;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.step.AgentAppStepType;
import org.thingsboard.server.common.data.agent.step.ComposeTypeChoiceStep;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.id.AgentApplicationId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.msa.config.AgentConfiguration;
import org.thingsboard.server.msa.config.TBConfiguration;
import org.thingsboard.server.msa.config.TestConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.msa.config.TestConfiguration.DIND_SERVICE_NAME;
import static org.thingsboard.server.msa.config.TestConfiguration.TB_MONOLITH_SERVICE_NAME;

@Slf4j
public abstract class AbstractContainerTest {

    protected static final TestConfiguration TEST_CONFIGURATION = new TestConfiguration();
    protected static final TBConfiguration TB_CONFIGURATION = TEST_CONFIGURATION.getTbConfiguration();
    protected static final AgentConfiguration AGENT_CONFIGURATION = TEST_CONFIGURATION.getAgentConfiguration();

    public static RestClient cloudRestClient = null;
    protected static String tbUrl;

    protected static DockerVerifier dockerVerifier;
    protected static Agent agent;

    @Rule
    public TestRule watcher = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            log.info("==> Starting test: {}.{}", description.getClassName(), description.getMethodName());
        }

        @Override
        protected void succeeded(Description description) {
            log.info("==> Test succeeded: {}.{}", description.getClassName(), description.getMethodName());
        }

        @Override
        protected void failed(Throwable e, Description description) {
            log.error("==> Test failed: {}.{}", description.getClassName(), description.getMethodName(), e);
        }
    };

    @BeforeClass
    public static void before() throws Exception {
        // Resolve mapped ports from running test containers
        String tbHost = ContainerTestSuite.testContainer.getServiceHost(TB_MONOLITH_SERVICE_NAME, 8080);
        Integer tbPort = ContainerTestSuite.testContainer.getServicePort(TB_MONOLITH_SERVICE_NAME, 8080);
        tbUrl = "http://" + tbHost + ":" + tbPort;

        String dindHost = ContainerTestSuite.testContainer.getServiceHost(DIND_SERVICE_NAME, 2375);
        Integer dindPort = ContainerTestSuite.testContainer.getServicePort(DIND_SERVICE_NAME, 2375);

        cloudRestClient = new RestClient(tbUrl);
        loginWithRetries(TB_CONFIGURATION.getEmail(), TB_CONFIGURATION.getPassword());

        dockerVerifier = new DockerVerifier(dindHost, dindPort);

        // Create the Agent entity on cloud side (routing key/secret must match what tb-agent is configured with)
        agent = createAgent();

        // Wait for templates to be synced from the local git repo
        awaitTemplatesSynced();

        log.info("Test setup complete. TB URL: {}, Agent: {}", tbUrl, agent.getId());
    }

    private static void loginWithRetries(String email, String password) {
        Awaitility.await("login to ThingsBoard")
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .atMost(90, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        cloudRestClient.login(email, password);
                        return true;
                    } catch (Exception e) {
                        log.warn("Login failed, retrying: {}", e.getMessage());
                        return false;
                    }
                });
    }

    private static Agent createAgent() {
        // Check if an agent with this routing key already exists (e.g., from previous run)
        var existingAgents = cloudRestClient.getTenantAgents(new PageLink(100));
        if (existingAgents != null) {
            for (Agent existing : existingAgents.getData()) {
                if (AGENT_CONFIGURATION.getRoutingKey().equals(existing.getRoutingKey())) {
                    log.info("Agent with routing key {} already exists: {}", existing.getRoutingKey(), existing.getId());
                    return existing;
                }
            }
        }
        Agent agentEntity = new Agent();
        agentEntity.setName("test-agent-" + System.currentTimeMillis());
        agentEntity.setRoutingKey(AGENT_CONFIGURATION.getRoutingKey());
        agentEntity.setSecret(AGENT_CONFIGURATION.getRoutingSecret());
        return cloudRestClient.saveAgent(agentEntity);
    }

    private static void awaitTemplatesSynced() {
        log.info("Waiting for agent app templates to sync...");
        Awaitility.await("agent app templates synced")
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> {
                    List<AgentAppTemplate> templates = cloudRestClient.getAgentAppTemplates();
                    return templates != null && !templates.isEmpty();
                });
        log.info("Agent app templates synced: {}", cloudRestClient.getAgentAppTemplates().size());
    }

    // --- Template helpers ---

    protected AgentAppTemplate getEdgeTemplate(String version) {
        return getAppTemplate(AgentApplicationType.EDGE, version);
    }
    protected AgentAppTemplate getGenericTemplate(String version) {
        return getAppTemplate(AgentApplicationType.GENERIC, version);
    }

    private static @NonNull AgentAppTemplate getAppTemplate(AgentApplicationType appType, String version) {
        return cloudRestClient.getAgentAppTemplates().stream()
                .filter(t -> appType.equals(t.getAppType())
                        && version.equals(t.getCurrentVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Template not found for version: " + version));
    }

    protected AgentAppTemplate getLatestGenericTemplate() {
        return cloudRestClient.getLatestAgentAppTemplate(AgentApplicationType.GENERIC, AgentAppConfigType.DOCKER_COMPOSE)
                .orElseThrow(() -> new IllegalStateException("No GENERIC DOCKER_COMPOSE template found"));
    }

    // --- App lifecycle helpers ---

    protected AgentApplication installDockerComposeApp(AgentAppTemplate template, JsonNode compose) {
        AgentApplication app = AgentApplication.fromTemplate(template);
        app.setAgentId(agent.getId());
        app.setName("test-app-" + System.currentTimeMillis());
        DockerComposeConfig config = (DockerComposeConfig) app.getConfig();
        config.setCompose(compose);
        AgentAppEventRequest request = new AgentAppEventRequest();
        request.setActionType(AgentAppEventActionType.INSTALL);
        request.setApplication(app);
        return cloudRestClient.installAgentApp(request);
    }

    protected void createAppEvent(AgentApplicationId appId, AgentAppEventActionType actionType) {
        AgentApplication app = getAgentApplicationById(appId)
                .orElseThrow(() -> new IllegalStateException("App not found: " + appId));
        AgentAppEventRequest request = new AgentAppEventRequest();
        request.setActionType(actionType);
        request.setApplication(app);
        cloudRestClient.createAgentAppEvent(appId, request);
    }

    protected String getProjectName(AgentApplicationId appId) {
        return getAgentApplicationById(appId)
                .map(AgentApplication::getProjectName)
                .orElseThrow(() -> new IllegalStateException("App not found: " + appId));
    }

    protected Optional<AgentApplication> getAgentApplicationById(AgentApplicationId appId) {
        return cloudRestClient.getAgentApplicationById(appId);
    }

    // --- Event status helpers ---

    protected void awaitEventFinished(AgentApplicationId appId) {
        awaitEventStatus(appId, AgentAppEventStatus.FINISHED);
    }

    protected void awaitEventStatus(AgentApplicationId appId, AgentAppEventStatus expectedStatus) {
        Awaitility.await("event status " + expectedStatus)
                .pollInterval(1, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> {
                    PageData<AgentAppEvent> events = cloudRestClient.getAgentAppEvents(appId, new PageLink(1));
                    if (events == null || events.getData().isEmpty()) return false;
                    AgentAppEventStatus status = events.getData().get(0).getStatus();
                    return expectedStatus.equals(status) || AgentAppEventStatus.ERROR.equals(status);
                });
        // Verify it actually finished (not errored)
        PageData<AgentAppEvent> events = cloudRestClient.getAgentAppEvents(appId, new PageLink(1));
        AgentAppEventStatus actualStatus = events.getData().get(0).getStatus();
        if (!expectedStatus.equals(actualStatus)) {
            throw new AssertionError("Expected event status " + expectedStatus + " but got " + actualStatus);
        }
    }

    protected void awaitAllEventsFinished(AgentApplicationId appId) {
        Awaitility.await("all events finished for " + appId)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> {
                    PageData<AgentAppEvent> events = cloudRestClient.getAgentAppEvents(appId, new PageLink(100));
                    if (events == null || events.getData().isEmpty()) return false;
                    return events.getData().stream().allMatch(e ->
                            AgentAppEventStatus.FINISHED.equals(e.getStatus()) || AgentAppEventStatus.ERROR.equals(e.getStatus()));
                });
    }

    // --- DinD container verification helpers ---

    protected void awaitContainersRunning(String projectName, long expectedCount) {
        Awaitility.await("containers running in project " + projectName)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> dockerVerifier.countRunningContainers(projectName) >= expectedCount);
    }

    protected void awaitContainersRemoved(String projectName) {
        Awaitility.await("containers removed from project " + projectName)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> !dockerVerifier.projectExists(projectName));
    }

    protected void awaitApplicationRemoved(AgentApplicationId appId, String projectName) {
        Awaitility.await("Application is removed" + projectName)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> getAgentApplicationById(appId).isEmpty());
    }

    protected Optional<JsonNode> getComposeTemplateByName(AgentAppTemplate template, String composeTemplate) {
        return template.getStartSteps().stream()
                .filter(s -> s.getType() == AgentAppStepType.COMPOSE_TEMPLATE)
                .findFirst()
                .map(s -> (ComposeTypeChoiceStep) s)
                .map(s -> s.getComposeTemplates().get(composeTemplate));
    }

}
