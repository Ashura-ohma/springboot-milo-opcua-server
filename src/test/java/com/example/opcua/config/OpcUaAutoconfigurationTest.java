package com.example.opcua.config;

import com.example.opcua.auth.Authenticator;
import com.example.opcua.auth.UsernameAuthenticator;
import com.example.opcua.auth.X509Authenticator;
import com.example.opcua.milo.KeyStoreLoader;
import com.example.opcua.namespace.ExampleNamespace;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.stack.core.security.TrustListManager;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.security.ServerCertificateValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpcUaAutoconfigurationTest {

    @TempDir
    Path temporaryDirectory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpcUaAutoconfiguration.class));

    @Test
    void disabledServerCreatesNoOpcUaBeansFilesOrListenerEvenWithoutPassword() throws Exception {
        int port = availableLoopbackPort();
        runner.withPropertyValues(
                "spring.opcua.server.enabled=false",
                "spring.opcua.server.tcp.port=" + port,
                "spring.opcua.server.key-store.generate=true",
                "spring.opcua.server.key-store.path=" + keyStorePath(),
                "spring.opcua.server.trust-list-manager.path=" + trustListPath())
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(OpcUaServerProperties.class)
                            .doesNotHaveBean(OpcUaServer.class)
                            .doesNotHaveBean(KeyStoreLoader.class)
                            .doesNotHaveBean(TrustListManager.class)
                            .doesNotHaveBean(ServerCertificateValidator.class)
                            .doesNotHaveBean(Authenticator.class)
                            .doesNotHaveBean(ExampleNamespace.class)
                            .doesNotHaveBean("miloServerStarter");
                    assertThat(keyStorePath()).doesNotExist();
                    assertThat(trustListPath()).doesNotExist();
                    assertLoopbackPortAvailable(port);
                });
    }

    @Test
    void disabledServerDoesNotBindOrValidateUnusedProperties() {
        runner.withPropertyValues(
                "spring.opcua.server.enabled=false",
                "spring.opcua.server.tcp.port=not-an-integer",
                "spring.opcua.server.security.policy=not-a-policy")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(OpcUaServerProperties.class));
    }

    @Test
    void serverIsEnabledByDefaultAndRequiresExplicitKeyStorePassword() {
        runner.withPropertyValues(
                "spring.opcua.server.autostart.enabled=false",
                "spring.opcua.server.key-store.path=" + keyStorePath(),
                "spring.opcua.server.trust-list-manager.path=" + trustListPath())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("password")
                            .hasStackTraceContaining("must not be blank");
                    assertThat(keyStorePath()).doesNotExist();
                    assertThat(trustListPath()).doesNotExist();
                });
    }

    @ParameterizedTest(name = "rejects {0}")
    @CsvSource({
            "tcp.port=0, must be greater than or equal to 1",
            "tcp.port=65536, must be less than or equal to 65535",
            "bind-address=, must not be blank",
            "advertised-hosts=, must not be empty",
            "path=missing-leading-slash, must start with /",
            "discovery-path=missing-leading-slash, must start with /",
            "discovery-path=/find, discovery-path must end with /discovery",
            "path=/business/discovery, business path must not",
            "path=/business/discovery/, business path must not",
            "path=/business?query=value, use distinct plain URI paths",
            "path=/business#fragment, use distinct plain URI paths",
            "application-uri=relative-uri, applicationUri must be an absolute URI",
            "lifecycle.startup-timeout=0ms, lifecycle timeouts must be positive",
            "lifecycle.shutdown-timeout=-1s, lifecycle timeouts must be positive",
            "security.mode=Sign, security.policy None must pair with mode None",
            "security.policy=Basic256Sha256, secured policies require Sign or SignAndEncrypt",
            "tcp.encoding=xml, tcp.encoding must be binary",
            "https.encoding=xml, https.encoding must be binary",
            "https.encoding=json, https.encoding must be binary"
    })
    void invalidEnabledConfigurationFailsBeforeCreatingSecurityFiles(String property, String expectedMessage) {
        configuredRunner().withPropertyValues("spring.opcua.server." + property)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(expectedMessage);
                    assertThat(keyStorePath()).doesNotExist();
                    assertThat(trustListPath()).doesNotExist();
                });
    }

    @Test
    void bindsPropertiesAndBuildsEndpointsWithoutAutostarting() throws Exception {
        int port = availableLoopbackPort();
        configuredRunner().withPropertyValues(
                "spring.opcua.server.bind-address=127.0.0.1",
                "spring.opcua.server.advertised-hosts=localhost,127.0.0.1",
                "spring.opcua.server.tcp.port=" + port,
                "spring.opcua.server.path=/test-server",
                "spring.opcua.server.discovery-path=/test-server/discovery",
                "spring.opcua.server.application-uri=urn:example:opcua:test",
                "spring.opcua.server.lifecycle.startup-timeout=7s",
                "spring.opcua.server.lifecycle.shutdown-timeout=9s",
                "spring.opcua.server.demo.namespace-uri=urn:example:opcua:test-demo",
                "spring.opcua.server.demo.initial-value=42.125",
                "spring.opcua.server.demo.writable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(OpcUaServerProperties.class)
                            .hasSingleBean(OpcUaServer.class)
                            .hasSingleBean(ExampleNamespace.class)
                            .hasSingleBean(UsernameAuthenticator.class)
                            .hasSingleBean(X509Authenticator.class);
                    OpcUaServerProperties properties = context.getBean(OpcUaServerProperties.class);
                    assertThat(properties.getTcp().getPort()).isEqualTo(port);
                    assertThat(properties.getAdvertisedHosts()).containsExactly("localhost", "127.0.0.1");
                    assertThat(properties.getLifecycle().getStartupTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.getLifecycle().getShutdownTimeout()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(properties.getDemo().getNamespaceUri()).isEqualTo("urn:example:opcua:test-demo");
                    assertThat(properties.getDemo().getInitialValue()).isEqualTo(42.125);
                    assertThat(properties.getDemo().isWritable()).isTrue();
                    assertThat(properties.toString()).doesNotContain("test-only-password");
                    SmartLifecycle lifecycle = context.getBean("miloServerStarter", SmartLifecycle.class);
                    assertThat(lifecycle.isAutoStartup()).isFalse();
                    assertThat(lifecycle.isRunning()).isFalse();

                    Set<EndpointConfiguration> endpoints = context.getBean(OpcUaServer.class)
                            .getConfig().getEndpoints();
                    assertThat(endpoints).hasSize(4);
                    assertThat(endpoints).allSatisfy(endpoint -> {
                        assertThat(endpoint.getBindAddress()).isEqualTo("127.0.0.1");
                        assertThat(endpoint.getBindPort()).isEqualTo(port);
                        assertThat(endpoint.getHostname()).isIn("localhost", "127.0.0.1");
                        assertThat(endpoint.getPath()).isIn("/test-server", "/test-server/discovery");
                    });
                    assertThat(endpoints).extracting(EndpointConfiguration::getEndpointUrl)
                            .containsExactlyInAnyOrder(
                                    "opc.tcp://localhost:" + port + "/test-server",
                                    "opc.tcp://127.0.0.1:" + port + "/test-server",
                                    "opc.tcp://localhost:" + port + "/test-server/discovery",
                                    "opc.tcp://127.0.0.1:" + port + "/test-server/discovery");
                    assertThat(Files.isRegularFile(keyStorePath())).isTrue();
                    assertLoopbackPortAvailable(port);
                });
        assertLoopbackPortAvailable(port);
    }

    @Test
    void demoNamespaceCanBeDisabledIndependently() {
        configuredRunner().withPropertyValues("spring.opcua.server.demo.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(OpcUaServer.class)
                        .doesNotHaveBean(ExampleNamespace.class));
    }

    @Test
    void userAuthenticatorsReplaceDenyAllDefaults() {
        configuredRunner().withUserConfiguration(CustomAuthenticators.class)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(UsernameAuthenticator.class)
                            .hasSingleBean(X509Authenticator.class)
                            .doesNotHaveBean("usernameAuthenticatorDummy")
                            .doesNotHaveBean("x509AuthenticatorDummy");
                    assertThat(context.getBean(UsernameAuthenticator.class)).isSameAs(CustomAuthenticators.USERNAME);
                    assertThat(context.getBean(X509Authenticator.class)).isSameAs(CustomAuthenticators.X509);
                });
    }

    @Test
    void bootDiscoversAutoconfigurationFromImportsRegistry() {
        new ApplicationContextRunner().withUserConfiguration(RegistryApplication.class)
                .withPropertyValues(testSecurityProperties())
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(OpcUaAutoconfiguration.class)
                        .hasSingleBean(OpcUaServerProperties.class)
                        .hasSingleBean(OpcUaServer.class)
                        .hasBean("miloServerStarter"));
    }

    private ApplicationContextRunner configuredRunner() {
        return runner.withPropertyValues(testSecurityProperties());
    }

    private String[] testSecurityProperties() {
        return new String[]{
                "spring.opcua.server.enabled=true",
                "spring.opcua.server.autostart.enabled=false",
                "spring.opcua.server.key-store.password=test-only-password",
                "spring.opcua.server.key-store.generate=true",
                "spring.opcua.server.key-store.path=" + keyStorePath(),
                "spring.opcua.server.trust-list-manager.path=" + trustListPath()
        };
    }

    private Path keyStorePath() {
        return temporaryDirectory.resolve("security/server.p12");
    }

    private Path trustListPath() {
        return temporaryDirectory.resolve("security/pki");
    }

    private static int availableLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void assertLoopbackPortAvailable(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThat(socket.isBound()).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RegistryApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAuthenticators {
        static final UsernameAuthenticator USERNAME = credentials -> true;
        static final X509Authenticator X509 = certificate -> true;

        @Bean
        UsernameAuthenticator applicationUsernameAuthenticator() {
            return USERNAME;
        }

        @Bean
        X509Authenticator applicationX509Authenticator() {
            return X509;
        }
    }
}
