package com.arshad.ayn.core.access

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** A JSON answer with [code] and [body] to any request, for an interceptor standing in for a
 *  third-party API. */
fun answer(code: Int, body: String): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
