package com.arshad.ayn.core.access.newpipe

import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * A transport answering chat requests from [answers] in order (status, body; 429 as the transport
 * throws it; past the script, no connection), every other request from the recorded bootstrap
 * (YouTube's client version). Installed as the vendored extractor's transport on construction, so
 * a [NewPipeLiveChat] over it runs the real request path.
 */
class ChatServer(vararg answers: Pair<Int, String>) : Downloader() {
    private val script = ArrayDeque(answers.toList())
    private val bootstrap = FixtureDownloader("live-chat")
    val requests: MutableList<Request> = mutableListOf()

    init {
        NewPipeAccess.ensureInitialized(this)
    }

    override fun execute(request: Request): Response {
        if (CHAT_PATH !in request.url()) return bootstrap.execute(request)
        requests += request
        val (code, body) = script.removeFirstOrNull() ?: throw IOException("unscripted chat request")
        if (code == HTTP_TOO_MANY_REQUESTS) throw ReCaptchaException("rate limited", request.url())
        return Response(code, "scripted", emptyMap(), body, request.url())
    }

    private companion object {
        const val CHAT_PATH = "/live_chat/"
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
