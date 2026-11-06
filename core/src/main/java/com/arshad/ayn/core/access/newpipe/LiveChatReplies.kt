package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatBadge
import com.arshad.ayn.core.access.ChatContinuation
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.LiveChatReply
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject

/*
 * InnerTube's live chat shapes (spec 19 LIVE-D6, D16), written fresh from the LIVE.S recordings
 * (`testFixtures/resources/live/`); renderer names and fields cross-checked against yt-dlp's
 * `youtube_live_chat.py` and chat-downloader's catalogue (LIVE-D8). Every token is sent back plain,
 * as the recorder sent the seed: YouTube writes some URL-encoded (`%3D`), some not.
 */

/** The page's chat seed from its `next` answer's conversation bar; `null` when the page has no
 *  chat (none, turned off, or replay not kept). */
internal fun liveChatSeedOf(conversationBar: JsonObject): LiveChatSeed? {
    val chat = conversationBar.getObject("liveChatRenderer")
    val token = chat.getArray("continuations").objects().tokenOf("reloadContinuationData") ?: return null
    return LiveChatSeed(token, replay = chat.getBoolean("isReplay", false))
}

/**
 * A `get_live_chat` answer, or `null` when [answer] isn't one. An InnerTube answer without chat
 * contents is the chat's end (yt-dlp reads it so too): no actions, no next request. An item of a
 * kind not drawn (a placeholder for a held message, a poll, a donation banner) is skipped alone.
 */
internal fun liveChatReplyOf(answer: JsonObject): LiveChatReply? {
    val chat = answer.getObject("continuationContents").getObject("liveChatContinuation")
    if (chat.isEmpty()) {
        return if (answer.has("responseContext")) LiveChatReply(emptyList(), next = null, views = null) else null
    }
    return LiveChatReply(
        actions = chat.getArray("actions").objects().mapNotNull(::chatActionOf),
        next = chat.getArray("continuations").objects().firstNotNullOfOrNull(::liveContinuationOf),
        views = chatViewsOf(chat.getObject("header")),
    )
}

/**
 * A `get_live_chat_replay` answer, or `null` when [answer] isn't one. Each `replayChatItemAction`
 * carries its actions and the play position they belong at; one without a position is skipped. The
 * end ([ReplayPage.next] `null`) is what YouTube answered: no chat contents (as a live answer's
 * end) or no item with a position (yt-dlp follows a replay's next token only past one too); a page
 * whose items are all kinds not drawn goes on.
 */
internal fun replayPageOf(answer: JsonObject): ReplayPage? {
    val chat = answer.getObject("continuationContents").getObject("liveChatContinuation")
    if (chat.isEmpty()) return if (answer.has("responseContext")) ReplayPage.END else null
    val replays = chat.getArray("actions").objects().mapNotNull { action ->
        action.getObject("replayChatItemAction").takeIf { !it.isEmpty() }?.let { replay -> offsetOf(replay)?.let { it to replay } }
    }
    val continuations = chat.getArray("continuations").objects()
    val untilMs = replays.maxOfOrNull { it.first }
    return ReplayPage(
        items = replays.flatMap { (offset, replay) ->
            replay.getArray("actions").objects().mapNotNull { item -> chatActionOf(item)?.let { ReplayItem(offset, it) } }
        }.sortedBy { it.offsetMs },
        next = continuations.tokenOf("liveChatReplayContinuationData").takeIf { untilMs != null },
        seek = continuations.tokenOf("playerSeekContinuationData"),
        views = chatViewsOf(chat.getObject("header")),
        untilMs = untilMs,
    )
}

/** YouTube writes the offset as a string; a number is read too. */
private fun offsetOf(replay: JsonObject): Long? = when (val value = replay.get("videoOffsetTimeMsec")) {
    is String -> value.toLongOrNull()
    is Number -> value.toLong()
    else -> null
}

