package com.example.opcua.integration;

import com.example.opcua.config.OpcUaAutoconfiguration;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/** Uses only a generated test certificate and an ephemeral IPv4 loopback listener. */
class LoopbackServerIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void occupiedLoopbackPortFailsContextStartupAndDoesNotLeakAListener() throws Exception {
        int port;
        try (ServerSocket occupiedPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = occupiedPort.getLocalPort();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OpcUaAutoconfiguration.class))
                    .withPropertyValues(
                            "spring.opcua.server.enabled=true",
                            "spring.opcua.server.autostart.enabled=true",
                            "spring.opcua.server.bind-address=127.0.0.1",
                            "spring.opcua.server.advertised-hosts=127.0.0.1",
                            "spring.opcua.server.tcp.port=" + port,
                            "spring.opcua.server.key-store.path=" + temporaryDirectory.resolve("occupied-port.p12"),
                            "spring.opcua.server.key-store.password=occupied-port-test-only-password",
                            "spring.opcua.server.key-store.generate=true",
                            "spring.opcua.server.trust-list-manager.path=" + temporaryDirectory.resolve("pki"),
                            "spring.opcua.server.lifecycle.startup-timeout=5s",
                            "spring.opcua.server.lifecycle.shutdown-timeout=5s")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("miloServerStarter");
                        assertThat(occupiedPort.isClosed()).isFalse();
                    });
        }
        try (ServerSocket releasedPort = new ServerSocket()) {
            // Ignore closed TCP connections in TIME_WAIT, but never an active listener.
            releasedPort.setReuseAddress(true);
            releasedPort.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            assertThat(releasedPort.isBound()).isTrue();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void startsReadsDemoValueDisconnectsAndReleasesListenerOnContextClose() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        String namespaceUri = "urn:example:opcua:loopback-test";
        String endpointUrl = "opc.tcp://127.0.0.1:" + port + "/integration-test";

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpcUaAutoconfiguration.class))
                .withPropertyValues(
                        "spring.opcua.server.enabled=true",
                        "spring.opcua.server.autostart.enabled=true",
                        "spring.opcua.server.bind-address=127.0.0.1",
                        "spring.opcua.server.advertised-hosts=127.0.0.1",
                        "spring.opcua.server.tcp.port=" + port,
                        "spring.opcua.server.path=/integration-test",
                        "spring.opcua.server.discovery-path=/integration-test/discovery",
                        "spring.opcua.server.application-uri=urn:example:opcua:loopback-server",
                        "spring.opcua.server.key-store.path=" + temporaryDirectory.resolve("server.p12"),
                        "spring.opcua.server.key-store.password=integration-test-only-password",
                        "spring.opcua.server.key-store.generate=true",
                        "spring.opcua.server.trust-list-manager.path=" + temporaryDirectory.resolve("pki"),
                        "spring.opcua.server.lifecycle.startup-timeout=10s",
                        "spring.opcua.server.lifecycle.shutdown-timeout=10s",
                        "spring.opcua.server.demo.namespace-uri=" + namespaceUri,
                        "spring.opcua.server.demo.initial-value=87.625",
                        "spring.opcua.server.demo.writable=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("miloServerStarter", SmartLifecycle.class).isRunning()).isTrue();
                    OpcUaClient client = OpcUaClient.create(endpointUrl,
                            endpoints -> endpoints.stream()
                                    .filter(endpoint -> SecurityPolicy.None.getUri().equals(endpoint.getSecurityPolicyUri()))
                                    .findFirst(),
                            builder -> builder
                                    .setApplicationName(LocalizedText.english("Loopback integration test"))
                                    .setApplicationUri("urn:example:opcua:loopback-client")
                                    .setRequestTimeout(uint(5000))
                                    .build());
                    try {
                        client.connect().get(10, TimeUnit.SECONDS);
                        DataValue namespaceArray = client.readValue(0.0, TimestampsToReturn.Neither,
                                Identifiers.Server_NamespaceArray).get(5, TimeUnit.SECONDS);
                        assertThat(namespaceArray.getStatusCode().isGood()).isTrue();
                        String[] namespaces = (String[]) namespaceArray.getValue().getValue();
                        int namespaceIndex = Arrays.asList(namespaces).indexOf(namespaceUri);
                        assertThat(namespaceIndex).isGreaterThanOrEqualTo(1);

                        DataValue value = client.readValue(0.0, TimestampsToReturn.Neither,
                                new NodeId(namespaceIndex, "MyDevice/SensorValue")).get(5, TimeUnit.SECONDS);
                        assertThat(value.getStatusCode().isGood()).isTrue();
                        assertThat(value.getValue().getValue()).isEqualTo(87.625);
                    } finally {
                        client.disconnect().get(10, TimeUnit.SECONDS);
                    }
                });

        try (ServerSocket releasedPort = new ServerSocket()) {
            // Ignore closed TCP connections in TIME_WAIT, but never an active listener.
            releasedPort.setReuseAddress(true);
            releasedPort.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            assertThat(releasedPort.isBound()).isTrue();
        }
    }
}
