package com.arshad.ayn.core.access.newpipe

/**
 * The LIVE.S chat recordings (`resources/live/`, 2026-11-02, lofi live `rFZHOHl-L8A`) and
 * SmartTube's MIT chat fixtures (`resources/live/smarttube/`, THIRD-PARTY.md), with goldens read
 * from the raw payloads (TEST-ORACLE-01).
 */
object RecordedLiveChat {
    /** The first `get_live_chat` answer (the page's seed): 64 text lines, 2 gifts, 1 notice,
     *  6 removals, 1 pinned message (74 actions). */
    const val FIRST = "live-chat-first.json"

    /** The answer to the first's Live chat token: the same header, Live chat selected. */
    const val LIVE_VIEW = "live-chat-menu-1.json"

    /** SmartTube 2022: badges (member, moderator, verified), retractions, a placeholder replace. */
    const val SMARTTUBE_BADGES = "smarttube/get_live_chat2.json"

    /** SmartTube 2022: a timed continuation (4904 ms). */
    const val SMARTTUBE_TIMED = "smarttube/get_live_chat.json"

    /** SmartTube: an answer holding only a reload continuation. */
    const val SMARTTUBE_RELOAD = "smarttube/reload_live_chat.json"

    /** Next pages' `conversationBar`s: live chat, a kept replay, chat off, replay off. */
    const val NEXT_LIVE = "next-live.json"
    const val NEXT_REPLAY = "next-Z1sqWFs86uU.json"
    const val NEXT_CHAT_OFF = "next-ROboUHfPUrU.json"
    const val NEXT_REPLAY_OFF = "next-Iz6qdzVCN9g.json"

    const val FIRST_ACTION_COUNT = 74

    /** [FIRST]'s invalidation continuation (10 000 ms), its `%3D` decoded. */
    const val FIRST_NEXT_TOKEN =
        "0ofMyAOBAxpeQ2lrcUp3b1lWVU5UU2pSbmExWkROazV5ZGtsSk9IVnRlblJtTUU5M0VndHlSbHBJVDBoc0xVdzRRUm9UNnFqZHVRRU5DZ3R5UmxwSVQwaHNMVXc0UVNBQk1BQSUzRCjB-8Dc4OiXAzAAQAJK2gEIARgAIABKCBAAGAAgADAAUNqW9d3g6JcDWAR4AKIBAhoAqgEMEAAaACIAKgQIABAAsAEAwAEAyAHalvXd4OiXA-IBDAjV9aDXBhD9j4GPAugBAPgBAIgCAJACAJoCDAjV9aDXBhD9j4GPAtgCAOICAIgDAJIDYAoeChhVQ01GQTZoTFJoUXA1Slp0LTN0QVRXVlEQ_v0BCh4KGFVDY01HelZMYUxOODVIejMzb0dET2pWURDo9wEKHgoYVUNLRnc1aGtKeDgtMnNnRm1FMjB0SXlREI73AbgDAVDhs_fc4OiXA1jf9ajPgd2XA4IBBggEGAEwAIgBAKABntX33eDolwOyAQC6AQIICtAB0_Wg1wY="

    /** [FIRST]'s header: Top chat, then Live chat (decoded). */
    const val FIRST_TOP_TOKEN =
        "0ofMyAPRAhpeQ2lrcUp3b1lWVU5UU2pSbmExWkROazV5ZGtsSk9IVnRlblJtTUU5M0VndHlSbHBJVDBoc0xVdzRRUm9UNnFqZHVRRU5DZ3R5UmxwSVQwaHNMVXc0UVNBQk1BQSUzRDABStoBCAEYACAASggQABgAIAAwAFDalvXd4OiXA1gEeACiAQIaAKoBDBAAGgAiACoECAAQALABAMABAMgB2pb13eDolwPiAQwI1fWg1wYQ_Y-BjwLoAQD4AQCIAgCQAgCaAgwI1fWg1wYQ_Y-BjwLYAgDiAgCIAwCSA2AKHgoYVUNNRkE2aExSaFFwNUpadC0zdEFUV1ZREP79AQoeChhVQ2NNR3pWTGFMTjg1SHozM29HRE9qVlEQ6PcBCh4KGFVDS0Z3NWhrSng4LTJzZ0ZtRTIwdEl5URCO9wG4AwGCAQwIBBgBIAAoATAAOACoAQE="
    const val FIRST_LIVE_TOKEN =
        "0ofMyAPRAhpeQ2lrcUp3b1lWVU5UU2pSbmExWkROazV5ZGtsSk9IVnRlblJtTUU5M0VndHlSbHBJVDBoc0xVdzRRUm9UNnFqZHVRRU5DZ3R5UmxwSVQwaHNMVXc0UVNBQk1BQSUzRDABStoBCAEYACAASggQABgAIAAwAFDalvXd4OiXA1gEeACiAQIaAKoBDBAAGgAiACoECAAQALABAMABAMgB2pb13eDolwPiAQwI1fWg1wYQ_Y-BjwLoAQD4AQCIAgCQAgCaAgwI1fWg1wYQ_Y-BjwLYAgDiAgCIAwCSA2AKHgoYVUNNRkE2aExSaFFwNUpadC0zdEFUV1ZREP79AQoeChhVQ2NNR3pWTGFMTjg1SHozM29HRE9qVlEQ6PcBCh4KGFVDS0Z3NWhrSng4LTJzZ0ZtRTIwdEl5URCO9wG4AwGCAQwIARgBIAAoATAAOACoAQE="

