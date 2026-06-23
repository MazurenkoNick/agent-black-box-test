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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.client.HttpStatusCodeException;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.List;

@Slf4j
public class AppVolumeBindTest extends AbstractContainerTest {

    // Relative bind sources the server must reject. Covers the leading-dot/tilde forms and,
    // crucially, separator-carrying sources without a leading marker ("data/logs", "sub/../../etc")
    // that Docker still resolves relative to the project dir but a naive check would treat as a
    // named volume. Mirrors DockerComposeUtils.isRelativeHostPath / the agent's resolveVolumeBinds.
    private static final List<String> RELATIVE_SOURCES = List.of(
            "./data:/var/log/app",
            "../data:/var/log/app",
            "~/data:/var/log/app",
            "data/logs:/var/log/app",
            "sub/../../etc:/host-etc"
    );

    @Test
    public void testInstallRejectsRelativeVolumePaths() {
        AgentAppTemplate template = getLatestGenericTemplate();

        for (String relativeBind : RELATIVE_SOURCES) {
            JsonNode compose = defaultCompose(template);
            addVolumeToFirstService(compose, relativeBind);

            AgentApplication created = null;
            try {
                created = installDockerComposeApp(template, compose);
                Assert.fail("Install must be rejected for relative volume bind '" + relativeBind
                        + "', but the app was created: " + created.getId());
            } catch (Exception e) {
                String details = errorDetails(e);
                log.info("Relative bind '{}' rejected as expected: {}", relativeBind, details);
                Assert.assertTrue("Expected a relative-volume validation error for '" + relativeBind
                        + "', got: " + details, details.contains("relative paths in volumes"));
            } finally {
                // No-op on the rejection path (created == null); guards against a validation regression.
                deleteAppQuietly(created, null);
            }
        }
    }

    @Test
    public void testInstallAcceptsAbsoluteVolumeBind() {
        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = defaultCompose(template);
        // Docker auto-creates the host directory for an absolute bind mount in DinD.
        addVolumeToFirstService(compose, "/tmp/agent-app-vol-" + System.currentTimeMillis() + ":/var/log/app");
        assertInstallSucceedsAndRuns(template, compose);
    }

    @Test
    public void testInstallAcceptsNamedVolumeBind() {
        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = defaultCompose(template);
        String volume = "appdata" + System.currentTimeMillis();
        addVolumeToFirstService(compose, volume + ":/var/log/app");
        declareManagedVolume(compose, volume);
        assertInstallSucceedsAndRuns(template, compose);
    }

    private void assertInstallSucceedsAndRuns(AgentAppTemplate template, JsonNode compose) {
        AgentApplication app = null;
        String projectName = null;
        try {
            app = installDockerComposeApp(template, compose);
            Assert.assertNotNull("Installed app should have an ID", app.getId());
            awaitEventFinished(app.getId());

            projectName = getProjectName(app.getId());
            Assert.assertNotNull("Project name should be set by server", projectName);

            awaitContainersRunning(projectName, 1);
            Assert.assertTrue("Container should be running in DinD",
                    dockerVerifier.countRunningContainers(projectName) >= 1);

            createAppEvent(app.getId(), AgentAppEventActionType.DELETE);
            awaitApplicationRemoved(app.getId(), projectName);
            awaitContainersRemoved(projectName);
            Assert.assertFalse("No containers should remain after delete", dockerVerifier.projectExists(projectName));
        } finally {
            deleteAppQuietly(app, projectName);
        }
    }

    private JsonNode defaultCompose(AgentAppTemplate template) {
        return getComposeTemplateByName(template, "default")
                .orElseThrow(() -> new IllegalStateException("No 'default' compose template found"))
                .deepCopy();
    }

    private void addVolumeToFirstService(JsonNode compose, String bind) {
        ObjectNode services = (ObjectNode) compose.get("services");
        String serviceName = services.fieldNames().next();
        ObjectNode service = (ObjectNode) services.get(serviceName);
        JsonNode existing = service.get("volumes");
        ArrayNode volumes = existing instanceof ArrayNode array ? array : service.putArray("volumes");
        volumes.add(bind);
    }

    private void declareManagedVolume(JsonNode compose, String name) {
        JsonNode existing = compose.get("volumes");
        ObjectNode volumes = existing instanceof ObjectNode object ? object : ((ObjectNode) compose).putObject("volumes");
        volumes.putNull(name);
    }

    private String errorDetails(Exception e) {
        if (e instanceof HttpStatusCodeException httpError) {
            return httpError.getStatusCode() + " " + httpError.getResponseBodyAsString();
        }
        return String.valueOf(e.getMessage());
    }

}
