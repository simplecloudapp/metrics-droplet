package app.simplecloud.droplet.metrics.runtime

import app.simplecloud.droplet.api.auth.MetadataKeys
import io.grpc.*

class StandaloneAuthSecretInterceptor(
    private val secretKey: String
) : ServerInterceptor {

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val secretKey = headers.get(MetadataKeys.AUTH_SECRET_KEY)
        if (this.secretKey != secretKey) {
            call.close(Status.UNAUTHENTICATED, headers)
            return object : ServerCall.Listener<ReqT>() {}
        }

        return Contexts.interceptCall(Context.current(), call, headers, next)
    }

}