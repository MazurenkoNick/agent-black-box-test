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
import org.junit.Test;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.msa.AbstractContainerTest;
import org.thingsboard.server.msa.ContainerTestSuite;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.msa.config.TestConfiguration.TB_AGENT_SERVICE_NAME;

@Slf4j
public class ReconnectTest extends AbstractContainerTest {

    @Test
    public void testAgentReconnectsAndCompletesEvent() throws Exception {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        AgentApplication app = installDockerComposeApp(template, compose.get());
        awaitEventFinished(app.getId());

        String projectName = getProjectName(app.getId());
        awaitContainersRunning(projectName, 1);

        // Bounce the tb-agent service to simulate reconnection
        log.info("Bouncing tb-agent service to test reconnection...");
        ContainerTestSuite.testContainer.getContainerByServiceName(TB_AGENT_SERVICE_NAME)
                .ifPresent(container -> {
                    container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
                });

        // Wait a bit for the agent to reconnect (it retries with 2s backoff)
        Awaitility.await("agent reconnects")
                .pollDelay(3, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(60, TimeUnit.SECONDS)
                .until(() -> true); // just wait for reconnect window

        // Create a new event after reconnect
        createAppEvent(app.getId(), AgentAppEventActionType.RESTART);
        awaitEventFinished(app.getId());

        // Verify containers still running
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Container should be running after agent reconnect and restart",
                dockerVerifier.countRunningContainers(projectName) >= 1);

        // Cleanup
        createAppEvent(app.getId(), AgentAppEventActionType.DELETE);
        awaitApplicationRemoved(app.getId(), projectName);

        // Verify containers removed from DinD
        awaitContainersRemoved(projectName);
        Assert.assertFalse("No containers should remain after delete", dockerVerifier.projectExists(projectName));
    }
}
