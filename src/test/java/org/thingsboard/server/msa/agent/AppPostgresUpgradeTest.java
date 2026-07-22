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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventStatus;
import org.thingsboard.server.common.data.agent.AgentAppEvent;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.id.AgentApplicationId;
import org.thingsboard.server.msa.AbstractContainerTest;
import org.thingsboard.server.msa.DockerVerifier.ExecResult;

import java.util.concurrent.TimeUnit;

/**
 * Verifies the agent's automatic PostgreSQL major-version migration during a compose UPDATE.
 * A PostgreSQL data directory is binary-incompatible across major versions, so switching a
 * postgres service image (15 -> 16) must trigger the agent-side migration: backup of the data
 * volume, pg_dumpall with the old image, restore with the new image — with the data surviving.
 * A downgrade (16 -> 15) must be rejected and rolled back, leaving the original service running.
 */
@Slf4j
public class AppPostgresUpgradeTest extends AbstractContainerTest {

    private static final String PG_SERVICE = "postgres";
    private static final String PG_VOLUME_KEY = "pg-data";
    private static final String PG_PASSWORD = "pgUpgradeTestPw";
    private static final String PG_DB = "testdb";
    // Pulling postgres images into DinD plus dump/restore takes longer than the default 120 s event wait.
    private static final long EVENT_TIMEOUT_SEC = 300;

    @Test
    public void testPostgresMajorUpgradeMigratesDataOnUpdate() {
        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = getComposeTemplateByName(template, "default")
                .orElseThrow(() -> new AssertionError("Generic template has no 'default' compose"));
        addPostgresService(compose, "postgres:15");

        AgentApplication app = null;
        String projectName = null;
        try {
            app = installDockerComposeApp(template, compose);
            awaitEventTerminal(app.getId(), AgentAppEventStatus.FINISHED);

            projectName = getProjectName(app.getId());
            awaitContainersRunning(projectName, 2);
            Assert.assertTrue("postgres:15 container should be running after install",
                    dockerVerifier.hasRunningContainerWithImage(projectName, "postgres:15"));

            awaitPostgresReady(projectName);
            ExecResult seed = psql(projectName,
                    "CREATE TABLE migration_marker(val int); INSERT INTO migration_marker VALUES (42);");
            Assert.assertEquals("Seeding test data should succeed: " + seed.output(), 0, seed.exitCode());

            // Bump the postgres major and trigger UPDATE: the agent must migrate the data
            switchPostgresImage(app.getId(), "postgres:16");
            createAppEvent(app.getId(), AgentAppEventActionType.UPDATE);
            awaitEventTerminal(app.getId(), AgentAppEventStatus.FINISHED);

            awaitContainersRunning(projectName, 2);
            Assert.assertTrue("postgres:16 container should be running after upgrade",
                    dockerVerifier.hasRunningContainerWithImage(projectName, "postgres:16"));
            Assert.assertFalse("postgres:15 container should no longer be running",
                    dockerVerifier.hasRunningContainerWithImage(projectName, "postgres:15"));

            awaitPostgresReady(projectName);
            ExecResult dataVersion = dockerVerifier.execInContainer(projectName, PG_SERVICE,
                    "cat", "/var/lib/postgresql/data/PG_VERSION");
            Assert.assertEquals("Data directory should be on the new major after migration",
                    "16", dataVersion.output().trim());

            ExecResult select = psql(projectName, "SELECT val FROM migration_marker;");
            Assert.assertEquals("Seeded row should survive the migration: " + select.output(),
                    0, select.exitCode());
            Assert.assertTrue("Seeded row should survive the migration, got: " + select.output(),
                    select.output().contains("42"));

            Assert.assertTrue("Pre-migration backup volume should be retained",
                    dockerVerifier.hasVolumeWithPrefix(projectName + "_" + PG_VOLUME_KEY + "-backup-"));
            Assert.assertFalse("Migration scratch volume should be removed after success",
                    dockerVerifier.hasVolumeWithPrefix("tb-pg-migrate-"));
        } finally {
            deleteAppQuietly(app, projectName);
        }
    }

