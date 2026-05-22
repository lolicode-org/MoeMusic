package org.lolicode.moemusic.api

/**
 * Fatal registration error for duplicate plugin or music-source ids.
 *
 * MoeMusic treats registration ids as global keys. When two registrations claim the same id,
 * startup cannot continue safely because later lookups would become ambiguous.
 */
public class DuplicateRegistrationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