private fun List<JsonObject>.tokenOf(kind: String): String? =
    firstNotNullOfOrNull { it.getObject(kind).nonEmpty("continuation") }?.let(::plainToken)

private fun liveContinuationOf(continuation: JsonObject): ChatContinuation? =
    LIVE_CONTINUATIONS.firstNotNullOfOrNull { kind ->
        val data = continuation.getObject(kind)
        data.nonEmpty("continuation")?.let { token ->
            ChatContinuation(plainToken(token), timeoutMs = data.getNumber("timeoutMs")?.toLong())
        }
    }

/** YouTube's menu lists Top chat first, Live chat second (titles are localized; yt-dlp picks by
 *  index too). Both or neither. */
private fun chatViewsOf(header: JsonObject): ChatViews? {
    val tokens = header.getObject("liveChatHeaderRenderer").getObject("viewSelector")
        .getObject("sortFilterSubMenuRenderer").getArray("subMenuItems").objects()
        .map { it.getObject("continuation").getObject("reloadContinuationData").nonEmpty("continuation") }
    val top = tokens.getOrNull(0) ?: return null
    val live = tokens.getOrNull(1) ?: return null
    return ChatViews(plainToken(top), plainToken(live))
}

private fun chatActionOf(action: JsonObject): ChatAction? = when {
    action.has("addChatItemAction") ->
        chatMessageOf(action.getObject("addChatItemAction").getObject("item"))?.let(ChatAction::Add)
    action.has("removeChatItemAction") -> removal(action.getObject("removeChatItemAction"))
    action.has("markChatItemAsDeletedAction") -> removal(action.getObject("markChatItemAsDeletedAction"))
    action.has("removeChatItemByAuthorAction") -> authorRemoval(action.getObject("removeChatItemByAuthorAction"))
    action.has("markChatItemsByAuthorAsDeletedAction") ->
        authorRemoval(action.getObject("markChatItemsByAuthorAsDeletedAction"))
    action.has("replaceChatItemAction") -> replacement(action.getObject("replaceChatItemAction"))
    action.has("addBannerToLiveChatCommand") -> pin(action.getObject("addBannerToLiveChatCommand"))
    action.has("removeBannerForLiveChatCommand") ->
        action.getObject("removeBannerForLiveChatCommand").nonEmpty("targetActionId")
            ?.let(ChatAction::Unpin)
    else -> null
}

private fun removal(action: JsonObject): ChatAction? =
    action.nonEmpty("targetItemId")?.let(ChatAction::Remove)

private fun authorRemoval(action: JsonObject): ChatAction? =
    action.nonEmpty("externalChannelId")?.let(ChatAction::RemoveAuthor)

/** A line replaced by a placeholder (or by anything not drawn) is gone from the list. */
private fun replacement(action: JsonObject): ChatAction? {
    val id = action.nonEmpty("targetItemId") ?: return null
    val message = chatMessageOf(action.getObject("replacementItem"))
    return if (message != null) ChatAction.Replace(id, message) else ChatAction.Remove(id)
}

private fun pin(command: JsonObject): ChatAction? {
    val banner = command.getObject("bannerRenderer").getObject("liveChatBannerRenderer")
    val id = banner.nonEmpty("actionId") ?: return null
    val message = chatMessageOf(banner.getObject("contents")) ?: return null
    val title = banner.getObject("header").getObject("liveChatBannerHeaderRenderer").getObject("text").text()
        ?: return null
    return ChatAction.Pin(id, title, message)
}

