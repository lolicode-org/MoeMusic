package org.lolicode.moemusic.core.contentfilter

import org.lolicode.moemusic.api.event.OnContentFilterArtistRuleChanged
import org.lolicode.moemusic.api.event.OnContentFilterRulesApplied
import org.lolicode.moemusic.api.event.OnContentFilterTrackRuleChanged
import org.lolicode.moemusic.api.model.ContentFilterRuleAction
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.lolicode.moemusic.api.model.ExactArtistFilterRule
import org.lolicode.moemusic.api.model.ExactTrackFilterRule
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.event.CoreEvents
import java.nio.file.Path

data class ContentFilterRuleUpdateResult(
    val changed: Boolean,
    val nowBlocked: Boolean,
    val affectedCount: Int,
)

/**
 * Shared helper for mutating exact-rule config and immediately re-applying the in-memory runtime.
 */
object ContentFilterRuleEditor {

    fun updateTrackRule(
        sourceId: String,
        trackId: String,
        action: ContentFilterRuleAction,
        note: String? = null,
    ): ContentFilterRuleUpdateResult {
        val normalizedRule = ExactTrackFilterRule(
            sourceId = sourceId.trim(),
            trackId = trackId.trim(),
            note = note?.trim()?.takeIf(String::isNotEmpty),
        )
        if (normalizedRule.sourceId.isBlank() || normalizedRule.trackId.isBlank()) {
            return ContentFilterRuleUpdateResult(changed = false, nowBlocked = false, affectedCount = 0)
        }

        val rules = ModConfigManager.config.contentFilter.normalized()
        val normalizedRuleKey = contentFilterExactRuleKey(normalizedRule.sourceId, normalizedRule.trackId)
        val existingIndex = rules.exactTrackRules.indexOfFirst { existing ->
            contentFilterExactRuleKey(existing.sourceId, existing.trackId) == normalizedRuleKey
        }
        val existingRule = rules.exactTrackRules.getOrNull(existingIndex)
        val updatedTrackRules = when (action) {
            ContentFilterRuleAction.BAN -> {
                when {
                    existingRule == null -> rules.exactTrackRules + normalizedRule
                    normalizedRule.note == null || existingRule.note == normalizedRule.note -> rules.exactTrackRules
                    else -> rules.exactTrackRules.toMutableList().apply {
                        this[existingIndex] = existingRule.copy(note = normalizedRule.note)
                    }
                }
            }

            ContentFilterRuleAction.UNBAN -> {
                if (existingRule == null) rules.exactTrackRules else rules.exactTrackRules.filterIndexed { index, _ ->
                    index != existingIndex
                }
            }

            ContentFilterRuleAction.TOGGLE -> {
                if (existingRule != null) {
                    rules.exactTrackRules.filterIndexed { index, _ -> index != existingIndex }
                } else {
                    rules.exactTrackRules + normalizedRule
                }
            }
        }
        val updatedRules = rules.copy(
            enabled = rules.enabled || action != ContentFilterRuleAction.UNBAN,
            exactTrackRules = updatedTrackRules,
        ).normalized()

        val changed = updatedRules != rules
        if (changed) {
            saveRules(updatedRules)
        }

        val result = ContentFilterRuleUpdateResult(
            changed = changed,
            nowBlocked = normalizedRule in updatedRules.exactTrackRules,
            affectedCount = if (changed) 1 else 0,
        )
        CoreEvents.bus.fire(
            OnContentFilterTrackRuleChanged(
                sourceId = normalizedRule.sourceId,
                trackId = normalizedRule.trackId,
                action = action,
                note = normalizedRule.note,
                changed = result.changed,
                nowBlocked = result.nowBlocked,
                affectedCount = result.affectedCount,
            )
        )
        return result
    }

