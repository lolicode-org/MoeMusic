package org.lolicode.moemusic.clientcore.hud

import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.core.config.HudAnchor
import org.lolicode.moemusic.core.config.HudCoverSide
import org.lolicode.moemusic.core.config.HudProgressBarPosition
import org.lolicode.moemusic.core.config.NowPlayingHudConfig

object NowPlayingHudModel {

    data class HudLine(
        val text: String,
        val color: Int,
    )

    data class Layout(
        val panelX: Int,
        val panelY: Int,
        val panelWidth: Int,
        val panelHeight: Int,
        val contentX: Int,
        val contentY: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val coverX: Int,
        val coverY: Int,
        val coverSize: Int,
        val textX: Int,
        val textWidth: Int,
        val textScale: Float,
        val scaledFontHeight: Int,
        val textLineGap: Int,
        val textBlockHeight: Int,
        val metadataLines: List<HudLine>,
        val primaryLyric: HudLine?,
        val secondaryLyric: HudLine?,
        val progressX: Int,
        val progressY: Int?,
        val progressWidth: Int,
        val progressHeight: Int,
    )

    data class TextMetrics(
        val scale: Float,
        val scaledFontHeight: Int,
        val textLineGap: Int,
        val textBlockHeight: Int,
    )

    fun metadataLines(ctx: TrackContext, config: NowPlayingHudConfig, positionMs: Long): List<HudLine> {
        val primaryColor = parseArgb(config.textColorArgb)
        val secondaryColor = parseArgb(config.secondaryTextColorArgb)
        val lines = mutableListOf<HudLine>()
        if (config.showTitle) lines += HudLine(ctx.track.title, primaryColor)
        if (config.showArtist) lines += HudLine(ctx.track.artistDisplay.ifBlank { "-" }, secondaryColor)
        if (config.showAlbum) lines += HudLine(ctx.track.album ?: "-", secondaryColor)
        if (config.showTime) lines += HudLine(buildTimeString(positionMs, ctx.track.durationMs), secondaryColor)
        return lines
    }

    fun lyricLine(text: String?, colorArgb: String, showLyrics: Boolean): HudLine? {
        if (!showLyrics) return null
        return HudLine(text.orEmpty(), parseArgb(colorArgb))
    }

    fun computeLayout(
        guiWidth: Int,
        guiHeight: Int,
        fontLineHeight: Int,
        config: NowPlayingHudConfig,
        metadataLines: List<HudLine>,
        primaryLyric: HudLine?,
        secondaryLyric: HudLine?,
    ): Layout? {
        val lineCount = metadataLines.size + listOfNotNull(primaryLyric, secondaryLyric).size
        val contentHeight = config.verticalSize
        val textMetrics = computeTextMetrics(fontLineHeight, contentHeight, lineCount)
        val coverSize = if (config.showCover) contentHeight else 0
        val hasProgress = config.showProgressBar
        val progressExtraHeight = if (hasProgress) GAP + PROGRESS_HEIGHT else 0
        val hasText = metadataLines.isNotEmpty() || primaryLyric != null || secondaryLyric != null
        val textWidth = if (hasText) config.textMaxWidth else 0
        val contentWidth = when {
            coverSize > 0 && textWidth > 0 -> coverSize + GAP + textWidth
            coverSize > 0 -> coverSize
            textWidth > 0 -> textWidth
            else -> return null
        }
        val panelWidth = CONTENT_PADDING * 2 + contentWidth
        val panelHeight = CONTENT_PADDING * 2 + contentHeight + progressExtraHeight
        val panelX = when (config.anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.BOTTOM_LEFT -> MARGIN + config.offsetX
            HudAnchor.TOP_RIGHT, HudAnchor.BOTTOM_RIGHT -> guiWidth - panelWidth - MARGIN - config.offsetX
        }
        val panelY = when (config.anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.TOP_RIGHT -> MARGIN + config.offsetY
            HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_RIGHT -> guiHeight - panelHeight - MARGIN - config.offsetY
        }
        val progressY = when {
            !hasProgress -> null
            config.progressBarPosition == HudProgressBarPosition.TOP -> panelY + CONTENT_PADDING
            else -> panelY + CONTENT_PADDING + contentHeight + GAP
        }
        val contentY = when {
            !hasProgress -> panelY + CONTENT_PADDING
            config.progressBarPosition == HudProgressBarPosition.TOP -> panelY + CONTENT_PADDING + PROGRESS_HEIGHT + GAP
            else -> panelY + CONTENT_PADDING
        }
        val contentX = panelX + CONTENT_PADDING
        val coverX = when {
            coverSize <= 0 -> contentX
            config.coverSide == HudCoverSide.LEFT -> contentX
            else -> contentX + contentWidth - coverSize
        }
        val textX = when {
            textWidth <= 0 -> contentX
            coverSize <= 0 -> contentX
            config.coverSide == HudCoverSide.LEFT -> coverX + coverSize + GAP
            else -> contentX
        }
        val coverY = contentY + ((contentHeight - coverSize) / 2).coerceAtLeast(0)

        return Layout(
            panelX = panelX,
            panelY = panelY,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            contentX = contentX,
            contentY = contentY,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            coverX = coverX,
            coverY = coverY,
            coverSize = coverSize,
            textX = textX,
            textWidth = textWidth,
            textScale = textMetrics.scale,
            scaledFontHeight = textMetrics.scaledFontHeight,
            textLineGap = textMetrics.textLineGap,
            textBlockHeight = textMetrics.textBlockHeight,
            metadataLines = metadataLines,
            primaryLyric = primaryLyric,
            secondaryLyric = secondaryLyric,
            progressX = contentX,
            progressY = progressY,
            progressWidth = contentWidth,
            progressHeight = PROGRESS_HEIGHT,
        )
    }

    fun computeProgress(ctx: TrackContext, positionMs: Long): Float {
        val durationMs = ctx.track.durationMs.takeIf { it > 0 } ?: return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun buildTimeString(positionMs: Long, durationMs: Long): String =
        "${formatElapsed(positionMs)} / ${if (durationMs > 0) formatElapsed(durationMs) else "∞"}"

    fun parseArgb(value: String): Int = value.trim().removePrefix("#").toULong(16).toInt()

    private fun formatElapsed(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val seconds = safeMs / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun computeTextMetrics(fontLineHeight: Int, contentHeight: Int, lineCount: Int): TextMetrics {
        if (lineCount <= 0) {
            return TextMetrics(
                scale = 1f,
                scaledFontHeight = 0,
                textLineGap = 0,
                textBlockHeight = 0,
            )
        }

        val scaledFontHeight = ((contentHeight - (lineCount - 1)) / lineCount).coerceAtLeast(1)
        val textLineGap = if (lineCount <= 1) {
            0
        } else {
            ((contentHeight - scaledFontHeight * lineCount) / (lineCount - 1)).coerceAtLeast(0)
        }
        val textBlockHeight = scaledFontHeight * lineCount + textLineGap * (lineCount - 1)
        return TextMetrics(
            scale = scaledFontHeight.toFloat() / fontLineHeight.toFloat(),
            scaledFontHeight = scaledFontHeight,
            textLineGap = textLineGap,
            textBlockHeight = textBlockHeight,
        )
    }

    private const val MARGIN: Int = 6
    private const val CONTENT_PADDING: Int = 4
    private const val GAP: Int = 4
    private const val PROGRESS_HEIGHT: Int = 3
}
