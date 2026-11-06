package com.arshad.ayn.core.access

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

/**
 * YouTube's Restricted Mode on the wire (KP-D2): the `YouTube-Restrict` header value the
 * InnerTube requests of one call carry. The filter layer sets it per call from the profile
 * captured when the call starts (KP-D15); the transport reads it on the thread that makes the
 * request. That works because the vendored extractor's requests are blocking calls on the calling
 * coroutine's thread, which the context element keeps set wherever the coroutine runs.
 */
object YouTubeRestrict {
    const val HEADER: String = "YouTube-Restrict"
    const val STRICT: String = "Strict"

    /** The hosts Google applies the header on (Workspace admin help, "Control YouTube content
     *  available to users", checked 2026-10-10); other hosts never see it. */
    val HOSTS: Set<String> = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        "www.youtube-nocookie.com",
    )

    private val value = ThreadLocal<String?>()

    /** Requests made under this context carry [headerValue]; `null` sends no header. */
    fun context(headerValue: String?): CoroutineContext = value.asContextElement(headerValue)

    /** The value for a request made on this thread now; `null` outside any [context]. */
    fun current(): String? = value.get()
}
