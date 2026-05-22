package org.lolicode.moemusic.api

/**
 * Base exception for user-visible exceptional failures.
 *
 * Throw this only when the normal return path is no longer appropriate. Core catches these at
 * user boundaries and renders [userMessage] for the initiating player. Unknown exception types are
 * treated as internal failures instead.
 *
 * Boundary contract:
 * - core -> plugin calls are non-retrying; if a plugin throws, core treats that attempt as final
 * - plugin -> core API calls are also non-retrying; plugins should treat thrown core exceptions as
 *   unrecoverable for the current operation
 * - plugin-owned retries belong around the plugin's own upstream I/O, before one of these
 *   exceptions is finally thrown
 */
public open class UserFacingException(
    public val userMessage: LocalizedText,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: userMessage.debugString(), cause)

public open class AlreadyQueuedException(
    cause: Throwable? = null,
) : UserFacingException(LocalizedText.key("error.moemusic.already_queued"), cause = cause)

public open class PermissionDeniedException(
    userMessage: LocalizedText = LocalizedText.key("error.moemusic.permission_denied"),
    cause: Throwable? = null,
) : UserFacingException(userMessage, cause = cause)

/**
 * Shared local request-budget rejection.
 *
 * This is used for MoeMusic's own per-player rate limiter. It is distinct from
 * [SourceRateLimitException], which means the upstream music source rejected the request.
 */
public open class RateLimitedException(
    cause: Throwable? = null,
) : UserFacingException(LocalizedText.key("error.moemusic.rate_limit"), cause = cause)

public open class TrackUnavailableException(
    userMessage: LocalizedText,
    cause: Throwable? = null,
) : UserFacingException(userMessage, cause = cause)

/**
 * Thrown by the submission gate when a content-filter rule rejects a track.
 *
 * Unlike a plain [TrackUnavailableException], the [fullReason] may contain a confidential rule
 * detail (e.g. a matched regex pattern) and must not be shown verbatim to all users. Catch
 * sites should render [fullReason] only for users with `moemusic.moderation.filter_manage`,
 * and [maskedReason] for everyone else.
 */
public class FilterBlockException(
    public val fullReason: LocalizedText,
    public val maskedReason: LocalizedText = LocalizedText.key("error.moemusic.content_filter.managed"),
    cause: Throwable? = null,
) : TrackUnavailableException(fullReason, cause)

public open class SourceException(
    userMessage: LocalizedText,
    cause: Throwable? = null,
) : UserFacingException(userMessage, cause = cause)

public class SourceNetworkException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.source.network"), cause)

public class SourceTimeoutException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.source.timeout"), cause)

public class SourceRateLimitException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.source.rate_limit"), cause)

public class SourceAuthException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.source.auth"), cause)

public class SourceFormatException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.source.bad_format"), cause)

public class SourceInternalException(
    cause: Throwable? = null,
) : SourceException(LocalizedText.key("error.moemusic.internal"), cause)
