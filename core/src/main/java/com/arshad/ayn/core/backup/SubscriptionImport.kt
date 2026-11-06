package com.arshad.ayn.core.backup

import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.store.CHANNEL_ID
import com.arshad.ayn.core.store.CHANNEL_URL_PREFIX
import com.arshad.ayn.core.store.channelIdOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Far above a real subscriptions file (about 100 bytes a channel). */
internal const val MAX_SUBSCRIPTIONS_BYTES = 8 * 1024 * 1024

/** A channel read from an import, keyed the way the extractor keys channels. */
internal data class ImportedChannel(val channelUrl: String, val name: String)

/**
 * The channels in a Google Takeout `subscriptions.csv` or a NewPipe subscriptions export (a JSON
 * object, told apart by content), each once (M5-D6). Rows without a channel id are dropped, so a
 * header row goes too, whatever language it is in.
 */
internal fun parseSubscriptionsFile(bytes: ByteArray): List<ImportedChannel> {
    val text = bytes.decodeToString().removePrefix(BYTE_ORDER_MARK)
    val channels = if (text.trimStart().startsWith('{')) newPipeChannels(text) else takeoutChannels(text)
    return channels.distinctBy { it.channelUrl }
}

private fun takeoutChannels(text: String): List<ImportedChannel> = buildList {
    forEachCsvRecord(text, keepFields = TAKEOUT_COLUMNS) { fields -> takeoutChannel(fields)?.let(::add) }
}

/** Takeout columns by position (their headers are translated): channel id, channel URL, title. */
private fun takeoutChannel(fields: List<String>): ImportedChannel? {
    val id = fields.getOrNull(0)?.trim()?.takeIf(CHANNEL_ID::matches)
        ?: fields.getOrNull(1)?.trim()?.let(::channelIdOf)
    return id?.let { channel(it, fields.getOrNull(2)) }
}

private fun newPipeChannels(text: String): List<ImportedChannel> {
    if (!nestsWithin(text, MAX_JSON_DEPTH)) return emptyList()
    val export = decodeOrNull(text, NewPipeExport.serializer()) ?: return emptyList()
    return export.subscriptions
        .filter { it.serviceId == NEWPIPE_YOUTUBE_SERVICE }
        .mapNotNull { entry -> entry.url?.let(::channelIdOf)?.let { channel(it, entry.name) } }
}

/** A channel without a readable name (blank, or longer than any real one) shows its id until
 *  its page is opened. */
private fun channel(id: String, name: String?): ImportedChannel =
    ImportedChannel(CHANNEL_URL_PREFIX + id, name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH } ?: id)

/**
 * Calls [onRecord] with the first [keepFields] fields of each RFC 4180 record: a quoted field may
 * hold commas, line breaks and doubled quotes; a record ends at LF or CRLF. Later fields are
 * skipped, and records aren't kept, so a wide or binary file costs no more than its text.
 */
internal fun forEachCsvRecord(text: String, keepFields: Int, onRecord: (List<String>) -> Unit) {
    var fields = ArrayList<String>(keepFields)
    var fieldIndex = 0
    val field = StringBuilder()
    var quoted = false

    fun keep(c: Char) {
        if (fieldIndex < keepFields) field.append(c)
    }

    fun endField() {
        if (fieldIndex < keepFields) fields.add(field.toString())
        field.setLength(0)
        fieldIndex++
    }

    fun endRecord() {
        endField()
        onRecord(fields)
        fields = ArrayList(keepFields)
        fieldIndex = 0
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c != '"') {
                keep(c)
            } else if (text.getOrNull(i + 1) == '"') {
                keep('"')
                i++
            } else {
                quoted = false
            }
        } else {
            when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> Unit
                '\n' -> endRecord()
                else -> keep(c)
            }
        }
        i++
    }
    if (field.isNotEmpty() || fieldIndex > 0) endRecord()
}

/** NewPipe's subscriptions export; other keys are ignored. */
@Serializable
private class NewPipeExport(val subscriptions: List<NewPipeSubscription> = emptyList())

@Serializable
private class NewPipeSubscription(
    @SerialName("service_id") val serviceId: Int? = null,
    val url: String? = null,
    val name: String? = null,
)

private const val TAKEOUT_COLUMNS = 3

/** NewPipe's id for YouTube among its services. */
private const val NEWPIPE_YOUTUBE_SERVICE = 0
