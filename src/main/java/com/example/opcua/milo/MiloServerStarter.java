package com.example.opcua.milo;

import com.example.opcua.config.OpcUaServerProperties;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Owns the server and namespace lifecycle within the Spring application context.
 *
 * <p>Server future waits are bounded. Milo namespace lifecycle callbacks are
 * synchronous and must return promptly. No extra keep-alive thread or JVM
 * shutdown hook is needed: Spring owns startup and shutdown. A stopped or
 * failed instance cannot be restarted because Milo 0.6.x does not recreate
 * the built-in namespaces that its constructor started.</p>
 */
public class MiloServerStarter implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MiloServerStarter.class);

    private final OpcUaServer server;
    private final List<ManagedNamespaceWithLifecycle> namespaces;
    private final List<ManagedNamespaceWithLifecycle> startedNamespaces = new ArrayList<>();
    private final long startupTimeoutNanos;
    private final long shutdownTimeoutNanos;
    private final boolean autoStartup;

    private volatile boolean running;
    // Milo starts its built-in namespaces in the server constructor, even
    // when automatic network startup is disabled. Always release them once.
    private boolean serverNeedsShutdown = true;
    private boolean stopped;
    private boolean failed;

    public MiloServerStarter(OpcUaServer server,
                             List<ManagedNamespaceWithLifecycle> namespaces,
                             OpcUaServerProperties properties) {
        this.server = Objects.requireNonNull(server, "server");
        this.namespaces = List.copyOf(namespaces);
        Objects.requireNonNull(properties, "properties");
        this.startupTimeoutNanos = positiveTimeout(
                properties.getLifecycle().getStartupTimeout(), "startupTimeout");
        this.shutdownTimeoutNanos = positiveTimeout(
                properties.getLifecycle().getShutdownTimeout(), "shutdownTimeout");
        this.autoStartup = properties.getAutostart().isEnabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (failed || stopped) {
            throw new IllegalStateException("OPC UA lifecycle has stopped or failed; create a new application context to restart");
        }

        CompletableFuture<OpcUaServer> startup = null;
        try {
            for (ManagedNamespaceWithLifecycle namespace : namespaces) {
                // A callback can allocate resources and then fail. Include that
                // partially-started namespace in reverse-order rollback too.
                startedNamespaces.add(namespace);
                namespace.startup();
            }

            startup = server.startup();
            startup.get(startupTimeoutNanos, TimeUnit.NANOSECONDS);
            verifyBoundEndpoints();
            running = true;
            log.info("OPC UA server started");
        } catch (InterruptedException e) {
            IllegalStateException failure = startupFailure("OPC UA server startup interrupted", e, startup);
            Thread.currentThread().interrupt();
            throw failure;
        } catch (TimeoutException e) {
            throw startupFailure("OPC UA server startup timed out", e, startup);
        } catch (ExecutionException e) {
            throw startupFailure("OPC UA server startup failed", e.getCause(), startup);
        } catch (RuntimeException e) {
            throw startupFailure("OPC UA server startup failed", e, startup);
        } catch (Error e) {
            failed = true;
            arrangeLateStartupCleanup(startup);
            RuntimeException cleanupFailure = cleanup();
            if (cleanupFailure != null) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private void verifyBoundEndpoints() {
        // Milo 0.6.x logs individual bind failures but can still complete its
        // startup future successfully. Only report ready when every configured
        // endpoint is actually bound, including optional HTTPS endpoints.
        Set<EndpointConfiguration> configured = server.getConfig().getEndpoints();
        if (configured.isEmpty()) {
            throw new IllegalStateException("No OPC UA server endpoints are configured");
        }
        Set<EndpointConfiguration> missing = new LinkedHashSet<>(configured);
        missing.removeAll(server.getStackServer().getBoundEndpoints());
        if (!missing.isEmpty()) {
            String endpoints = missing.stream()
                    .map(EndpointConfiguration::getEndpointUrl)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("OPC UA server failed to bind configured endpoints: " + endpoints);
        }
    }

    private IllegalStateException startupFailure(String message, Throwable cause,
                                                 CompletableFuture<OpcUaServer> startup) {
        // A failed startup is terminal for this server instance. In particular,
        // a late completion must never shut down a newly restarted instance.
        failed = true;
        arrangeLateStartupCleanup(startup);
        IllegalStateException failure = new IllegalStateException(message, cause);
        RuntimeException cleanupFailure = cleanup();
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private void arrangeLateStartupCleanup(CompletableFuture<OpcUaServer> startup) {
        if (startup != null && !startup.isDone()) {
            // Cancelling a CompletableFuture does not cancel Milo's underlying
            // socket bind. Close again if a timed-out/interrupted bind completes
            // after the immediate rollback, without waiting on its future.
            startup.whenComplete((ignored, startupError) -> {
                try {
                    server.shutdown().whenComplete((shutdownServer, shutdownError) -> {
                        if (shutdownError != null) {
                            log.error("Could not clean up late OPC UA server startup", shutdownError);
                        }
                    });
                } catch (RuntimeException e) {
                    log.error("Could not clean up late OPC UA server startup", e);
                }
            });
        }
    }

    @Override
    public synchronized void stop() {
        if (!running && startedNamespaces.isEmpty()) {
            return;
        }
        stopped = true;
        RuntimeException failure = cleanup();
        if (failure != null) {
            failed = true;
            throw failure;
        }
    }

    @Override
    public synchronized void destroy() {
        // Spring may destroy this bean without ever calling stop(), e.g.
        // autostart=false or a context refresh failure before lifecycle start.
        stopped = true;
        RuntimeException failure = cleanup();
        if (failure != null) {
            failed = true;
            throw failure;
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            // Spring must be released even when cleanup reports a failure.
            callback.run();
        }
    }

    private RuntimeException cleanup() {
        running = false;
        RuntimeException failure = null;
        boolean interrupted = Thread.interrupted();
        try {
            for (int i = startedNamespaces.size() - 1; i >= 0; i--) {
                try {
                    startedNamespaces.get(i).shutdown();
                } catch (RuntimeException | Error e) {
                    failure = addCleanupFailure(failure, e);
                }
            }
            startedNamespaces.clear();

            // Preserve an interrupt set by a namespace callback, but still
            // initiate and wait for server cleanup before restoring it.
            interrupted |= Thread.interrupted();
            if (serverNeedsShutdown) {
                serverNeedsShutdown = false;
                try {
                    server.shutdown().get(shutdownTimeoutNanos, TimeUnit.NANOSECONDS);
                    log.info("OPC UA server shut down");
                } catch (InterruptedException e) {
                    interrupted = true;
                    failure = addCleanupFailure(failure, e);
                } catch (ExecutionException e) {
                    failure = addCleanupFailure(failure, e.getCause());
                } catch (TimeoutException | RuntimeException | Error e) {
                    failure = addCleanupFailure(failure, e);
                }
            }
            return failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException addCleanupFailure(RuntimeException failure, Throwable cause) {
        if (failure == null) {
            return new IllegalStateException("OPC UA server shutdown failed", cause);
        }
        failure.addSuppressed(cause);
        return failure;
    }

    private static long positiveTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    @Override
    public int getPhase() {
        // Start after ordinary lifecycle components; stop before dependencies.
        return Integer.MAX_VALUE;
    }
}
