package org.lolicode.moemusic.api

/**
 * Result type for expected user-facing outcomes.
 *
 * Use [UserResult.Error] when the operation completed normally from the plugin's point of view but
 * the answer to the player is still "no" or "not available". Typical cases are no search access,
 * invalid user-supplied ids, unsupported link types, source-specific validation failures, and
 * "not found" results that the caller is expected to show directly to the player.
 *
 * Throw [UserFacingException] only for exceptional paths where normal return flow has been aborted.
 * Typical cases are playback-time resolution failures, transport failures that escape a retry
 * policy, or plugin-internal failures where continuing the current operation no longer makes sense.
 *
 * In short:
 * - expected, player-facing branch -> [UserResult.Error]
 * - exceptional abort of the current operation -> throw [UserFacingException] (or subclass)
 */
public sealed interface UserResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : UserResult<T>

    public data class Error(
        val message: LocalizedText,
    ) : UserResult<Nothing>
}

public inline fun <T, R> UserResult<T>.map(transform: (T) -> R): UserResult<R> = when (this) {
    is UserResult.Success -> UserResult.Success(transform(value))
    is UserResult.Error -> this
}

public inline fun <T> UserResult<T>.getOrElse(defaultValue: (LocalizedText) -> T): T = when (this) {
    is UserResult.Success -> value
    is UserResult.Error -> defaultValue(message)
}

public inline fun <T> UserResult<T>.onSuccess(block: (T) -> Unit): UserResult<T> {
    if (this is UserResult.Success) block(value)
    return this
}

public inline fun <T> UserResult<T>.onError(block: (LocalizedText) -> Unit): UserResult<T> {
    if (this is UserResult.Error) block(message)
    return this
}