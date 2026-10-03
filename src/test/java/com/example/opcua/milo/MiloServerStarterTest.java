package com.example.opcua.milo;

import com.example.opcua.config.OpcUaServerProperties;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.UaStackServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiloServerStarterTest {

    private OpcUaServer server;
    private OpcUaServerConfig serverConfig;
    private UaStackServer stackServer;
    private EndpointConfiguration endpoint;
    private ManagedNamespaceWithLifecycle first;
    private ManagedNamespaceWithLifecycle second;
    private OpcUaServerProperties properties;
    private MiloServerStarter lifecycle;

    @BeforeEach
    void setUp() {
        server = mock(OpcUaServer.class);
        serverConfig = mock(OpcUaServerConfig.class);
        stackServer = mock(UaStackServer.class);
        endpoint = mock(EndpointConfiguration.class);
        when(endpoint.getEndpointUrl()).thenReturn("opc.tcp://localhost:4840/test");
        when(server.getConfig()).thenReturn(serverConfig);
        when(server.getStackServer()).thenReturn(stackServer);
        when(serverConfig.getEndpoints()).thenReturn(Set.of(endpoint));
        when(stackServer.getBoundEndpoints()).thenReturn(Set.of(endpoint));
        first = mock(ManagedNamespaceWithLifecycle.class);
        second = mock(ManagedNamespaceWithLifecycle.class);
        properties = new OpcUaServerProperties();
        properties.getLifecycle().setStartupTimeout(Duration.ofMillis(20));
        properties.getLifecycle().setShutdownTimeout(Duration.ofMillis(20));
        when(server.startup()).thenReturn(CompletableFuture.completedFuture(server));
        when(server.shutdown()).thenReturn(CompletableFuture.completedFuture(server));
        lifecycle = new MiloServerStarter(server, List.of(first, second), properties);
    }

    @AfterEach
    void clearTestThreadInterrupt() {
        Thread.interrupted();
    }

    @Test
    void startsNamespacesBeforeServerAndStopsThemInReverseOrder() {
        assertFalse(lifecycle.isRunning());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());

        InOrder order = inOrder(first, second, server);
        order.verify(first).startup();
        order.verify(second).startup();
        order.verify(server).startup();
        order.verify(second).shutdown();
        order.verify(first).shutdown();
        order.verify(server).shutdown();
    }

    @Test
    void repeatedStartAndStopAreIdempotent() {
        lifecycle.stop();
        lifecycle.start();
        lifecycle.start();
        lifecycle.stop();
        lifecycle.stop();

        verify(first).startup();
        verify(second).startup();
        verify(first).shutdown();
        verify(second).shutdown();
        verify(server).startup();
        verify(server).shutdown();
    }

    @Test
    void rejectsRestartAfterACleanStopBecauseMiloBuiltInNamespacesCannotRestart() {
        lifecycle.start();
        lifecycle.stop();
        assertThrows(IllegalStateException.class, lifecycle::start);
        assertFalse(lifecycle.isRunning());

        verify(server).startup();
        verify(server).shutdown();
        verify(first).startup();
        verify(first).shutdown();
    }

    @Test
    void destroyReleasesConstructedServerEvenWhenAutoStartupWasDisabled() {
        properties.getAutostart().setEnabled(false);
        lifecycle = new MiloServerStarter(server, List.of(first), properties);

        lifecycle.destroy();
        lifecycle.destroy();
        assertFalse(lifecycle.isRunning());
        verify(server, never()).startup();
        verify(first, never()).startup();
        verify(first, never()).shutdown();
        verify(server).shutdown();
        assertThrows(IllegalStateException.class, lifecycle::start);
    }

    @Test
    void springStopThenDestroyDoesNotShutDownTwice() {
        lifecycle.start();
        lifecycle.stop();
        lifecycle.destroy();
        verify(server).shutdown();
        verify(first).shutdown();
        verify(second).shutdown();
    }

    @Test
    void failureInNamespaceRollsBackPartiallyStartedAndEarlierNamespaces() {
        IllegalArgumentException cause = new IllegalArgumentException("bad namespace");
        doThrow(cause).when(second).startup();

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertSame(cause, failure.getCause());
        assertFalse(lifecycle.isRunning());
        InOrder order = inOrder(first, second);
        order.verify(first).startup();
        order.verify(second).startup();
        order.verify(second).shutdown();
        order.verify(first).shutdown();
        verify(server, never()).startup();
        verify(server).shutdown();
    }

    @Test
    void serverStartupFailurePropagatesToCallerAndRollsBackNamespaces() {
        IllegalArgumentException cause = new IllegalArgumentException("bind failed");
        when(server.startup()).thenReturn(CompletableFuture.failedFuture(cause));

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertSame(cause, failure.getCause());
        assertFalse(lifecycle.isRunning());
        InOrder order = inOrder(second, first, server);
        order.verify(server).startup();
        order.verify(second).shutdown();
        order.verify(first).shutdown();
        order.verify(server).shutdown();
    }

    @Test
    void completedStartupFutureWithoutBoundEndpointsFailsAndRollsBack() {
        when(stackServer.getBoundEndpoints()).thenReturn(Set.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(failure.getCause().getMessage().contains("failed to bind"));
        assertTrue(failure.getCause().getMessage().contains("opc.tcp://localhost:4840/test"));
        assertFalse(lifecycle.isRunning());
        verify(second).shutdown();
        verify(first).shutdown();
        verify(server).shutdown();
    }

    @Test
    void partiallyBoundEndpointConfigurationFailsAndRollsBack() {
        EndpointConfiguration httpsEndpoint = mock(EndpointConfiguration.class);
        when(httpsEndpoint.getEndpointUrl()).thenReturn("https://localhost:8443/test");
        when(serverConfig.getEndpoints()).thenReturn(Set.of(endpoint, httpsEndpoint));

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(failure.getCause().getMessage().contains("https://localhost:8443/test"));
        assertFalse(lifecycle.isRunning());
        verify(second).shutdown();
        verify(first).shutdown();
        verify(server).shutdown();
    }

    @Test
    void serverWithNoConfiguredEndpointsIsNeverReportedAsRunning() {
        when(serverConfig.getEndpoints()).thenReturn(Set.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(failure.getCause().getMessage().contains("No OPC UA server endpoints"));
        assertFalse(lifecycle.isRunning());
        verify(server).shutdown();
    }

    @Test
    void synchronouslyThrownServerFailureStillShutsServerDown() {
        IllegalStateException cause = new IllegalStateException("startup failed before future");
        when(server.startup()).thenThrow(cause);

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertSame(cause, failure.getCause());
        verify(server).shutdown();
        verify(first).shutdown();
        verify(second).shutdown();
    }

    @Test
    void startupTimeoutIsBoundedAndClosesLateStartup() {
        CompletableFuture<OpcUaServer> startup = new CompletableFuture<>();
        when(server.startup()).thenReturn(startup);

        IllegalStateException failure = assertTimeout(Duration.ofSeconds(2),
                () -> assertThrows(IllegalStateException.class, lifecycle::start));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertFalse(lifecycle.isRunning());
        assertFalse(startup.isCancelled(), "cancellation would hide a later socket bind completion");
        verify(server).shutdown();
        verify(first).shutdown();
        verify(second).shutdown();

        startup.complete(server);
        verify(server, times(2)).shutdown();
        assertThrows(IllegalStateException.class, lifecycle::start);
        verify(server).startup();
    }

    @Test
    void startupInterruptionRestoresFlagAndCleansUp() {
        when(server.startup()).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(lifecycle.isRunning());
        verify(first).shutdown();
        verify(second).shutdown();
        verify(server).shutdown();
    }

    @Test
    void startupErrorIsRethrownAfterRollback() {
        AssertionError cause = new AssertionError("namespace fatal failure");
        doThrow(cause).when(second).startup();

        assertSame(cause, assertThrows(AssertionError.class, lifecycle::start));
        verify(second).shutdown();
        verify(first).shutdown();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void rollbackFailureIsSuppressedWithoutHidingStartupCause() {
        IllegalStateException startupFailure = new IllegalStateException("bind failed");
        IllegalStateException shutdownFailure = new IllegalStateException("cleanup failed");
        when(server.startup()).thenReturn(CompletableFuture.failedFuture(startupFailure));
        doThrow(shutdownFailure).when(second).shutdown();

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::start);
        assertSame(startupFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(shutdownFailure, failure.getSuppressed()[0].getCause());
        verify(first).shutdown();
        verify(server).shutdown();
    }

    @Test
    void shutdownContinuesAfterNamespaceFailureAndInvokesSpringCallback() {
        lifecycle.start();
        IllegalStateException namespaceFailure = new IllegalStateException("namespace cleanup failed");
        IllegalStateException serverFailure = new IllegalStateException("server cleanup failed");
        doThrow(namespaceFailure).when(second).shutdown();
        when(server.shutdown()).thenReturn(CompletableFuture.failedFuture(serverFailure));
        Runnable callback = mock(Runnable.class);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> lifecycle.stop(callback));
        assertSame(namespaceFailure, failure.getCause());
        assertArrayEquals(new Throwable[]{serverFailure}, failure.getSuppressed());
        verify(first).shutdown();
        verify(server).shutdown();
        verify(callback).run();
        assertFalse(lifecycle.isRunning());
        lifecycle.stop();
        verify(server).shutdown();
    }

    @Test
    void shutdownTimeoutIsBoundedAndStillInvokesCallback() {
        lifecycle.start();
        when(server.shutdown()).thenReturn(new CompletableFuture<>());
        Runnable callback = mock(Runnable.class);

        IllegalStateException failure = assertTimeout(Duration.ofSeconds(2),
                () -> assertThrows(IllegalStateException.class, () -> lifecycle.stop(callback)));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertFalse(lifecycle.isRunning());
        verify(callback).run();
        verify(first).shutdown();
        verify(second).shutdown();
        assertThrows(IllegalStateException.class, lifecycle::start);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionWhileWaitingForShutdownRestoresFlag() throws Exception {
        lifecycle.start();
        CompletableFuture<OpcUaServer> shutdown = mock(CompletableFuture.class);
        when(shutdown.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("interrupted"));
        when(server.shutdown()).thenReturn(shutdown);

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifecycle::stop);
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void stopPreservesAnExistingInterruptWhileStillCleaningUp() {
        lifecycle.start();
        Thread.currentThread().interrupt();

        lifecycle.stop();
        assertTrue(Thread.currentThread().isInterrupted());
        verify(server).shutdown();
        verify(first).shutdown();
        verify(second).shutdown();
    }

    @Test
    void autoStartupIsControlledByPropertiesWithoutBlockingExplicitStart() {
        assertTrue(lifecycle.isAutoStartup());
        properties.getAutostart().setEnabled(false);
        lifecycle = new MiloServerStarter(server, List.of(first), properties);
        assertFalse(lifecycle.isAutoStartup());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
    }

    @Test
    void rejectsInvalidTimeoutsBeforeStartingAnything() {
        properties.getLifecycle().setStartupTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> new MiloServerStarter(server, List.of(first), properties));
        properties.getLifecycle().setStartupTimeout(Duration.ofMillis(10));
        properties.getLifecycle().setShutdownTimeout(Duration.ofMillis(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new MiloServerStarter(server, List.of(first), properties));
        verify(server, never()).startup();
    }
}