    /** [NEXT_LIVE]'s seed, its `%3D%3D` decoded. */
    const val NEXT_LIVE_SEED =
        "0ofMyAOEARpeQ2lrcUp3b1lWVU5UU2pSbmExWkROazV5ZGtsSk9IVnRlblJtTUU5M0VndHlSbHBJVDBoc0xVdzRRUm9UNnFqZHVRRU5DZ3R5UmxwSVQwaHNMVXc0UVNBQk1BQSUzRDABggEMCAQYAiAAKAAwADgAiAEBoAHjh7rd4OiXA6gBALIBAA=="

    /** `get_live_chat_replay` answers for LTT `Z1sqWFs86uU` (13 090 s): the seed's page at 0 ms
     *  (header: Top chat replay, Live chat replay), page 20 of the walk, a seek to 6 545 000 ms and
     *  one to 12 970 000 ms. Each item's renderer is a text line but for [REPLAY_FIRST]'s notice;
     *  placeholders (held lines) aren't drawn. */
    const val REPLAY_FIRST = "replay-Z1sqWFs86uU-first.json"
    const val REPLAY_MIDDLE = "replay-Z1sqWFs86uU-middle.json"
    const val REPLAY_SEEK = "replay-Z1sqWFs86uU-seek.json"
    const val REPLAY_LAST = "replay-Z1sqWFs86uU-last.json"

    /** [NEXT_REPLAY]'s seed. */
    const val NEXT_REPLAY_SEED =
        "op2w0wRyGl5DaWtxSndvWVZVTllkWEZUUW14SVFVVTJXSGN0ZVdWS1FUQlVkVzUzRWd0YU1YTnhWMFp6T0RaMVZSb1Q2cWpkdVFFTkNndGFNWE54VjBaek9EWjFWU0FCTUFBJTNEQAFyDAgEGAIgACgAMAA4AHgB"

    /** [REPLAY_FIRST]'s next and seek tokens and its two views, each `%3D` decoded. */
    const val REPLAY_FIRST_NEXT =
        "op2w0wR9Gl5DaWtxSndvWVZVTllkWEZUUW14SVFVVTJXSGN0ZVdWS1FUQlVkVzUzRWd0YU1YTnhWMFp6T0RaMVZSb1Q2cWpkdVFFTkNndGFNWE54VjBaek9EWjFWU0FCTUFBJTNEKNTBoh9AAEgDUgggALABAPgBAHIGCAQYATAAeAA="
    const val REPLAY_FIRST_SEEK =
        "op2w0wR9Gl5DaWtxSndvWVZVTllkWEZUUW14SVFVVTJXSGN0ZVdWS1FUQlVkVzUzRWd0YU1YTnhWMFp6T0RaMVZSb1Q2cWpkdVFFTkNndGFNWE54VjBaek9EWjFWU0FCTUFBJTNEKNTBoh9AAEgEUgggALABAPgBAHIGCAQYATAAeAA="
    const val REPLAY_TOP_TOKEN =
        "op2w0wR6Gl5DaWtxSndvWVZVTllkWEZUUW14SVFVVTJXSGN0ZVdWS1FUQlVkVzUzRWd0YU1YTnhWMFp6T0RaMVZSb1Q2cWpkdVFFTkNndGFNWE54VjBaek9EWjFWU0FCTUFBJTNEQAFSCCAAsAEA-AEAcgwIBBgBIAAoATAAOAA="
    const val REPLAY_LIVE_TOKEN =
        "op2w0wR6Gl5DaWtxSndvWVZVTllkWEZUUW14SVFVVTJXSGN0ZVdWS1FUQlVkVzUzRWd0YU1YTnhWMFp6T0RaMVZSb1Q2cWpkdVFFTkNndGFNWE54VjBaek9EWjFWU0FCTUFBJTNEQAFSCCAAsAEA-AEAcgwIARgBIAAoATAAOAA="

    fun text(name: String): String =
        checkNotNull(RecordedLiveChat::class.java.getResourceAsStream("/live/$name")) { name }
            .use { it.readBytes().decodeToString() }
}
