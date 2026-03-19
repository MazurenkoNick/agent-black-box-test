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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.server.common.data.agent.AgentAppEvent;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventRequest;
import org.thingsboard.server.common.data.agent.AgentAppEventStatus;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RollbackTest extends AbstractContainerTest {

    @Test
    public void testRollbackOnFailedUpdate() {
        // Install app with 1.0.0 (valid nginx:alpine)
        AgentAppTemplate template
                = getGenericTemplate("1.0.0");
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        AgentApplication app = installDockerComposeApp(template, compose.get());
        awaitEventFinished(app.getId());

        String projectName = getProjectName(app.getId());
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Original container should be running before update",
                dockerVerifier.hasRunningContainerWithImage(projectName, "nginx:alpine"));

        AgentApplication appForUpdate = cloudRestClient.getAgentApplicationById(app.getId())
                .orElseThrow(() -> new AssertionError("App not found"));

        if (appForUpdate.getConfig() instanceof DockerComposeConfig dc) {
            String originalCompose = dc.getCompose() != null ? dc.getCompose().toString() : "{}";
            String brokenCompose = originalCompose.replace("nginx:alpine", "nginx:this-tag-does-not-exist-xyz");
            dc.setCompose(JsonNodeFactory.instance.textNode(brokenCompose));
        }

        AgentAppEventRequest updateRequest = new AgentAppEventRequest();
        updateRequest.setActionType(AgentAppEventActionType.UPDATE);
        cloudRestClient.updateAgentApplication(appForUpdate);
        cloudRestClient.createAgentAppEvent(app.getId(), updateRequest);

        // Wait for event to either error or finish (rollback should trigger automatically)
        Awaitility.await("update event completes or errors")
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(180, TimeUnit.SECONDS)
                .until(() -> {
                    PageData<AgentAppEvent> events = cloudRestClient.getAgentAppEvents(app.getId(), new PageLink(1));
                    if (events == null || events.getData().isEmpty()) return false;
                    AgentAppEventStatus status = events.getData().get(0).getStatus();
                    return AgentAppEventStatus.FINISHED.equals(status) || AgentAppEventStatus.ERROR.equals(status);
                });

        // Original container with nginx:alpine should still be running (rollback succeeded)
        Assert.assertTrue("Original container should still be running after failed update/rollback",
                dockerVerifier.hasRunningContainerWithImage(projectName, "nginx:alpine"));

        // Cleanup
        createAppEvent(app.getId(), AgentAppEventActionType.DELETE);
        awaitContainersRemoved(projectName);
    }
}
