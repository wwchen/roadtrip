package ca.floo.roadtrip.support

/**
 * A sign-in attempt could not be completed: discovery or token exchange failed,
 * or an ID token did not verify.
 *
 * Messages are for operators, not end users. The route layer must translate
 * these into a generic failure rather than surfacing the text — a verification
 * message that distinguishes "bad signature" from "wrong audience" tells an
 * attacker which knob to turn next.
 */
class AuthException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
