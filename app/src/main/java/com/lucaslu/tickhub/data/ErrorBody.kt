package com.lucaslu.tickhub.data

/**
 * GitHub error response body. Lives in `data` so the R8 keep rule
 * `-keep class com.lucaslu.tickhub.data.** { *; }` covers its generated serializer —
 * keeping it in `network` risked a SerializationException in the error path
 * after the v2.0 R8 shrink (the v1.0 launch-crash pattern, but on 4xx/5xx).
 */
@kotlinx.serialization.Serializable
data class ErrorBody(val message: String? = null)
