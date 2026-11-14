package ru.markn.dnd.server

import ai.koog.http.client.KoogHttpClientException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.*

/** Only allowlisted values leave the adapter. Never retain a provider body/message as a cause. */
class AiFailure(val reason: String, val httpStatus: Int? = null, val providerType: String? = null,
    val responseIssue: String? = null) :
    Exception("AI request failed ($reason)") {
    fun fields(): Map<String, String> = buildMap {
        put("reason", reason)
        httpStatus?.let { put("http_status", it.toString()) }
        providerType?.let { put("provider_error_type", it) }
        responseIssue?.let { put("response_issue", it) }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun safeApiFailure(failure: Exception): AiFailure {
    val chain = generateSequence<Throwable>(failure) { it.cause }.take(12).toList()
    val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull { it.statusCode != null }
    if (http != null) {
        val allowedTypes = setOf("invalid_request_error", "authentication_error", "permission_error",
            "not_found_error", "rate_limit_error", "api_error", "overloaded_error")
        val type = try {
            http.errorBody?.takeIf { it.length <= 16_384 }?.let {
                Json.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            }?.takeIf { it in allowedTypes }
        } catch (_: Exception) { null }
        return AiFailure("http_error", http.statusCode, type)
    }
    val names = chain.map { it::class.simpleName }.toSet()
    val reason = when {
        chain.any { it is SerializationException } -> "provider_response_invalid"
        "UnknownHostException" in names -> "dns_error"
        names.any { it in setOf("SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException") } -> "tls_error"
        names.any { it in setOf("HttpRequestTimeoutException", "SocketTimeoutException", "ConnectTimeoutException") } -> "transport_timeout"
        names.any { it in setOf("ConnectException", "SocketException", "IOException") } -> "connection_error"
        else -> "api_client_error"
    }
    val missing = chain.filterIsInstance<MissingFieldException>().firstOrNull()
    val issue = if (reason != "provider_response_invalid") null else when {
        missing?.missingFields?.contains("signature") == true -> "missing_thinking_signature"
        missing != null -> "missing_required_provider_field"
        else -> "invalid_provider_structure"
    }
    return AiFailure(reason, responseIssue = issue)
}
