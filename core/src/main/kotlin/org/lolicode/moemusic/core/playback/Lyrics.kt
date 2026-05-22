package org.lolicode.moemusic.core.playback

data class LyricLine(
    val startMs: Long,
    val text: String,
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
) {
    fun lineAt(positionMs: Long): LyricLine? {
        if (lines.isEmpty()) return null
        val effectiveMs = positionMs - offsetMs
        var current: LyricLine? = null
        for (line in lines) {
            if (line.startMs > effectiveMs) break
            current = line
        }
        return current
    }

    fun nextLineAfter(positionMs: Long): LyricLine? {
        if (lines.isEmpty()) return null
        val effectiveMs = positionMs - offsetMs
        return lines.firstOrNull { it.startMs > effectiveMs }
    }
}

private val timestampRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*")
private val metadataRegex = Regex("^\\[([A-Za-z]+):(.*)]$")

fun parseLyrics(raw: String?): ParsedLyrics? {
    val source = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = mutableListOf<LyricLine>()
    var offsetMs = 0L

    for (rawLine in source.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val metadataMatch = metadataRegex.matchEntire(line)
        if (metadataMatch != null && !line.startsWith("[0")) {
            val key = metadataMatch.groupValues[1].lowercase()
            val value = metadataMatch.groupValues[2].trim()
            if (key == "offset") {
                offsetMs = value.toLongOrNull() ?: offsetMs
            }
            continue
        }

        val matches = timestampRegex.findAll(line).toList()
        if (matches.isEmpty()) continue

        val lyricText = line.substring(matches.last().range.last + 1).trim()
        if (lyricText.isEmpty()) continue

        for (match in matches) {
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toLongOrNull() ?: continue
            val fractionRaw = match.groupValues[3]
            val fractionMs = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLong() * 100L
                2 -> fractionRaw.toLong() * 10L
                else -> fractionRaw.take(3).toLong()
            }
            val startMs = minutes * 60_000L + seconds * 1_000L + fractionMs
            lines += LyricLine(startMs = startMs, text = lyricText)
        }
    }

    if (lines.isEmpty()) return null
    return ParsedLyrics(lines.sortedBy { it.startMs }, offsetMs)
}
