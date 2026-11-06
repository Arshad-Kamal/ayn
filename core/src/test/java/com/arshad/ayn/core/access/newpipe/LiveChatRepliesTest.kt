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
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Chat answers and seeds read from the LIVE.S recordings and SmartTube's fixtures
 * ([RecordedLiveChat]); kinds no recording holds (Super Chat, Super Sticker, membership, author
 * removal, unpin) from minimal answers in the shapes yt-dlp and chat-downloader read.
 */
class LiveChatRepliesTest {

    @Test
    fun firstAnswer_readsEveryAction_byKind() {
        val actions = reply(RecordedLiveChat.FIRST).actions

        assertEquals(RecordedLiveChat.FIRST_ACTION_COUNT, actions.size)
        assertEquals(
            mapOf("Pin" to 1, "Add" to 67, "Remove" to 6),
            actions.groupingBy { it::class.simpleName!! }.eachCount(),
        )
    }

    @Test
    fun firstAnswer_textLine_carriesAuthorPhotoAndText() {
        val expected = ChatMessage(
            id = "ChwKGkNPT1FnYVhnNkpjREZka1N6QVFkQmFrazJR",
            kind = ChatKind.TEXT,
            author = "@diksha-sha",
            authorChannelId = "UCVET9V8F9o_23JMUKNuueuw",
            authorPhotoUrl = "https://yt4.ggpht.com/h96eQREN1o8LSiD08VhA0dz-QFhY-d5T2-uxA5UtNnkhubgk2xwFMexclkp1s5l_zO2Tg4LIqw=s64-c-k-c0x00ffffff-no-rj",
            badges = emptySet(),
            runs = listOf(ChatRun.Text("han dikh gyi ..ye ek bnda dimag kha rha tha")),
        )

        assertEquals(ChatAction.Add(expected), reply(RecordedLiveChat.FIRST).actions[3])
    }

    @Test
    fun firstAnswer_unicodeEmojiIsText_channelEmojiIsItsLargestPicture() {
        val add = reply(RecordedLiveChat.FIRST).actions[10] as ChatAction.Add

        assertEquals(
            listOf(
                ChatRun.Text("\uD83D\uDE44"),
                ChatRun.CustomEmoji(":gar:", "https://yt3.ggpht.com/pxQTF9D-uxlSIgoopRcS8zAZnBBEPp2R9bwo5qIc3kc7PF2k18so72-ohINWPa6OvWudEcsC=w48-h48-c-k-nd"),
                ChatRun.Text("-"),
            ),
            add.message.runs,
        )
    }

    @Test
    fun firstAnswer_pinnedMessage_namesWhoPinnedItAndKeepsTheAuthorsBadges() {
        val pin = reply(RecordedLiveChat.FIRST).actions[0] as ChatAction.Pin

        assertEquals("ChwKGkNNT0h6cVRGeHBjREZkSk5xd0lkaEtNWENn", pin.bannerId)
        assertEquals("Pinned by Lofi Girl", pin.title)
        assertEquals("ChwKGkNNSHR6S0RGeHBjREZZWEVQd1FkeVZRSldB" to setOf(ChatBadge.VERIFIED, ChatBadge.OWNER), pin.message.id to pin.message.badges)
        assertEquals(ChatRun.Text("\uD83C\uDFA7"), pin.message.runs.first())
    }

    @Test
    fun firstAnswer_viewersGift_isAGiftLineWithItsWords() {
        val gift = (reply(RecordedLiveChat.FIRST).actions[1] as ChatAction.Add).message

        assertEquals(
            listOf(ChatKind.GIFT, "@dikshantdethe748", "sent Star", "https://yt4.ggpht.com/Q5THHanaRmPfxyhQ6ndEbSfpckW6henFreARfXHlBaTNtPzFQm2wjOFI91-c2pm3Bx3SnJp-mQ=s64-c-k-c0x00ffffff-no-rj"),
            listOf(gift.kind, gift.author, gift.headline, gift.authorPhotoUrl),
        )
    }