    fun updateArtistRules(
        sourceId: String,
        artistIds: Collection<String>,
        action: ContentFilterRuleAction,
        note: String? = null,
    ): ContentFilterRuleUpdateResult {
        val normalizedSource = sourceId.trim()
        val normalizedNote = note?.trim()?.takeIf(String::isNotEmpty)
        val normalizedRules = artistIds
            .map { artistId -> ExactArtistFilterRule(normalizedSource, artistId.trim(), normalizedNote) }
            .filter { rule -> rule.sourceId.isNotBlank() && rule.artistId.isNotBlank() }
            .distinctBy { contentFilterExactRuleKey(it.sourceId, it.artistId) }
        if (normalizedRules.isEmpty()) {
            return ContentFilterRuleUpdateResult(changed = false, nowBlocked = false, affectedCount = 0)
        }

        val rules = ModConfigManager.config.contentFilter.normalized()
        val updatedRules = rules.exactArtistRules.toMutableList()
        var changedCount = 0
        when (action) {
            ContentFilterRuleAction.BAN -> normalizedRules.forEach { rule ->
                val ruleKey = contentFilterExactRuleKey(rule.sourceId, rule.artistId)
                val existingIndex = updatedRules.indexOfFirst { existing ->
                    contentFilterExactRuleKey(existing.sourceId, existing.artistId) == ruleKey
                }
                if (existingIndex < 0) {
                    updatedRules += rule
                    changedCount += 1
                } else if (rule.note != null && updatedRules[existingIndex].note != rule.note) {
                    updatedRules[existingIndex] = updatedRules[existingIndex].copy(note = rule.note)
                    changedCount += 1
                }
            }

            ContentFilterRuleAction.UNBAN -> normalizedRules.forEach { rule ->
                val ruleKey = contentFilterExactRuleKey(rule.sourceId, rule.artistId)
                val beforeSize = updatedRules.size
                updatedRules.removeAll { existing ->
                    contentFilterExactRuleKey(existing.sourceId, existing.artistId) == ruleKey
                }
                if (updatedRules.size != beforeSize) changedCount += 1
            }

            ContentFilterRuleAction.TOGGLE -> normalizedRules.forEach { rule ->
                val ruleKey = contentFilterExactRuleKey(rule.sourceId, rule.artistId)
                val existingIndex = updatedRules.indexOfFirst { existing ->
                    contentFilterExactRuleKey(existing.sourceId, existing.artistId) == ruleKey
                }
                if (existingIndex >= 0) {
                    updatedRules.removeAt(existingIndex)
                } else {
                    updatedRules += rule
                }
                changedCount += 1
            }
        }

        val updatedConfigRules = rules.copy(
            enabled = rules.enabled || action != ContentFilterRuleAction.UNBAN,
            exactArtistRules = updatedRules,
        ).normalized()
        val changed = updatedConfigRules != rules
        if (changed) {
            saveRules(updatedConfigRules)
        }

        val nowBlocked = normalizedRules.any { target ->
            val targetKey = contentFilterExactRuleKey(target.sourceId, target.artistId)
            updatedConfigRules.exactArtistRules.any { existing ->
                contentFilterExactRuleKey(existing.sourceId, existing.artistId) == targetKey
            }
        }
        val result = ContentFilterRuleUpdateResult(
            changed = changed,
            nowBlocked = nowBlocked,
            affectedCount = changedCount.takeIf { changed } ?: 0,
        )
        CoreEvents.bus.fire(
            OnContentFilterArtistRuleChanged(
                sourceId = normalizedSource,
                artistIds = normalizedRules.map(ExactArtistFilterRule::artistId),
                action = action,
                note = normalizedNote,
                changed = result.changed,
                nowBlocked = result.nowBlocked,
                affectedCount = result.affectedCount,
            )
        )
        return result
    }

    fun applyCurrentConfig() {
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        CoreEvents.bus.fire(OnContentFilterRulesApplied(ContentFilterRuntime.currentRules))
    }

    fun reloadFromDisk(configDir: Path) {
        val reloadedRules = ModConfigManager.readFromDisk(configDir).contentFilter
        ModConfigManager.replaceInMemory { current -> current.copy(contentFilter = reloadedRules) }
        applyCurrentConfig()
    }

    private fun saveRules(updatedRules: ContentFilterRules) {
        ModConfigManager.updateContentFilter { updatedRules }
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        CoreEvents.bus.fire(OnContentFilterRulesApplied(ContentFilterRuntime.currentRules))
    }
}
