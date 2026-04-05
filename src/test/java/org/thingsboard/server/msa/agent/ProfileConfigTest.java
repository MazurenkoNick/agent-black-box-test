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
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventRequest;
import org.thingsboard.server.common.data.agent.AgentAppProfile;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.AgentApplicationType;
import org.thingsboard.server.common.data.agent.AgentBulkAction;
import org.thingsboard.server.common.data.agent.AgentBulkActionStatus;
import org.thingsboard.server.common.data.agent.AgentGroup;
import org.thingsboard.server.common.data.agent.BulkOperationRequest;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.common.data.id.AgentAppProfileId;
import org.thingsboard.server.common.data.id.AgentGroupId;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.Optional;

/**
 * Black-box tests for profile-managed application configuration.
 * <p>
 * Verifies that:
 * - Installing an app with a profileId copies the profile's config onto the app
 * - Bulk UPDATE pushes the profile's current config to all apps in a group
 * - Direct config edits are blocked on profile-managed apps
 */
@Slf4j
public class ProfileConfigTest extends AbstractContainerTest {

    @Test
    public void testInstallWithProfileInheritsProfileConfig() {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        Assert.assertTrue("Template should have a 'default' compose", compose.isPresent());

        // Create profile with the template's config
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName("test-profile-" + System.currentTimeMillis());
        profile.setAppType(AgentApplicationType.GENERIC);
        profile.setTemplateId(template.getId());
        DockerComposeConfig profileConfig = new DockerComposeConfig();
        profileConfig.setCompose(compose.get());
        profile.setConfig(profileConfig);
        profile = cloudRestClient.saveAgentAppProfile(profile);
        AgentAppProfileId profileId = profile.getId();
        log.info("Created profile: {}", profileId);

        AgentApplication installed = null;
        String projectName = null;
        try {
            // Install app with profileId — should inherit profile's config
            AgentApplication app = AgentApplication.fromTemplate(template);
            app.setAgentId(agent.getId());
            app.setName("profile-app-" + System.currentTimeMillis());
            app.setApplicationProfileId(profileId);
            // Don't set config — it should come from the profile

            AgentAppEventRequest request = new AgentAppEventRequest();
            request.setActionType(AgentAppEventActionType.INSTALL);
            request.setApplication(app);
            installed = cloudRestClient.installAgentApp(request);

            Assert.assertNotNull("Installed app should have an ID", installed.getId());
            awaitEventFinished(installed.getId());
            projectName = getProjectName(installed.getId());

            // Verify the app got the profile's config
            AgentApplication fetched = getAgentApplicationById(installed.getId())
                    .orElseThrow(() -> new AssertionError("App not found after install"));
            Assert.assertNotNull("App should have config from profile", fetched.getConfig());
            Assert.assertTrue("Config should be DockerComposeConfig", fetched.getConfig() instanceof DockerComposeConfig);
            DockerComposeConfig appConfig = (DockerComposeConfig) fetched.getConfig();
            Assert.assertNotNull("Compose should not be null", appConfig.getCompose());

            // Verify containers are running
            awaitContainersRunning(projectName, 1);
        } finally {
            if (installed != null) {
                createAppEvent(installed.getId(), AgentAppEventActionType.DELETE);
                awaitApplicationRemoved(installed.getId(), projectName);
                awaitContainersRemoved(projectName);
            }
            cloudRestClient.deleteAgentAppProfile(profileId);
        }
    }