    @Test
    fun firstAnswer_youTubesNotice_isASystemLineWithoutAuthor() {
        val notice = (reply(RecordedLiveChat.FIRST).actions[73] as ChatAction.Add).message

        assertEquals(
            listOf(ChatKind.SYSTEM, null, listOf(ChatRun.Text("Subscribers-only mode. Messages that appear are from people who subscribe to this channel."))),
            listOf(notice.kind, notice.author, notice.runs),
        )
    }

    @Test
    fun firstAnswer_moderatorRemoval_namesTheRemovedLine() {
        assertEquals(ChatAction.Remove("ChwKGkNMbVZfSjdnNkpjREZmZmY1d01kaWFrOEpn"), reply(RecordedLiveChat.FIRST).actions[24])
    }

    @Test
    fun firstAnswer_nextRequestAndViews_areDecoded() {
        val reply = reply(RecordedLiveChat.FIRST)

        assertEquals(ChatContinuation(RecordedLiveChat.FIRST_NEXT_TOKEN, 10_000L), reply.next)
        assertEquals(ChatViews(RecordedLiveChat.FIRST_TOP_TOKEN, RecordedLiveChat.FIRST_LIVE_TOKEN), reply.views)
    }

    @Test
    fun liveViewAnswer_stillListsTopChatFirst() {
        val views = reply(RecordedLiveChat.LIVE_VIEW).views!!
        val titles = json(RecordedLiveChat.LIVE_VIEW).getObject("continuationContents").getObject("liveChatContinuation")
            .getObject("header").getObject("liveChatHeaderRenderer").getObject("viewSelector")
            .getObject("sortFilterSubMenuRenderer").getArray("subMenuItems")
            .filterIsInstance<JsonObject>()
            .map { it.getString("title") to plainToken(it.getObject("continuation").getObject("reloadContinuationData").getString("continuation")) }

        assertEquals(listOf("Top chat" to views.top, "Live chat" to views.live), titles)
    }

    @Test
    fun smartTubeAnswer_readsMemberModeratorAndVerifiedBadges() {
        val actions = reply(RecordedLiveChat.SMARTTUBE_BADGES).actions
        val badges = actions.filterIsInstance<ChatAction.Add>().associate { it.message.author to it.message.badges }

        assertEquals(setOf(ChatBadge.MODERATOR, ChatBadge.MEMBER), badges["Gaming with Phoenix TV"])
        assertEquals(setOf(ChatBadge.VERIFIED, ChatBadge.MODERATOR), badges["Nightbot"])
        assertEquals(emptySet<ChatBadge>(), badges["M.Vieira"])
    }

    /** A held line's placeholder is never drawn; replaced by one, the line is gone; a retracted
     *  line is gone. */
    @Test
    fun smartTubeAnswer_placeholdersAndRetractionsRemoveLines() {
        val actions = reply(RecordedLiveChat.SMARTTUBE_BADGES).actions

        assertEquals(16, actions.size)
        assertEquals(
            listOf(
                ChatAction.Remove("CkUKGkNNR09ySm1GMl9nQ0ZSY0sxZ0Fka3NzSlpnEidDTHF3djVPRTJfZ0NGYmJLVHdnZEc5Y0ZPUTE2NTY3OTM2MTgwNDU%3D"),
                ChatAction.Remove("CkUKGkNLYnNwWk9IMl9nQ0ZYeXU1UWNkR3VRRmJ3EidDT1c2eV95RzJfZ0NGYk9Vd2dFZGtfa0k5dzE2NTY3OTQxNDI2OTE%3D"),
                ChatAction.Remove("CkUKGkNLM0Zvc2lIMl9nQ0ZiLXI1UWNkcXFRQzl3EidDT1c2eV95RzJfZ0NGYk9Vd2dFZGtfa0k5dzE2NTY3OTQyNTM4MDM%3D"),
            ),
            actions.filterIsInstance<ChatAction.Remove>(),
        )
    }

    @Test
    fun smartTubeTimedAnswer_waitsWhatYouTubeAsks() {
        assertEquals(4904L, reply(RecordedLiveChat.SMARTTUBE_TIMED).next?.timeoutMs)
    }

