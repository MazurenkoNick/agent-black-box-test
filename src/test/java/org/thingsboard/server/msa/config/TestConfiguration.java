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
package org.thingsboard.server.msa.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

@Slf4j
public class TestConfiguration {

    public static final String TB_MONOLITH_SERVICE_NAME = "tb-monolith";
    public static final String TB_KAFKA_SERVICE_NAME = "kafka";
    public static final String TB_AGENT_SERVICE_NAME = "tb-agent";
    public static final String DIND_SERVICE_NAME = "dind";
    public static final String DOCKER_SOURCE_DIR = "./docker-agent/";

    @Getter
    private TBConfiguration tbConfiguration;
    @Getter
    private AgentConfiguration agentConfiguration;
    @Getter
    private List<File> composeFiles;

    public TestConfiguration() {
        loadTBConfiguration();
        loadAgentConfiguration();
        createComposeFiles();
    }

    private void createComposeFiles() {
        composeFiles = List.of(
                new File(DOCKER_SOURCE_DIR + "docker-compose.yml"),
                new File(DOCKER_SOURCE_DIR + "docker-compose.postgres.yml"),
                new File(DOCKER_SOURCE_DIR + "docker-compose.volumes.yml")
        );
    }

    private void loadTBConfiguration() {
        Properties properties = new Properties();
        try (InputStream inputStream = TestConfiguration.class.getResourceAsStream("/tbConfig.properties")) {
            properties.load(inputStream);
            String host = properties.getProperty("tb.cloudRpcHost");
            int port = Integer.parseInt(properties.getProperty("tb.cloudRpcPort"));
            String email = properties.getProperty("tb.email");
            String password = properties.getProperty("tb.password");
            tbConfiguration = new TBConfiguration(host, port, email, password);
            log.info("Loaded TB configuration");
        } catch (IOException e) {
            log.error("Error loading TB configuration", e);
        }
    }

    private void loadAgentConfiguration() {
        Properties properties = new Properties();
        try (InputStream inputStream = TestConfiguration.class.getResourceAsStream("/agentConfig.properties")) {
            properties.load(inputStream);
            String routingKey = properties.getProperty("agent.routingKey");
            String routingSecret = properties.getProperty("agent.routingSecret");
            agentConfiguration = new AgentConfiguration(routingKey, routingSecret);
            log.info("Loaded Agent configuration");
        } catch (IOException e) {
            log.error("Error loading Agent configuration", e);
        }
    }
}
