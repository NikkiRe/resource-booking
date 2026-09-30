package dev.nikita.booking.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class GrpcServer(private val api: BookingGrpcService, @Value("\${grpc.port:9090}") private val configuredPort: Int) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Volatile private var server: Server? = null

    val port: Int get() = checkNotNull(server).port

    override fun start() {
        if (isRunning) return
        val started = NettyServerBuilder.forPort(configuredPort)
            .maxInboundMessageSize(64 * 1024)
            .addService(ServerInterceptors.intercept(api, IdempotencyKeyInterceptor()))
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()
        try {
            started.start()
            server = started
            logger.info("gRPC server listening on port {}", started.port)
        } catch (exception: Exception) {
            started.shutdownNow()
            throw exception
        }
    }

    override fun stop() {
        val active = server ?: return
        active.shutdown()
        try {
            if (!active.awaitTermination(10, TimeUnit.SECONDS)) {
                active.shutdownNow()
                active.awaitTermination(5, TimeUnit.SECONDS)
            }
        } catch (exception: InterruptedException) {
            active.shutdownNow()
            Thread.currentThread().interrupt()
        } finally {
            server = null
        }
    }

    override fun isRunning(): Boolean = server?.isShutdown == false

    override fun getPhase(): Int = Int.MAX_VALUE
}

internal class IdempotencyKeyInterceptor : ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val key = headers.get(HEADER)
        val context = if (key == null) Context.current() else Context.current().withValue(KEY, key)
        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        val HEADER: Metadata.Key<String> = Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER)
        val KEY: Context.Key<String> = Context.key("idempotency-key")
    }
}
