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

import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.concurrent.TimeUnit;

@Slf4j
public class StateSyncTest extends AbstractContainerTest {

    @Test
    public void testAgentSyncsExternalProjectToCloud() {
        String projectName = "statesynctest-" + System.currentTimeMillis();
        String serviceName = "hello";
        String image = "nginx:alpine";

        // Create a compose project directly in DinD (not via the cloud API)
        dockerVerifier.createStubComposeProject(projectName, serviceName, image);

        try {
            // The agent should discover this project and sync it as an AgentApplication
            Awaitility.await("agent app synced for external project")
                    .pollInterval(5, TimeUnit.SECONDS)
                    .atMost(180, TimeUnit.SECONDS)
                    .until(() -> {
                        PageData<AgentApplication> apps = cloudRestClient.getAgentApplicationsByAgentId(agent.getId(), new PageLink(100));
                        if (apps == null || apps.getData().isEmpty()) return false;
                        return apps.getData().stream()
                                .anyMatch(app -> projectName.equals(app.getProjectName()));
                    });

            // Verify the synced application
            AgentApplication syncedApp = cloudRestClient.getAgentApplicationsByAgentId(agent.getId(), new PageLink(100))
                    .getData().stream()
                    .filter(app -> projectName.equals(app.getProjectName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Synced app not found"));

            Assert.assertNotNull("Synced app should have an ID", syncedApp.getId());
            Assert.assertEquals("Project name should match", projectName, syncedApp.getProjectName());

            log.info("Agent synced external project '{}' as app {}", projectName, syncedApp.getId());
        } finally {
            // Cleanup: remove the project from DinD
            dockerVerifier.removeComposeProject(projectName);
        }
    }
}
