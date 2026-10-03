package io.github.nomskis.earshot.call

/**
 * Android's rule for media, applied to the call: when headphones go away,
 * playback pauses instead of carrying on out loud (that's what
 * ACTION_AUDIO_BECOMING_NOISY is for). Earbuds dying at the gym shouldn't put
 * the other person's voice on the loudspeaker. Their voice is held until the
 * earbuds are back, or until you choose the speaker.
 *
 * A call that starts on the speaker is never held.
 */
class OutputHold(startedPersonal: Boolean) {
    private var wasPersonal = startedPersonal
    private var held = false

    /** Feed every output change (worn or not); returns the new held state when it changes. */
    fun onOutput(personal: Boolean): Boolean? {
        if (personal) {
            wasPersonal = true
            if (!held) return null
            held = false
            return false
        }
        if (!wasPersonal) return null
        wasPersonal = false
        if (held) return null
        held = true
        return true
    }
}
