package com.fluxguard.grpc;

import com.fluxguard.grpc.ratelimit.v1.RateLimitGrpc;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Spring-managed lifecycle wrapper around the Netty-based gRPC server.
 *
 * <p>Binds the {@link RateLimitGrpcService} on the configured port, exposing the
 * standard gRPC health and reflection services alongside it. Every RPC is traced
 * via an OpenTelemetry server interceptor so spans correlate with the calling
 * Java/Go services. The server starts and stops with the Spring application
 * context through {@link SmartLifecycle}.
 */
@Component
public class GrpcServer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcServer.class);

    /** Seconds to wait for a graceful shutdown before forcing termination. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final RateLimitGrpcService service;
    private final OpenTelemetry openTelemetry;
    private final int port;

    private final HealthStatusManager health = new HealthStatusManager();
    private Server server;
    private volatile boolean running;

    /**
     * Constructs the lifecycle wrapper with its collaborators.
     *
     * @param service       the rate-limit gRPC service implementation to expose
     * @param openTelemetry auto-configured OpenTelemetry entrypoint for tracing
     * @param port          TCP port the server binds to
     */
    public GrpcServer(
            final RateLimitGrpcService service,
            final OpenTelemetry openTelemetry,
            @Value("${fluxguard.grpc.port:9099}") final int port) {
        this.service = service;
        this.openTelemetry = openTelemetry;
        this.port = port;
    }

    /**
     * Builds and starts the gRPC server, marking health checks as serving.
     *
     * @throws RuntimeException if the server cannot bind to the configured port
     */
    @Override
    public void start() {
        try {
            this.server = buildServer().start();
            health.setStatus("", ServingStatus.SERVING);
            health.setStatus(RateLimitGrpc.SERVICE_NAME, ServingStatus.SERVING);
            this.running = true;
            LOG.info("gRPC server started on port {}", server.getPort());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to start gRPC server on port " + port, ex);
        }
    }

    private Server buildServer() {
        final GrpcTelemetry telemetry = GrpcTelemetry.create(openTelemetry);
        return NettyServerBuilder.forPort(port)
            .addService(service)
            .addService(ProtoReflectionServiceV1.newInstance())
            .addService(health.getHealthService())
            .intercept(telemetry.createServerInterceptor())
            .build();
    }

    /**
     * Marks health as not-serving and shuts the server down gracefully.
     *
     * <p>Waits up to {@link #SHUTDOWN_TIMEOUT_SECONDS} seconds for in-flight RPCs
     * to drain before forcing termination.
     */
    @Override
    public void stop() {
        health.setStatus("", ServingStatus.NOT_SERVING);
        health.setStatus(RateLimitGrpc.SERVICE_NAME, ServingStatus.NOT_SERVING);
        if (server != null) {
            shutdownServer();
        }
        this.running = false;
        LOG.info("gRPC server stopped");
    }

    private void shutdownServer() {
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException ex) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Indicates whether the gRPC server is currently running.
     *
     * @return {@code true} once {@link #start()} has bound the server
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Signals that this component should start automatically with the context.
     *
     * @return always {@code true}
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