    @Test
    public void testBulkUpdatePushesProfileConfigToApps() {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");
        Assert.assertTrue("Template should have a 'default' compose", compose.isPresent());

        // Create profile
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName("bulk-profile-" + System.currentTimeMillis());
        profile.setAppType(AgentApplicationType.GENERIC);
        profile.setTemplateId(template.getId());
        DockerComposeConfig profileConfig = new DockerComposeConfig();
        profileConfig.setCompose(compose.get());
        profile.setConfig(profileConfig);
        profile = cloudRestClient.saveAgentAppProfile(profile);
        AgentAppProfileId profileId = profile.getId();

        // Create group and assign profile
        AgentGroup group = new AgentGroup();
        group.setName("bulk-group-" + System.currentTimeMillis());
        group = cloudRestClient.saveAgentGroup(group);
        AgentGroupId groupId = group.getId();
        cloudRestClient.assignProfileToGroup(groupId, profileId);

        // Assign agent to group
        agent.setAgentGroupId(groupId);
        agent = cloudRestClient.saveAgent(agent);

        log.info("Created profile: {}, group: {}", profileId, groupId);

        AgentApplication installed = null;
        String projectName = null;
        try {
            // Install app with profileId
            AgentApplication app = AgentApplication.fromTemplate(template);
            app.setAgentId(agent.getId());
            app.setName("bulk-app-" + System.currentTimeMillis());
            app.setApplicationProfileId(profileId);

            AgentAppEventRequest installRequest = new AgentAppEventRequest();
            installRequest.setActionType(AgentAppEventActionType.INSTALL);
            installRequest.setApplication(app);
            installed = cloudRestClient.installAgentApp(installRequest);
            awaitEventFinished(installed.getId());
            projectName = getProjectName(installed.getId());
            awaitContainersRunning(projectName, 1);

            // Now run bulk UPDATE — should push profile config to the app
            BulkOperationRequest bulkRequest = new BulkOperationRequest();
            bulkRequest.setActionType(AgentAppEventActionType.UPDATE);

            AgentBulkAction bulkAction = cloudRestClient.bulkOperation(groupId, profileId, bulkRequest, false);
            Assert.assertNotNull("Bulk action should not be null", bulkAction);
            Assert.assertNotNull("Bulk action should have an ID", bulkAction.getId());
            Assert.assertEquals("Bulk action should be QUEUED initially",
                    AgentBulkActionStatus.QUEUED, bulkAction.getStatus());

            AgentBulkAction completed = awaitBulkActionCompleted(bulkAction.getId().getId());
            Assert.assertEquals("Bulk action should be COMPLETED", AgentBulkActionStatus.COMPLETED, completed.getStatus());
            Assert.assertEquals("Should have 1 total app", 1, completed.getTotal());
            Assert.assertEquals("Should have 1 submitted app", 1, completed.getSubmitted());
            Assert.assertTrue("Should have no skip counts",
                    completed.getSkipCounts() == null || completed.getSkipCounts().isEmpty());

            // Wait for all events (install + bulk update) to finish
            awaitAllEventsFinished(installed.getId());

            // Verify containers are still running after update
            Assert.assertTrue("Container should still be running after bulk update",
                    dockerVerifier.countRunningContainers(projectName) >= 1);
        } finally {
            if (installed != null) {
                // Wait for any in-flight events (e.g. bulk UPDATE) to finish before sending DELETE
                awaitAllEventsFinished(installed.getId());
                createAppEvent(installed.getId(), AgentAppEventActionType.DELETE);
                awaitApplicationRemoved(installed.getId(), projectName);
                awaitContainersRemoved(projectName);
            }

            // Unassign agent from group
            agent.setAgentGroupId(null);
            agent = cloudRestClient.saveAgent(agent);

            cloudRestClient.deleteAgentGroup(groupId);
            if (installed == null) {
                cloudRestClient.deleteAgentAppProfile(profileId);
            }
            // Profile can only be deleted after apps referencing it are gone
        }
    }

    @Test
    public void testDirectConfigUpdateBlockedForProfileManagedApp() {
        AgentAppTemplate template = getLatestGenericTemplate();
        Optional<JsonNode> compose = getComposeTemplateByName(template, "default");

        // Create profile
        AgentAppProfile profile = new AgentAppProfile();
        profile.setName("readonly-profile-" + System.currentTimeMillis());
        profile.setAppType(AgentApplicationType.GENERIC);
        profile.setTemplateId(template.getId());
        DockerComposeConfig profileConfig = new DockerComposeConfig();
        profileConfig.setCompose(compose.get());
        profile.setConfig(profileConfig);
        profile = cloudRestClient.saveAgentAppProfile(profile);
        AgentAppProfileId profileId = profile.getId();

        AgentApplication installed = null;
        String projectName = null;
        try {
            // Install app with profileId
            AgentApplication app = AgentApplication.fromTemplate(template);
            app.setAgentId(agent.getId());
            app.setName("readonly-app-" + System.currentTimeMillis());
            app.setApplicationProfileId(profileId);

            AgentAppEventRequest installRequest = new AgentAppEventRequest();
            installRequest.setActionType(AgentAppEventActionType.INSTALL);
            installRequest.setApplication(app);
            installed = cloudRestClient.installAgentApp(installRequest);
            awaitEventFinished(installed.getId());
            projectName = getProjectName(installed.getId());

            // Try to update config directly — should fail
            AgentApplication fetched = getAgentApplicationById(installed.getId())
                    .orElseThrow(() -> new AssertionError("App not found"));

            DockerComposeConfig newConfig = new DockerComposeConfig();
            newConfig.setCompose(JsonNodeFactory.instance.objectNode()); // different from profile's compose
            fetched.setConfig(newConfig);

            Assert.assertThrows(Exception.class, () -> cloudRestClient.updateAgentApplication(fetched));
        } finally {
            if (installed != null) {
                createAppEvent(installed.getId(), AgentAppEventActionType.DELETE);
                awaitApplicationRemoved(installed.getId(), projectName);
                awaitContainersRemoved(projectName);
            }
            cloudRestClient.deleteAgentAppProfile(profileId);
        }
    }
}