    @Test
    public void testPostgresDowngradeIsRejectedAndRolledBack() {
        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = getComposeTemplateByName(template, "default")
                .orElseThrow(() -> new AssertionError("Generic template has no 'default' compose"));
        addPostgresService(compose, "postgres:16");

        AgentApplication app = null;
        String projectName = null;
        try {
            app = installDockerComposeApp(template, compose);
            awaitEventTerminal(app.getId(), AgentAppEventStatus.FINISHED);

            projectName = getProjectName(app.getId());
            awaitContainersRunning(projectName, 2);
            awaitPostgresReady(projectName);
            ExecResult seed = psql(projectName,
                    "CREATE TABLE migration_marker(val int); INSERT INTO migration_marker VALUES (42);");
            Assert.assertEquals("Seeding test data should succeed: " + seed.output(), 0, seed.exitCode());

            // Attempt a major downgrade: the agent must fail the step and roll back
            switchPostgresImage(app.getId(), "postgres:15");
            createAppEvent(app.getId(), AgentAppEventActionType.UPDATE);
            awaitEventTerminal(app.getId(), AgentAppEventStatus.ERROR);

            AgentAppEvent event = getLatestEvent(app.getId());
            Assert.assertNotNull("Update event should exist", event);
            Assert.assertTrue("Event error should explain the rejected downgrade, got: " + event.getErrorMessage(),
                    event.getErrorMessage() != null && event.getErrorMessage().contains("downgrade is not supported"));

            // Rollback must have restored the original containers with data intact
            awaitContainersRunning(projectName, 2);
            Assert.assertTrue("postgres:16 container should be running again after rollback",
                    dockerVerifier.hasRunningContainerWithImage(projectName, "postgres:16"));
            awaitPostgresReady(projectName);
            ExecResult select = psql(projectName, "SELECT val FROM migration_marker;");
            Assert.assertTrue("Data should be intact after rejected downgrade, got: " + select.output(),
                    select.exitCode() == 0 && select.output().contains("42"));
        } finally {
            deleteAppQuietly(app, projectName);
        }
    }

    private void addPostgresService(JsonNode compose, String image) {
        ObjectNode composeNode = (ObjectNode) compose;
        ObjectNode postgres = ((ObjectNode) composeNode.get("services")).putObject(PG_SERVICE);
        postgres.put("image", image);
        ObjectNode env = postgres.putObject("environment");
        env.put("POSTGRES_PASSWORD", PG_PASSWORD);
        env.put("POSTGRES_DB", PG_DB);
        postgres.putArray("volumes").add(PG_VOLUME_KEY + ":/var/lib/postgresql/data");
        ObjectNode volumes = composeNode.has("volumes")
                ? (ObjectNode) composeNode.get("volumes")
                : composeNode.putObject("volumes");
        volumes.putObject(PG_VOLUME_KEY);
    }

    private void switchPostgresImage(AgentApplicationId appId, String image) {
        AgentApplication appForUpdate = cloudRestClient.getAgentApplicationById(appId)
                .orElseThrow(() -> new AssertionError("App not found"));
        DockerComposeConfig config = (DockerComposeConfig) appForUpdate.getConfig();
        JsonNode composeNode = config.getCompose();
        ((ObjectNode) composeNode.get("services").get(PG_SERVICE)).put("image", image);
        config.setCompose(composeNode);
        cloudRestClient.updateAgentApplication(appForUpdate);
    }

    private ExecResult psql(String projectName, String sql) {
        return dockerVerifier.execInContainer(projectName, PG_SERVICE,
                "psql", "-U", "postgres", "-d", PG_DB, "-tAc", sql);
    }

    private void awaitPostgresReady(String projectName) {
        Awaitility.await("postgres accepting connections in " + projectName)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> dockerVerifier.execInContainer(projectName, PG_SERVICE,
                        "pg_isready", "-U", "postgres").exitCode() == 0);
    }

    /**
     * Waits for the newest event to reach a terminal status and asserts which one.
     * The shared awaitEventStatus helper caps at 120 s — too tight for postgres image
     * pulls into DinD plus the dump/restore migration.
     */
    private void awaitEventTerminal(AgentApplicationId appId, AgentAppEventStatus expected) {
        Awaitility.await("event terminal status " + expected)
                .pollInterval(2, TimeUnit.SECONDS)
                .atMost(EVENT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .until(() -> {
                    AgentAppEventStatus status = getLatestEventStatus(appId);
                    return AgentAppEventStatus.FINISHED.equals(status) || AgentAppEventStatus.ERROR.equals(status);
                });
        AgentAppEventStatus actual = getLatestEventStatus(appId);
        if (!expected.equals(actual)) {
            AgentAppEvent event = getLatestEvent(appId);
            throw new AssertionError("Expected event status " + expected + " but got " + actual
                    + (event != null && event.getErrorMessage() != null ? " (error: " + event.getErrorMessage() + ")" : ""));
        }
    }
}
