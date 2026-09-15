package org.lolicode.moemusic.api

/**
 * Fatal registration error for duplicate music-source ids.
 *
 * MoeMusic treats music source registration ids as global keys. When two music sources claim
 * the same id, startup cannot continue safely because later lookups would become ambiguous.
 */
public class DuplicateRegistrationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