    @Test
    fun reloadOnlyAnswer_continuesWithoutAWait() {
        val reply = reply(RecordedLiveChat.SMARTTUBE_RELOAD)

        assertEquals(emptyList<ChatAction>() to null, reply.actions to reply.next?.timeoutMs)
        assertEquals(true, reply.next?.token?.isNotEmpty())
    }

    @Test
    fun innerTubeAnswerWithoutChat_isTheEnd() {
        assertEquals(LiveChatReply(emptyList(), null, null), liveChatReplyOf(parse("""{"responseContext":{}}""")))
    }

    @Test
    fun answerThatIsntInnerTube_isNotAChatAnswer() {
        assertNull(liveChatReplyOf(parse("""{"error":{"code":400}}""")))
    }

    @Test
    fun superChat_carriesItsAmountTierColourAndMessage() {
        val message = single(
            """{"liveChatPaidMessageRenderer":{"id":"p1","authorName":{"simpleText":"Sofia"},"authorExternalChannelId":"UCs",
            "purchaseAmountText":{"simpleText":"$5.00"},"headerBackgroundColor":4278239141,"bodyBackgroundColor":4280150454,
            "message":{"runs":[{"text":"Great view"}]}}}""",
        )

        assertEquals(
            listOf(ChatKind.SUPER_CHAT, "$5.00", 0xFF00BFA5.toInt(), listOf(ChatRun.Text("Great view"))),
            listOf(message.kind, message.amount, message.tierColor, message.runs),
        )
    }

    @Test
    fun superSticker_carriesItsPictureWithHttps() {
        val message = single(
            """{"liveChatPaidStickerRenderer":{"id":"s1","authorName":{"simpleText":"Sofia"},
            "purchaseAmountText":{"simpleText":"$2.00"},"moneyChipBackgroundColor":4280191205,
            "sticker":{"thumbnails":[{"url":"//lh3.googleusercontent.com/a=s40","width":40},{"url":"//lh3.googleusercontent.com/a=s80","width":80}]}}}""",
        )

        assertEquals(
            listOf(ChatKind.SUPER_STICKER, "$2.00", 0xFF1E88E5.toInt(), "https://lh3.googleusercontent.com/a=s80", emptyList<ChatRun>()),
            listOf(message.kind, message.amount, message.tierColor, message.stickerUrl, message.runs),
        )
    }

    @Test
    fun newMember_headlineIsYouTubesWelcome() {
        val message = single(
            """{"liveChatMembershipItemRenderer":{"id":"m1","authorName":{"simpleText":"Diego"},
            "headerSubtext":{"runs":[{"text":"Welcome to "},{"text":"Crew"},{"text":"!"}]}}}""",
        )

        assertEquals(ChatKind.MEMBERSHIP to "Welcome to Crew!", message.kind to message.headline)
    }

    @Test
    fun memberMilestone_headlineIsItsLength_messageIsTheBody() {
        val message = single(
            """{"liveChatMembershipItemRenderer":{"id":"m2","authorName":{"simpleText":"Diego"},
            "headerPrimaryText":{"runs":[{"text":"Member for "},{"text":"6"},{"text":" months"}]},"headerSubtext":{"simpleText":"Crew"},
            "message":{"runs":[{"text":"half a year!"}]}}}""",
        )

        assertEquals("Member for 6 months" to listOf(ChatRun.Text("half a year!")), message.headline to message.runs)
    }

    @Test
    fun giftedMemberships_takeTheAuthorFromTheirHeader() {
        val message = single(
            """{"liveChatSponsorshipsGiftPurchaseAnnouncementRenderer":{"id":"g1","authorExternalChannelId":"UCg",
            "header":{"liveChatSponsorshipsHeaderRenderer":{"authorName":{"simpleText":"Mia"},
            "primaryText":{"runs":[{"text":"Gifted "},{"text":"5"},{"text":" Crew memberships"}]}}}}}""",
        )

        assertEquals(
            listOf(ChatKind.MEMBERSHIP, "Mia", "UCg", "Gifted 5 Crew memberships"),
            listOf(message.kind, message.author, message.authorChannelId, message.headline),
        )
    }

