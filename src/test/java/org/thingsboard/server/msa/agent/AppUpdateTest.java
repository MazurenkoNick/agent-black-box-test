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
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.Optional;

@Slf4j
public class AppUpdateTest extends AbstractContainerTest {

    @Test
    public void testUpdateRedeploysContainersInDind() {
        // Install with 1.0.0 (nginx:alpine)
        AgentAppTemplate template100 = getGenericTemplate("1.0.0");
        Optional<JsonNode> compose = getComposeTemplateByName(template100, "default");
        AgentApplication app = installDockerComposeApp(template100, compose.get());
        awaitEventFinished(app.getId());

        String projectName = getProjectName(app.getId());
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Container with nginx:alpine should be running",
                dockerVerifier.hasRunningContainerWithImage(projectName, "nginx:alpine"));

        // Update app (UPDATE action reuses existing app with potentially new config)
        createAppEvent(app.getId(), AgentAppEventActionType.UPDATE);
        awaitEventFinished(app.getId());

        // Container should still be running after update
        awaitContainersRunning(projectName, 1);
        Assert.assertTrue("Container should still be running after update",
                dockerVerifier.countRunningContainers(projectName) >= 1);

        // Cleanup
        createAppEvent(app.getId(), AgentAppEventActionType.DELETE);
        awaitApplicationRemoved(app.getId(), projectName);

        // Verify containers removed from DinD
        awaitContainersRemoved(projectName);
        Assert.assertFalse("No containers should remain after delete", dockerVerifier.projectExists(projectName));
    }
}
