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
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventRequest;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.Optional;

@Slf4j
public class AppInstallTest extends AbstractContainerTest {

    @Test
    public void testInstallCreatesContainersInDind() {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        AgentApplication app = installDockerComposeApp(template, compose.get());

        Assert.assertNotNull("Installed app should have an ID", app.getId());
        log.info("Installed app: {}", app.getId());

        // Wait for event to finish
        awaitEventFinished(app.getId());

        // Get project name from REST API (read-only field set by server)
        String projectName = getProjectName(app.getId());
        Assert.assertNotNull("Project name should be set by server", projectName);
        log.info("Agent project name: {}", projectName);

        // Verify container is running inside DinD
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Container should be running in DinD", dockerVerifier.countRunningContainers(projectName) >= 1);

        // Cleanup
        cloudRestClient.createAgentAppEvent(app.getId(), buildRequest(app, AgentAppEventActionType.DELETE));
        awaitApplicationRemoved(app.getId(), projectName);

        // Verify containers removed from DinD
        awaitContainersRemoved(projectName);
        Assert.assertFalse("No containers should remain after delete", dockerVerifier.projectExists(projectName));
    }

    private AgentAppEventRequest buildRequest(
            AgentApplication app,
            AgentAppEventActionType actionType) {
        AgentAppEventRequest request = new AgentAppEventRequest();
        request.setActionType(actionType);
        request.setApplication(app);
        return request;
    }
}