/** The one renderer inside [item], mapped; `null` for a kind not drawn or a line without an id. */
private fun chatMessageOf(item: JsonObject): ChatMessage? = when {
    item.has("liveChatTextMessageRenderer") -> authored(item.getObject("liveChatTextMessageRenderer"), ChatKind.TEXT)
    item.has("liveChatPaidMessageRenderer") -> item.getObject("liveChatPaidMessageRenderer").let { paid ->
        authored(paid, ChatKind.SUPER_CHAT)?.copy(
            amount = paid.getObject("purchaseAmountText").text(),
            tierColor = paid.argb("headerBackgroundColor"),
        )
    }
    item.has("liveChatPaidStickerRenderer") -> item.getObject("liveChatPaidStickerRenderer").let { sticker ->
        authored(sticker, ChatKind.SUPER_STICKER)?.copy(
            amount = sticker.getObject("purchaseAmountText").text(),
            tierColor = sticker.argb("moneyChipBackgroundColor"),
            stickerUrl = largestUrl(sticker.getObject("sticker").getArray("thumbnails")),
        )
    }
    item.has("liveChatMembershipItemRenderer") -> item.getObject("liveChatMembershipItemRenderer").let { member ->
        authored(member, ChatKind.MEMBERSHIP)?.copy(
            headline = member.getObject("headerPrimaryText").text() ?: member.getObject("headerSubtext").text(),
        )
    }
    item.has("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer") ->
        giftPurchase(item.getObject("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer"))
    item.has("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer") ->
        authored(item.getObject("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer"), ChatKind.MEMBERSHIP)
    item.has("giftMessageViewModel") -> gift(item.getObject("giftMessageViewModel"))
    item.has("liveChatViewerEngagementMessageRenderer") ->
        system(item.getObject("liveChatViewerEngagementMessageRenderer"))
    item.has("liveChatModeChangeMessageRenderer") -> system(item.getObject("liveChatModeChangeMessageRenderer"))
    else -> null
}

/** The fields every authored renderer shares; [ChatMessage.runs] is its `message`. */
private fun authored(renderer: JsonObject, kind: ChatKind): ChatMessage? {
    val id = renderer.nonEmpty("id") ?: return null
    return ChatMessage(
        id = id,
        kind = kind,
        author = renderer.getObject("authorName").text(),
        authorChannelId = renderer.nonEmpty("authorExternalChannelId"),
        authorPhotoUrl = largestUrl(renderer.getObject("authorPhoto").getArray("thumbnails")),
        badges = badgesOf(renderer.getArray("authorBadges")),
        runs = runsOf(renderer.getObject("message")),
    )
}

/** "Gifted 5 Crew memberships": the author block sits in the renderer's header. */
private fun giftPurchase(renderer: JsonObject): ChatMessage? {
    val header = renderer.getObject("header").getObject("liveChatSponsorshipsHeaderRenderer")
    val id = renderer.nonEmpty("id") ?: return null
    return ChatMessage(
        id = id,
        kind = ChatKind.MEMBERSHIP,
        author = header.getObject("authorName").text(),
        authorChannelId = renderer.nonEmpty("authorExternalChannelId"),
        authorPhotoUrl = largestUrl(header.getObject("authorPhoto").getArray("thumbnails")),
        badges = badgesOf(header.getArray("authorBadges")),
        runs = emptyList(),
        headline = header.getObject("primaryText").text(),
    )
}

/** A viewer's gift ("sent Star"), YouTube's view-model shape: no channel id, no badges. */
private fun gift(model: JsonObject): ChatMessage? {
    val id = model.nonEmpty("id") ?: return null
    return ChatMessage(
        id = id,
        kind = ChatKind.GIFT,
        author = model.getObject("authorName").getString("content")?.trim()?.ifEmpty { null },
        authorChannelId = null,
        authorPhotoUrl = largestUrl(model.getObject("authorAvatar").getObject("avatarViewModel").getObject("image").getArray("sources")),
        badges = emptySet(),
        runs = emptyList(),
        headline = model.getObject("text").getString("content")?.trim()?.ifEmpty { null },
    )
}

/** YouTube's own notice (subscribers-only mode; slow mode, whose `subtext` is its detail line
 *  "Send a message every 1 second", the headline): no author. */
