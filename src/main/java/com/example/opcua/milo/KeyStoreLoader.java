/*
 * Copyright (c) 2021 the Eclipse Milo Authors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.opcua.milo;

import com.example.opcua.config.OpcUaServerProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.server.util.HostnameUtil;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaRuntimeException;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedHttpsCertificateBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;

@Getter
@Slf4j
public class KeyStoreLoader {

    private final X509Certificate[] serverCertificateChain;
    private final X509Certificate serverCertificate;
    private final KeyPair serverKeyPair;

    private final X509Certificate[] httpsCertificateChain;
    private final X509Certificate httpsCertificate;
    private final KeyPair httpsKeyPair;

    public KeyStoreLoader(OpcUaServerProperties properties) throws Exception {
        Path path = Paths.get(properties.getKeyStore().getPath()).toAbsolutePath();
        char[] pass = properties.getKeyStore().getPassword().toCharArray();
        String serverAlias = properties.getKeyStore().getServerAlias();
        String httpsAlias = properties.getKeyStore().getHttpsAlias();

        log.info("Loading Key Store at {}", path);

        KeyStore keyStore = KeyStore.getInstance(properties.getKeyStore().getType());

        if (!Files.exists(path)) {
            if (!properties.getKeyStore().isGenerate()) {
                throw new UaRuntimeException(StatusCodes.Bad_ConfigurationError, "Key Store does not exist!");
            }
            Files.createDirectories(path.getParent());

            keyStore.load(null, pass);

            KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);

            String applicationUri = properties.getApplicationUri();

            SelfSignedCertificateBuilder builder = new SelfSignedCertificateBuilder(keyPair)
                    .setCommonName(properties.getApplicationName())
                    .setApplicationUri(applicationUri);

            // Get as many hostnames and IP addresses as we can listed in the certificate.
            Set<String> hostnames = new java.util.LinkedHashSet<>(properties.getAdvertisedHosts());

            for (String hostname : hostnames) {
                if (com.google.common.net.InetAddresses.isInetAddress(hostname)) {
                    builder.addIpAddress(hostname);
                } else {
                    builder.addDnsName(hostname);
                }
            }

            X509Certificate certificate = builder.build();

            keyStore.setKeyEntry(serverAlias, keyPair.getPrivate(), pass, new X509Certificate[]{certificate});

            // HTTPS certificate
            if (properties.getHttps().isEnabled()) {
                KeyPair httpsKeyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);

                SelfSignedHttpsCertificateBuilder httpsCertificateBuilder = new SelfSignedHttpsCertificateBuilder(httpsKeyPair);
                httpsCertificateBuilder.setCommonName(HostnameUtil.getHostname());
                for (String hostname : properties.getAdvertisedHosts()) {
                    if (com.google.common.net.InetAddresses.isInetAddress(hostname)) {
                        httpsCertificateBuilder.addIpAddress(hostname);
                    } else {
                        httpsCertificateBuilder.addDnsName(hostname);
                    }
                }
                X509Certificate httpsCertificate = httpsCertificateBuilder.build();

                keyStore.setKeyEntry(httpsAlias, httpsKeyPair.getPrivate(), pass, new X509Certificate[]{httpsCertificate});
            }

            try (java.io.OutputStream output = Files.newOutputStream(path)) {
                keyStore.store(output, pass);
            }
        } else {
            try (java.io.InputStream input = Files.newInputStream(path)) {
                keyStore.load(input, pass);
            }
        }

        Key serverPrivateKey = keyStore.getKey(serverAlias, pass);

        if (!(serverPrivateKey instanceof PrivateKey)) {
            throw new RuntimeException("Key is not of type PrivateKey");
        }

        serverCertificate = (X509Certificate) keyStore.getCertificate(serverAlias);

        serverCertificateChain = Arrays.stream(keyStore.getCertificateChain(serverAlias))
            .map(X509Certificate.class::cast)
            .toArray(X509Certificate[]::new);

        PublicKey serverPublicKey = serverCertificate.getPublicKey();
        serverKeyPair = new KeyPair(serverPublicKey, (PrivateKey) serverPrivateKey);

        if (properties.getHttps().isEnabled()) {
            Key httpPrivateKey = keyStore.getKey(httpsAlias, pass);
            if (!(httpPrivateKey instanceof PrivateKey)) {
                throw new RuntimeException("Key is not of type PrivateKey");
            }
            httpsCertificateChain = Arrays.stream(keyStore.getCertificateChain(httpsAlias))
                    .map(X509Certificate.class::cast).toArray(X509Certificate[]::new);
            httpsCertificate = httpsCertificateChain[0];
            PublicKey httpPublicKey = httpsCertificate.getPublicKey();
            httpsKeyPair = new KeyPair(httpPublicKey, (PrivateKey) httpPrivateKey);

        } else {
            httpsCertificateChain = new X509Certificate[0];
            httpsCertificate = null;
            httpsKeyPair = null;
        }
    }
}

