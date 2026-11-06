package com.arshad.ayn.core.filter.packs

import com.google.re2j.Pattern
import java.text.Normalizer

/**
 * A title or channel name as the packs read it: NFKC, as owner patterns read titles (KP-D20);
 * every pack pattern is case-insensitive. Normalize once per video, then ask every set.
 */
@JvmInline
value class PackText private constructor(val value: String) {
    companion object {
        fun of(text: String): PackText = PackText(Normalizer.normalize(text, Normalizer.Form.NFKC))
    }
}

/**
 * One pack rule (FPK-D9): it matches a text when [pattern] does and none of [unless] does. The
 * report's ICU lookarounds become [unless] patterns, so every rule runs on RE2/J in linear time.
 */
class PackRule(pattern: String, unless: List<String> = emptyList()) {
    private val pattern: Pattern = compile(pattern)
    private val unless: List<Pattern> = unless.map(::compile)

    internal val plainSource: String? = pattern.takeIf { unless.isEmpty() }

    fun matches(text: PackText): Boolean =
        pattern.matcher(text.value).find() && unless.none { it.matcher(text.value).find() }
}

/**
 * One block list of a pack: a tier or an option (FPK-D6, D8). It hides a video by its channel
 * id, a rule on its title, or a rule on its channel name (FPK-D5, D11), unless the title matches
 * one of [exemptTitles] (the music set's nasheed markers, FPK-D3).
 */
class BlockSet(
    /** YouTube channel ids (`UC…`). */
    val channelIds: Set<String>,
    val titleRules: List<PackRule>,
    val channelNameRules: List<PackRule> = emptyList(),
    private val exemptTitles: List<PackRule> = emptyList(),
) {
    // Rules without exemptions run as one alternation: one RE2 pass per title, not one per rule.
    private val titles = RuleGroup(titleRules)
    private val channelNames = RuleGroup(channelNameRules)

    /** Whether this set hides a video from [channelId] titled [title] on a channel named
     *  [channelName]; what isn't known can't match. */
    fun hides(channelId: String?, title: PackText?, channelName: PackText?): Boolean {
        if (title != null && exemptTitles.any { it.matches(title) }) return false
        return (channelId != null && channelId in channelIds) ||
            (title != null && titles.matches(title)) ||
            (channelName != null && channelNames.matches(channelName))
    }
}

private class RuleGroup(rules: List<PackRule>) {
    private val combined: Pattern? = rules.mapNotNull { it.plainSource }
        .takeIf { it.isNotEmpty() }
        ?.let { sources -> compile(sources.joinToString("|") { "(?:$it)" }) }
    private val guarded: List<PackRule> = rules.filter { it.plainSource == null }

    fun matches(text: PackText): Boolean =
        combined?.matcher(text.value)?.find() == true || guarded.any { it.matches(text) }
}

/** Shipped patterns are fixed: one that doesn't compile is a build bug, so this throws. */
private fun compile(pattern: String): Pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
