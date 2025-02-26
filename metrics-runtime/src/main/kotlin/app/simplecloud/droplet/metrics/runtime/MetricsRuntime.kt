package app.simplecloud.droplet.metrics.runtime

import app.simplecloud.droplet.api.auth.AuthCallCredentials
import app.simplecloud.droplet.api.auth.AuthSecretInterceptor
import app.simplecloud.droplet.metrics.runtime.database.DatabaseFactory
import app.simplecloud.droplet.metrics.runtime.launcher.AuthType
import app.simplecloud.droplet.metrics.runtime.launcher.MetricsStartCommand
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRepository
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsService
import app.simplecloud.droplet.metrics.shared.MetricsEventNames
import app.simplecloud.pubsub.PubSubClient
import build.buf.gen.simplecloud.controller.v1.ControllerDropletServiceGrpcKt
import build.buf.gen.simplecloud.metrics.v1.Metric
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.logging.log4j.LogManager

class MetricsRuntime(
    private val metricsStartCommand: MetricsStartCommand
) {

    private val logger = LogManager.getLogger(MetricsRuntime::class.java)

    private val interceptor = if (metricsStartCommand.authType == AuthType.AUTH_SERVER) {
        AuthSecretInterceptor(metricsStartCommand.controllerGrpcHost, metricsStartCommand.authorizationPort)
    } else {
        StandaloneAuthSecretInterceptor(metricsStartCommand.authSecret)
    }

    private val authCallCredentials = AuthCallCredentials(metricsStartCommand.authSecret)
    private val database = DatabaseFactory.createDatabase(metricsStartCommand.databaseUrl)

    private val repository = MetricsRepository(database)
    private val server = createGrpcServer()

    private val controllerChannel =
        ManagedChannelBuilder.forAddress(metricsStartCommand.controllerGrpcHost, metricsStartCommand.controllerGrpcPort).usePlaintext()
            .build()

    private val controllerDropletStub =
        ControllerDropletServiceGrpcKt.ControllerDropletServiceCoroutineStub(controllerChannel)
            .withCallCredentials(authCallCredentials)

    private val pubSubClient = PubSubClient(
        metricsStartCommand.pubSubGrpcHost,
        metricsStartCommand.pubSubGrpcPort,
        AuthCallCredentials(metricsStartCommand.authSecret)
    )

    suspend fun start() {
        logger.info("Starting metrics runtime")
        setupDatabase()
        startGrpcServer()
        attach()
        subscribeToMetricsEvents()

        suspendCancellableCoroutine<Unit> { continuation ->
            Runtime.getRuntime().addShutdownHook(Thread {
                server.shutdown()
                continuation.resume(Unit) { cause, _, _ ->
                    logger.info("Server shutdown due to: $cause")
                }
            })
        }
    }

    private fun attach() {
        if (metricsStartCommand.authType != AuthType.AUTH_SERVER) {
            return
        }

        logger.info("Attaching to controller...")
        val attacher =
            Attacher(metricsStartCommand, controllerChannel, controllerDropletStub)
        attacher.enforceAttach()
    }

    private fun setupDatabase() {
        logger.info("Setting up database...")
        database.setup()
    }

    private fun startGrpcServer() {
        logger.info("Starting gRPC server...")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                server.start()
                server.awaitTermination()
            } catch (e: Exception) {
                logger.error("Error in gRPC server", e)
                throw e
            }
        }
    }

    private fun subscribeToMetricsEvents() {
        pubSubClient.subscribe(MetricsEventNames.RECORD_METRIC, Metric::class.java) { metric ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.saveMetric(metric)
            }
        }
    }

    private fun createGrpcServer(): Server {
        return ServerBuilder.forPort(metricsStartCommand.grpcPort)
            .addService(MetricsService(repository))
            .intercept(
                AuthSecretInterceptor(
                    metricsStartCommand.authorizationHost,
                    metricsStartCommand.authorizationPort
                )
            )
            .build()
    }

}