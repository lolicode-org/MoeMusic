package org.lolicode.moemusic.api

/**
 * Locale-independent user-facing text payload.
 *
 * This is the canonical message type for the MoeMusic plugin API. Plugins should prefer passing
 * [LocalizedText] through results, exceptions, config validators, and authoritative rejection
 * paths rather than
 * eagerly formatting English strings.
 *
 * Use [LocalizedText.Key] for anything that should be translated per player locale. Use
 * [LocalizedText.Plain] only when the text is already final and should be shown exactly as-is.
 * Typical examples are upstream-provided titles, hostnames, numeric ids, or text that was already
 * rendered by another localization system.
 */
public sealed interface LocalizedText {
    public data class Plain(
        val text: String,
    ) : LocalizedText

    public data class Key(
        val key: String,
        val args: List<LocalizedTextArg> = emptyList(),
    ) : LocalizedText

    public companion object {
        public fun plain(text: String): LocalizedText = Plain(text)

        public fun key(key: String, vararg args: Any?): LocalizedText = Key(
            key = key,
            args = args.map(LocalizedTextArg::of),
        )
    }
}

public sealed interface LocalizedTextArg {
    public data class Text(
        val value: LocalizedText,
    ) : LocalizedTextArg

    public data class Value(
        val value: String,
    ) : LocalizedTextArg

    public companion object {
        public fun of(value: Any?): LocalizedTextArg = when (value) {
            is LocalizedText -> Text(value)
            else -> Value(value?.toString() ?: "")
        }
    }
}

public fun String.asLocalizedText(): LocalizedText = LocalizedText.Plain(this)

public fun LocalizedText.debugString(): String = when (this) {
    is LocalizedText.Plain -> text
    is LocalizedText.Key -> buildString {
        append(key)
        if (args.isNotEmpty()) {
            append(args.joinToString(prefix = "(", postfix = ")") { arg ->
                when (arg) {
                    is LocalizedTextArg.Text -> arg.value.debugString()
                    is LocalizedTextArg.Value -> arg.value
                }
            })
        }
    }
}

public fun LocalizedText?.isNullOrBlank(): Boolean = when (this) {
    null -> true
    is LocalizedText.Plain -> text.isBlank()
    is LocalizedText.Key -> false
}
