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

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.rules.ExternalResource;
import org.testcontainers.utility.Base58;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.thingsboard.server.msa.AbstractContainerTest.AGENT_CONFIGURATION;
import static org.thingsboard.server.msa.AbstractContainerTest.TEST_CONFIGURATION;
import static org.thingsboard.server.msa.config.TestConfiguration.DOCKER_SOURCE_DIR;

@Slf4j
public class ThingsBoardDbInstaller extends ExternalResource {

    private static final String POSTGRES_DATA_VOLUME = "tb-agent-postgres-test-data-volume";
    private static final String TB_LOG_VOLUME = "tb-agent-log-test-volume";

    private final DockerComposeExecutor dockerCompose;

    private final String postgresDataVolume;
    private final String tbLogVolume;

    @Getter
    private final Map<String, String> env;

    public ThingsBoardDbInstaller() {
        String identifier = Base58.randomString(6).toLowerCase();
        String project = identifier + Base58.randomString(6).toLowerCase();

        postgresDataVolume = project + "_" + POSTGRES_DATA_VOLUME;
        tbLogVolume = project + "_" + TB_LOG_VOLUME;

        dockerCompose = new DockerComposeExecutor(TEST_CONFIGURATION.getComposeFiles(), project);

        Dotenv dotenv = Dotenv.configure().directory(DOCKER_SOURCE_DIR).filename(".env").load();
        env = new HashMap<>();
        for (DotenvEntry entry : dotenv.entries()) {
            env.put(entry.getKey(), entry.getValue());
        }
        env.put("POSTGRES_DATA_VOLUME", postgresDataVolume);
        env.put("TB_LOG_VOLUME", tbLogVolume);
        env.put("AGENT_ROUTING_KEY", AGENT_CONFIGURATION.getRoutingKey());
        env.put("AGENT_ROUTING_SECRET", AGENT_CONFIGURATION.getRoutingSecret());

        dockerCompose.withEnv(env);
    }

    @Override
    protected void before() throws Throwable {
        initAgentTemplatesGitRepo();

        dockerCompose.withCommand("volume create " + postgresDataVolume);
        dockerCompose.invokeDocker();

        dockerCompose.withCommand("volume create " + tbLogVolume);
        dockerCompose.invokeDocker();

        try {
            dockerCompose.withCommand("up -d postgres");
            dockerCompose.invokeCompose();

            dockerCompose.withCommand("run --no-deps --rm -e INSTALL_TB=true -e LOAD_DEMO=true tb-monolith");
            dockerCompose.invokeCompose();
        } finally {
            try {
                dockerCompose.withCommand("down -v");
                dockerCompose.invokeCompose();
            } catch (Exception e) {
                log.error("Failed [before] cleanup", e);
            }
        }
    }

    @Override
    protected void after() {
        try {
            copyLogs(tbLogVolume, "./target/tb-logs/");
            dockerCompose.withCommand("volume rm -f " + postgresDataVolume + " " + tbLogVolume);
            dockerCompose.invokeDocker();
        } catch (Exception e) {
            log.error("Failed [after]", e);
            throw e;
        }
    }

    /**
     * Initializes the agent-templates directory as a git repository so that
     * AgentAppTemplateSyncService can read it via file:///agent-templates URI.
     */
    private void initAgentTemplatesGitRepo() {
        File templatesDir = new File(DOCKER_SOURCE_DIR + "agent-templates");
        if (!templatesDir.exists()) {
            log.warn("agent-templates directory not found: {}", templatesDir.getAbsolutePath());
            return;
        }
        File gitDir = new File(templatesDir, ".git");
        if (gitDir.exists()) {
            log.info("agent-templates git repo already initialized, re-committing changes");
            runGitCommand(templatesDir, "add", "-A");
            runGitCommandOptional(templatesDir, "commit", "--allow-empty", "-m", "update templates");
        } else {
            log.info("Initializing agent-templates as git repo at {}", templatesDir.getAbsolutePath());
            runGitCommand(templatesDir, "init", "-b", "main");
            runGitCommand(templatesDir, "config", "user.email", "test@thingsboard.io");
            runGitCommand(templatesDir, "config", "user.name", "TB Test");
            runGitCommand(templatesDir, "add", "-A");
            runGitCommand(templatesDir, "commit", "-m", "init agent templates");
        }
    }

    private void runGitCommand(File directory, String... args) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            new ProcessExecutor().command(command)
                    .directory(directory)
                    .redirectOutput(Slf4jStream.of(log).asInfo())
                    .redirectError(Slf4jStream.of(log).asError())
                    .exitValueNormal()
                    .executeNoTimeout();
        } catch (Exception e) {
            throw new RuntimeException("git command failed: " + String.join(" ", args), e);
        }
    }

    private void runGitCommandOptional(File directory, String... args) {
        try {
            runGitCommand(directory, args);
        } catch (Exception e) {
            log.debug("Optional git command failed (ignored): {}", e.getMessage());
        }
    }

    private void copyLogs(String volumeName, String targetDir) {
        try {
            new File(targetDir).mkdirs();
            String containerName = "tb-agent-logs-" + Base58.randomString(8);
            dockerCompose.withCommand("run -d --rm --name " + containerName + " -v " + volumeName + ":/root alpine tail -f /dev/null");
            dockerCompose.invokeDocker();
            dockerCompose.withCommand("cp " + containerName + ":/root/. " + new File(targetDir).getAbsolutePath());
            dockerCompose.invokeDocker();
            dockerCompose.withCommand("rm -f " + containerName);
            dockerCompose.invokeDocker();
        } catch (Exception e) {
            log.error("Failed to copy logs from volume {}", volumeName, e);
        }
    }
}
