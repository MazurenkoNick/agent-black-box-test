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
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.agent.AgentAppEventActionType;
import org.thingsboard.server.common.data.agent.AgentAppEventRequest;
import org.thingsboard.server.common.data.agent.AgentApplication;
import org.thingsboard.server.common.data.agent.config.AgentAppArgument;
import org.thingsboard.server.common.data.agent.config.AgentAppArgumentFormat;
import org.thingsboard.server.common.data.agent.config.AgentAppArgumentSource;
import org.thingsboard.server.common.data.agent.config.AgentAppArgumentValueType;
import org.thingsboard.server.common.data.agent.config.DockerComposeConfig;
import org.thingsboard.server.common.data.agent.template.AgentAppTemplate;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class AppArgumentsTest extends AbstractContainerTest {

    @Test
    public void testDynamicArgumentIsResolvedInDeployedContainerEnv() {
        String attributeKey = "agent_label_attr";
        String expectedValue = "resolved-" + System.currentTimeMillis();

        // Write the source attribute on the agent (AGENT source, SERVER_SCOPE).
        ObjectNode attributes = JacksonUtil.newObjectNode();
        attributes.put(attributeKey, expectedValue);
        cloudRestClient.saveEntityAttributesV2(agent.getId(), AttributeScope.SERVER_SCOPE.name(), attributes);

        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = getComposeTemplateByName(template, "default").orElseThrow();
        String serviceName = injectArgumentEnv(compose, "TB_RESOLVED", "${tb.app_label}");

        AgentApplication app = null;
        String projectName = null;
        try {
            AgentAppArgument argument = new AgentAppArgument();
            argument.setName("app_label");
            argument.setSourceType(AgentAppArgumentSource.AGENT);
            argument.setValueType(AgentAppArgumentValueType.ATTRIBUTE);
            argument.setScope(AttributeScope.SERVER_SCOPE);
            argument.setKey(attributeKey);

            app = installAppWithArguments(template, compose, List.of(argument));
            awaitEventFinished(app.getId());

            projectName = getProjectName(app.getId());
            awaitContainersRunning(projectName, 1);

            final String project = projectName;
            final String service = serviceName;
            Awaitility.await("resolved env present on container")
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> expectedValue.equals(dockerVerifier.getContainerEnv(project, service).get("TB_RESOLVED")));

            Assert.assertEquals("Dynamic argument must be resolved into the deployed container env",
                    expectedValue, dockerVerifier.getContainerEnv(projectName, serviceName).get("TB_RESOLVED"));
        } finally {
            deleteAppQuietly(app, projectName);
        }
    }

    @Test
    public void testJsonFormatScalarInjectedRaw() {
        // Resolved value "prod" is valid JSON, so a whole-value placeholder is injected raw and the
        // surrounding quotes collapse: the container env ends up as prod (no quotes).
        runFormatTest(AgentAppArgumentFormat.JSON, "json", "\"prod\"", Sink.ENV, Map.of("TB_FORMAT", "prod"));
    }

    @Test
    public void testStringFormatScalarInjectedQuoted() {
        // Same resolved value "prod", but STRING format JSON-escapes it and keeps it a quoted string
        // literal, so the container env keeps the embedded quotes: "prod".
        runFormatTest(AgentAppArgumentFormat.STRING, "string", "\"prod\"", Sink.ENV, Map.of("TB_FORMAT", "\"prod\""));
    }

    @Test
    public void testStringFormatArrayInjectedAsLiteral() {
        // An array value under STRING format stays an escaped string literal, so it survives in the
        // (scalar-only) container env verbatim as ["a","b"].
        runFormatTest(AgentAppArgumentFormat.STRING, "string_arr", "[\"a\",\"b\"]", Sink.ENV,
                Map.of("TB_FORMAT", "[\"a\",\"b\"]"));
    }

    @Test
    public void testJsonFormatArrayInjectedRaw() {
        // An array value under JSON format is injected raw into the structured ports list (the same value
        // under STRING format would make ports a string literal and fail compose validation). Every entry,
        // including the expanded 5683-5688/udp range, must be published with the requested host port.
        runFormatTest(AgentAppArgumentFormat.JSON, "json_arr",
                "[\"18080:8080\",\"11883:1883\",\"15683-15688:5683-5688/udp\"]", Sink.PORTS,
                Map.of(
                        "8080/tcp", "18080",
                        "1883/tcp", "11883",
                        "5683/udp", "15683",
                        "5684/udp", "15684",
                        "5685/udp", "15685",
                        "5686/udp", "15686",
                        "5687/udp", "15687",
                        "5688/udp", "15688"));
    }

    private enum Sink {ENV, PORTS}

    private void runFormatTest(AgentAppArgumentFormat format, String suffix, String attributeValue,
                              Sink sink, Map<String, String> expectedDeployed) {
        String attributeKey = "agent_format_attr_" + suffix;
        cloudRestClient.saveEntityAttributesV2(agent.getId(), AttributeScope.SERVER_SCOPE.name(),
                JacksonUtil.newObjectNode().put(attributeKey, attributeValue));

        AgentAppTemplate template = getLatestGenericTemplate();
        JsonNode compose = getComposeTemplateByName(template, "default").orElseThrow();
        String serviceName = sink == Sink.PORTS
                ? setWholeValuePorts(compose, "${tb.fmt_val}")
                : setObjectEnv(compose, "TB_FORMAT", "${tb.fmt_val}");

        AgentApplication app = null;
        String projectName = null;
        try {
            AgentAppArgument argument = new AgentAppArgument();
            argument.setName("fmt_val");
            argument.setSourceType(AgentAppArgumentSource.AGENT);
            argument.setValueType(AgentAppArgumentValueType.ATTRIBUTE);
            argument.setScope(AttributeScope.SERVER_SCOPE);
            argument.setKey(attributeKey);
            argument.setFormat(format);

            app = installAppWithArguments(template, compose, List.of(argument));
            awaitEventFinished(app.getId());

            projectName = getProjectName(app.getId());
            awaitContainersRunning(projectName, 1);

            final String project = projectName;
            final String service = serviceName;
            Supplier<Map<String, String>> actual = sink == Sink.PORTS
                    ? () -> dockerVerifier.getPublishedPorts(project, service)
                    : () -> dockerVerifier.getContainerEnv(project, service);

            Awaitility.await(format + "/" + sink + " values present on container")
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> actual.get().entrySet().containsAll(expectedDeployed.entrySet()));

            Map<String, String> deployed = actual.get();
            expectedDeployed.forEach((key, expected) -> Assert.assertEquals(
                    format + "-format argument must be injected according to its format: " + key,
                    expected, deployed.get(key)));
        } finally {
            deleteAppQuietly(app, projectName);
        }
    }

    private AgentApplication installAppWithArguments(AgentAppTemplate template, JsonNode compose,
                                                     List<AgentAppArgument> arguments) {
        AgentApplication app = AgentApplication.fromTemplate(template);
        app.setAgentId(agent.getId());
        app.setName("test-app-args-" + System.currentTimeMillis());
        DockerComposeConfig config = (DockerComposeConfig) app.getConfig();
        config.setCompose(compose);
        config.setArguments(arguments);
        AgentAppEventRequest request = new AgentAppEventRequest();
        request.setActionType(AgentAppEventActionType.INSTALL);
        request.setApplication(app);
        return cloudRestClient.installAgentApp(request).getApplication();
    }

    private String injectArgumentEnv(JsonNode compose, String envKey, String envValue) {
        ObjectNode services = (ObjectNode) compose.get("services");
        String serviceName = services.fieldNames().next();
        ObjectNode service = (ObjectNode) services.get(serviceName);
        JsonNode existingEnv = service.get("environment");
        if (existingEnv instanceof ArrayNode arrayEnv) {
            arrayEnv.add(envKey + "=" + envValue);
        } else {
            ObjectNode environment = existingEnv instanceof ObjectNode objectEnv ? objectEnv : JacksonUtil.newObjectNode();
            environment.put(envKey, envValue);
            service.set("environment", environment);
        }
        return serviceName;
    }

    /**
     * Sets an environment entry on the first service using the map form ({@code KEY: "value"}), converting any
     * existing list-form entries so the whole service env stays in object form. Object form is required for the
     * JSON-format test: only then is the injected {@code "${tb.<name>}"} a whole JSON value, which is the case
     * {@link org.thingsboard.server.common.data.agent.config.AgentArgumentUtils} treats as raw-injectable.
     */
    private String setObjectEnv(JsonNode compose, String envKey, String envValue) {
        ObjectNode services = (ObjectNode) compose.get("services");
        String serviceName = services.fieldNames().next();
        ObjectNode service = (ObjectNode) services.get(serviceName);
        ObjectNode environment = JacksonUtil.newObjectNode();
        JsonNode existingEnv = service.get("environment");
        if (existingEnv instanceof ArrayNode arrayEnv) {
            for (JsonNode entryNode : arrayEnv) {
                String entry = entryNode.asText();
                int idx = entry.indexOf('=');
                if (idx >= 0) {
                    environment.put(entry.substring(0, idx), entry.substring(idx + 1));
                } else {
                    environment.putNull(entry);
                }
            }
        } else if (existingEnv instanceof ObjectNode objectEnv) {
            environment.setAll(objectEnv);
        }
        environment.put(envKey, envValue);
        service.set("environment", environment);
        return serviceName;
    }

    /**
     * Replaces the first service's {@code ports} with the given whole-value placeholder. Under JSON format
     * a resolved array value is injected raw into this structured list; under STRING format the same value
     * would become a string literal and fail compose validation.
     */
    private String setWholeValuePorts(JsonNode compose, String placeholder) {
        ObjectNode services = (ObjectNode) compose.get("services");
        String serviceName = services.fieldNames().next();
        ObjectNode service = (ObjectNode) services.get(serviceName);
        service.put("ports", placeholder);
        return serviceName;
    }

}