    @Test
    fun replacementThatIsALine_replacesIt() {
        val actions = liveChatReplyOf(
            parse(
                """{"continuationContents":{"liveChatContinuation":{"actions":[{"replaceChatItemAction":{"targetItemId":"t1",
                "replacementItem":{"liveChatTextMessageRenderer":{"id":"t1","authorName":{"simpleText":"Ana"},
                "message":{"runs":[{"text":"edited"}]}}}}}]}}}""",
            ),
        )!!.actions

        assertEquals(
            listOf(ChatAction.Replace("t1", ChatMessage("t1", ChatKind.TEXT, "Ana", null, null, emptySet(), listOf(ChatRun.Text("edited"))))),
            actions,
        )
    }

    @Test
    fun giftedMembershipReceived_isAMembershipLineWithYouTubesWords() {
        val message = single(
            """{"liveChatSponsorshipsGiftRedemptionAnnouncementRenderer":{"id":"r1","authorName":{"simpleText":"Leo"},
            "message":{"runs":[{"text":"received a gift membership by "},{"text":"Mia"}]}}}""",
        )

        assertEquals(
            listOf(ChatKind.MEMBERSHIP, "Leo", listOf(ChatRun.Text("received a gift membership by "), ChatRun.Text("Mia"))),
            listOf(message.kind, message.author, message.runs),
        )
    }

    /** The notice's detail line is its headline, not run on into its words. */
    @Test
    fun slowModeNotice_keepsItsDetailApart() {
        val message = single(
            """{"liveChatModeChangeMessageRenderer":{"id":"n1","text":{"runs":[{"text":"Slow mode is on"}]},
            "subtext":{"runs":[{"text":"Send a message every "},{"text":"1 second"}]}}}""",
        )

        assertEquals(
            listOf(ChatKind.SYSTEM, listOf(ChatRun.Text("Slow mode is on")), "Send a message every 1 second"),
            listOf(message.kind, message.runs, message.headline),
        )
    }

    @Test
    fun authorRemovalsAndUnpin_areRead() {
        val actions = liveChatReplyOf(
            parse(
                """{"continuationContents":{"liveChatContinuation":{"actions":[
                {"markChatItemsByAuthorAsDeletedAction":{"externalChannelId":"UCa"}},
                {"removeChatItemByAuthorAction":{"externalChannelId":"UCb"}},
                {"removeBannerForLiveChatCommand":{"targetActionId":"b1"}}]}}}""",
            ),
        )!!.actions

        assertEquals(listOf(ChatAction.RemoveAuthor("UCa"), ChatAction.RemoveAuthor("UCb"), ChatAction.Unpin("b1")), actions)
    }

    @Test
    fun liveChatPage_seedsTopChat() {
        assertEquals(LiveChatSeed(RecordedLiveChat.NEXT_LIVE_SEED, replay = false), seed(RecordedLiveChat.NEXT_LIVE))
    }

    @Test
    fun endedStreamWithItsChat_seedsTheReplay() {
        assertEquals(true, seed(RecordedLiveChat.NEXT_REPLAY)?.replay)
    }

    @Test
    fun chatOffAndReplayOff_haveNoSeed() {
        assertEquals(listOf(null, null), listOf(seed(RecordedLiveChat.NEXT_CHAT_OFF), seed(RecordedLiveChat.NEXT_REPLAY_OFF)))
    }

    @Test
    fun plainToken_undoesOnlyPercentEscapes() {
        assertEquals(listOf("ab==", "a+b/c", "a%G1", "a%3"), listOf("ab%3D%3D", "a+b%2Fc", "a%G1", "a%3").map(::plainToken))
    }

    private fun reply(name: String): LiveChatReply = liveChatReplyOf(json(name))!!

    private fun seed(name: String): LiveChatSeed? =
        liveChatSeedOf(json(name).getObject("contents").getObject("twoColumnWatchNextResults").getObject("conversationBar"))

    private fun single(item: String): ChatMessage {
        val reply = liveChatReplyOf(parse("""{"continuationContents":{"liveChatContinuation":{"actions":[{"addChatItemAction":{"item":$item}}]}}}"""))
        return (reply!!.actions.single() as ChatAction.Add).message
    }

    private fun json(name: String): JsonObject = parse(RecordedLiveChat.text(name))

    private fun parse(text: String): JsonObject = JsonParser.`object`().from(text)
}
