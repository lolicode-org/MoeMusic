package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.plugin.Plugin
import java.nio.file.Path

/**
 * Summary of all plugin discovery, validation, and deduplication outcomes
 * performed during [PluginManager.initialize].
 */
data class PluginDiscoveryReport(
    val loadedPlugins: List<PluginCandidateInfo>,
    val duplicatePlugins: List<DeduplicationRecord>,
    val incompatiblePlugins: List<IncompatiblePluginRecord>,
    val failedPlugins: List<PluginFailureRecord>,
) {
    /** Whether any plugins were skipped due to duplication, incompatibility, or load failure. */
    val hasIssues: Boolean
        get() = duplicatePlugins.isNotEmpty() || incompatiblePlugins.isNotEmpty() || failedPlugins.isNotEmpty()
}

/**
 * Information about a plugin candidate discovered from a modloader or standalone jar.
 */
data class PluginCandidateInfo(
    val plugin: Plugin,
    val origin: String,
    val filePath: Path?,
)

/**
 * Record of multiple compatible candidates discovered with the same [pluginId],
 * where [selected] was chosen and [skipped] were discarded.
 */
data class DeduplicationRecord(
    val pluginId: String,
    val selected: PluginCandidateInfo,
    val skipped: List<PluginCandidateInfo>,
)

/**
 * Record of a candidate that failed validation (e.g. API version mismatch or invalid configId).
 */
data class IncompatiblePluginRecord(
    val pluginId: String,
    val version: String,
    val origin: String,
    val filePath: Path?,
    val supportedApiVersions: String,
    val runtimeApiVersion: String,
    val reason: String,
)

/**
 * Record of a standalone plugin jar that could not be read or instantiated.
 */
data class PluginFailureRecord(
    val jarPath: Path,
    val message: String,
    val cause: Throwable?,
)