private fun system(renderer: JsonObject): ChatMessage? {
    val id = renderer.nonEmpty("id") ?: return null
    return ChatMessage(
        id = id,
        kind = ChatKind.SYSTEM,
        author = null,
        authorChannelId = null,
        authorPhotoUrl = null,
        badges = emptySet(),
        runs = runsOf(renderer.getObject("message")) + runsOf(renderer.getObject("text")),
        headline = renderer.getObject("subtext").text(),
    )
}

private fun badgesOf(badges: JsonArray): Set<ChatBadge> = badges.objects().mapNotNull { badge ->
    val renderer = badge.getObject("liveChatAuthorBadgeRenderer")
    when {
        renderer.has("customThumbnail") -> ChatBadge.MEMBER
        else -> when (renderer.getObject("icon").getString("iconType")) {
            "OWNER" -> ChatBadge.OWNER
            "MODERATOR" -> ChatBadge.MODERATOR
            "VERIFIED" -> ChatBadge.VERIFIED
            else -> null
        }
    }
}.toSet()

/** Text runs as written; a Unicode emoji as its character; a channel's emoji as its picture. */
private fun runsOf(message: JsonObject): List<ChatRun> = message.getArray("runs").objects().mapNotNull { run ->
    if (!run.has("emoji")) return@mapNotNull run.nonEmpty("text")?.let(ChatRun::Text)
    val emoji = run.getObject("emoji")
    val text = emojiText(emoji) ?: return@mapNotNull null
    val image = largestUrl(emoji.getObject("image").getArray("thumbnails"))
    if (emoji.getBoolean("isCustomEmoji", false) && image != null) ChatRun.CustomEmoji(text, image) else ChatRun.Text(text)
}

/** A Unicode emoji's character; a channel's emoji's shortcut (`:gar:`), its id being opaque. */
private fun emojiText(emoji: JsonObject): String? {
    val label = (emoji.getArray("shortcuts").firstOrNull() as? String)?.ifEmpty { null }
        ?: emoji.getObject("image").getObject("accessibility").getObject("accessibilityData").nonEmpty("label")
    return if (emoji.getBoolean("isCustomEmoji", false)) label else emoji.nonEmpty("emojiId") ?: label
}

/** `simpleText`, or the runs' text joined (an emoji as [emojiText]). */
private fun JsonObject.text(): String? =
    nonEmpty("simpleText")
        ?: getArray("runs").objects()
            .joinToString("") { it.getString("text") ?: emojiText(it.getObject("emoji")).orEmpty() }
            .ifEmpty { null }

/** A string field, blank as absent. */
private fun JsonObject.nonEmpty(key: String): String? = getString(key)?.ifEmpty { null }

/** The widest picture's URL; a protocol-relative one (`//…`) gets `https:`. */
private fun largestUrl(pictures: JsonArray): String? =
    pictures.objects().maxByOrNull { it.getInt("width", 0) }?.nonEmpty("url")
        ?.let { if (it.startsWith("//")) "https:$it" else it }

/** YouTube writes colours as unsigned ARGB numbers. */
private fun JsonObject.argb(key: String): Int? = getNumber(key)?.toLong()?.toInt()

private fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()

/** Only `%XX` escapes are undone (a token is base64: ASCII), so a `+` stays a plus. */
internal fun plainToken(token: String): String {
    if ('%' !in token) return token
    val out = StringBuilder(token.length)
    var i = 0
    while (i < token.length) {
        val hex = if (token[i] == '%' && i + 2 <= token.lastIndex) hexByte(token[i + 1], token[i + 2]) else null
        if (hex != null) {
            out.append(hex.toChar())
            i += 3
        } else {
            out.append(token[i])
            i++
        }
    }
    return out.toString()
}

private fun hexByte(high: Char, low: Char): Int? {
    val h = Character.digit(high, 16)
    val l = Character.digit(low, 16)
    return if (h < 0 || l < 0) null else h * 16 + l
}

/** The continuations a live chat moves on with, in the order they are taken. */
private val LIVE_CONTINUATIONS = listOf("invalidationContinuationData", "timedContinuationData", "reloadContinuationData")
