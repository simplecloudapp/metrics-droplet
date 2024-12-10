package app.simplecloud.droplet.metrics.runtime.launcher

import app.simplecloud.droplet.metrics.runtime.MetricsRuntime
import app.simplecloud.metrics.internal.api.MetricsCollector
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.Path

class MetricsStartCommand(
    private val metricsCollector: MetricsCollector?
) : SuspendingCliktCommand() {

    private val defaultDatabaseUrl = "jdbc:sqlite:database.db"
    val databaseUrl: String by option(help = "Database URL (default: ${defaultDatabaseUrl})", envvar = "DATABASE_URL")
        .default(defaultDatabaseUrl)

    val grpcHost: String by option(help = "Grpc host (default: localhost)", envvar = "GRPC_HOST").default("localhost")
    val grpcPort: Int by option(help = "Grpc port (default: 5836)", envvar = "GRPC_PORT").int().default(5836)

    val pubSubGrpcHost: String by option(help = "Grpc host (default: localhost)", envvar = "GRPC_HOST").default("localhost")
    val pubSubGrpcPort: Int by option(help = "PubSub Grpc port (default: 5817)", envvar = "PUBSUB_GRPC_PORT").int()
        .default(5817)

    private val authSecretPath: Path by option(
        help = "Path to auth secret file (default: .auth.secret)",
        envvar = "AUTH_SECRET_PATH"
    )
        .path()
        .default(Path.of(".secrets", "auth.secret"))

    val authSecret: String by option(help = "Auth secret", envvar = "AUTH_SECRET_KEY")
        .defaultLazy { Files.readString(authSecretPath) }

    val authorizationHost: String by option(help = "Authorization host (default: localhost)", envvar = "AUTHORIZATION_HOST").default("localhost")

    val authorizationPort: Int by option(
        help = "Authorization port (default: 5818)",
        envvar = "AUTHORIZATION_PORT"
    ).int().default(5818)

    private val trackMetrics: Boolean by option(help = "Track metrics", envvar = "TRACK_METRICS")
        .boolean()
        .default(true)

    override suspend fun run() {
        if (trackMetrics) {
            metricsCollector?.start()
        }

        MetricsRuntime(this).start()
    }

}