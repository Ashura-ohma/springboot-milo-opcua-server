package com.example.opcua.config;

import lombok.Data;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "spring.opcua.server")
@Data
public class OpcUaServerProperties {

    public enum Encoding { binary, xml, json }
    public enum TokenPolicy { anonymous, username, x509 }
    public enum SecurityPolicy { None, Basic128Rsa15, Basic256Sha256 }
    public enum SecurityMode { None, Sign, SignAndEncrypt }

    private boolean enabled = true;
    @NotBlank
    private String bindAddress = "127.0.0.1";
    @NotEmpty
    private List<@NotBlank String> advertisedHosts = new ArrayList<>(Arrays.asList("localhost"));
    @NotBlank
    private String applicationUri = "urn:spring:opcua:server";
    @Valid @NotNull
    private LifecycleProperties lifecycle = new LifecycleProperties();
    @Valid @NotNull
    private AutostartProperties autostart = new AutostartProperties();
    @Valid @NotNull
    private DemoProperties demo = new DemoProperties();

    @NotBlank
    private String productUri = "urn:spring:opcua";
    @NotBlank
    private String applicationName = "Spring OPC UA Server";

    @NotBlank @Pattern(regexp = "/.*", message = "must start with /")
    private String path = "/";
    @NotBlank @Pattern(regexp = "/.*", message = "must start with /")
    private String discoveryPath = "/discovery";

    @Valid @NotNull
    private TrustListManagerProperties trustListManager = new TrustListManagerProperties();
    @Valid @NotNull
    private KeyStoreProperties keyStore = new KeyStoreProperties();

    @Valid @NotNull
    private TcpProperties tcp = new TcpProperties();
    @Valid @NotNull
    private HttpsProperties https = new HttpsProperties();

    @Valid @NotNull
    private SecurityProperties security = new SecurityProperties();

    @Valid @NotNull
    private AuthenticationProperties authentication = new AuthenticationProperties();

    @Valid @NotNull
    private BuildInfoProperties buildInfo = new BuildInfoProperties();

    @AssertTrue(message = "security.policy None must pair with mode None; secured policies require Sign or SignAndEncrypt")
    public boolean isSecurityCombinationValid() {
        return security == null || security.policy == null || security.mode == null ||
                (security.policy == SecurityPolicy.None) == (security.mode == SecurityMode.None);
    }

    @AssertTrue(message = "discovery-path must end with /discovery; business path must not; use distinct plain URI paths")
    public boolean isEndpointPathsValid() {
        return isPlainPath(path) && isPlainPath(discoveryPath) &&
                !path.endsWith("/discovery") && discoveryPath.endsWith("/discovery");
    }

    private boolean isPlainPath(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return value.startsWith("/") && !value.startsWith("//") &&
                    (value.equals("/") || !value.endsWith("/")) &&
                    uri.getRawQuery() == null && uri.getRawFragment() == null &&
                    value.equals(uri.getRawPath());
        } catch (RuntimeException e) { return false; }
    }

    @AssertTrue(message = "tcp.encoding must be binary")
    public boolean isTcpEncodingValid() { return tcp == null || tcp.encoding == Encoding.binary; }

    @AssertTrue(message = "https.encoding must be binary; Milo 0.6 does not implement xml/json HTTPS")
    public boolean isHttpsEncodingValid() { return https == null || https.encoding == Encoding.binary; }

    @AssertTrue(message = "applicationUri must be an absolute URI")
    public boolean isApplicationUriValid() {
        try { return java.net.URI.create(applicationUri).isAbsolute(); }
        catch (RuntimeException e) { return false; }
    }

    @Data
    public static class LifecycleProperties {
        @NotNull private Duration startupTimeout = Duration.ofSeconds(30);
        @NotNull private Duration shutdownTimeout = Duration.ofSeconds(30);
        @AssertTrue(message = "lifecycle timeouts must be positive and at least 1ms")
        public boolean isTimeoutsValid() {
            return startupTimeout != null && shutdownTimeout != null &&
                    startupTimeout.toMillis() > 0 && shutdownTimeout.toMillis() > 0;
        }
    }

    @Data
    public static class AutostartProperties { private boolean enabled = true; }

    @Data
    public static class DemoProperties {
        private boolean enabled = true;
        @NotBlank private String namespaceUri = "urn:example:opcua:demo";
        private double initialValue = 123.45;
        private boolean writable = false;
    }

    @Data
    public static class TcpProperties {

        @NotNull
        private Encoding encoding = Encoding.binary;
        @Min(1) @Max(65535)
        private int port = 4840;
    }

    @Data
    public static class HttpsProperties {

        private boolean enabled = false;
        @NotNull
        private Encoding encoding = Encoding.binary;
        @Min(1) @Max(65535)
        private int port = 8443;
    }

    @Data
    public static class SecurityProperties {

        @NotNull
        private SecurityPolicy policy = SecurityPolicy.None;
        @NotNull
        private SecurityMode mode = SecurityMode.None;
    }

    @Data
    public static class AuthenticationProperties {

        @NotEmpty
        private List<@NotNull TokenPolicy> tokenPolicies = new ArrayList<>(Arrays.asList(TokenPolicy.anonymous));
    }

    @Data
    public static class TrustListManagerProperties {

        @NotBlank
        private String path = "./data/opcua/pki";
    }

    @Data
    public static class KeyStoreProperties {

        @NotBlank
        private String path = "./data/opcua/server.p12";
        @NotBlank
        private String type = "PKCS12";
        @NotBlank
        private String serverAlias = "server-opcua";
        @NotBlank
        private String httpsAlias = "server-https";
        @NotBlank
        @lombok.ToString.Exclude
        private String password;
        private boolean generate = false;
    }

    @Data
    public static class BuildInfoProperties {

        private String productUri;
        private String productName;
        private String manufacturerName;
        private String softwareVersion;
        private String buildNumber;
        private DateTime buildDate = DateTime.now();
    }
}

