package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.call.Ids
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage

/**
 * Calling a contact from a call you started: rings their phone while you
 * wait in the room, and keeps track of what's happening so the call screen
 * can say it (calling, ringing, declined...).
 */
class OutgoingRing(
    val contact: Contact,
    private val myName: String,
    /** Our own inbox key: proves to them who's calling. */
    private val myInboxKey: String,
    private val video: Boolean,
    private val newRingId: () -> String = { Ids.random(9, "r") },
) {
    enum class Status {
        /** Ringing hasn't been confirmed yet. */
        CALLING,
        RINGING,
        /** They accepted and are joining. */
        ANSWERED,
        /** No device of theirs is listening right now. */
        UNREACHABLE,
        DECLINED,
        BUSY,
        NO_ANSWER,
    }

    var status: Status = Status.CALLING
        private set

    private var ringId: String? = null
    /** Stopped trying: the minute is up, or the server can't ring at all. */
    private var expired = false

    /** The call is over without them: declined, busy or not answered. */
    val gaveUp: Boolean get() = status == Status.DECLINED || status == Status.BUSY || status == Status.NO_ANSWER

    /** Their phone can't be reached yet, and it's still worth ringing again. */
    val keepsTrying: Boolean get() = status == Status.UNREACHABLE && !expired

    /** They've been in the room: from now on it's an ordinary call. */
    var joined = false
        private set

    /** Why the call ended without them, for the home screen; null unless [gaveUp]. */
    val outcome: String?
        get() = when (status) {
            Status.DECLINED -> "${contact.name} declined the call."
            Status.BUSY -> "${contact.name} is on another call."
            Status.NO_ANSWER -> "No answer from ${contact.name}."
            else -> null
        }

    /**
     * We're waiting alone in [room]: the ring to send, or null when there's no
     * point (answered, or they already said no). Called again after a
     * reconnect, because the server drops a ring when the caller's
     * connection does.
     */
    fun ring(room: String): ClientMessage.Ring? {
        if (expired || (status != Status.CALLING && status != Status.RINGING && status != Status.UNREACHABLE)) return null
        val id = newRingId()
        ringId = id
        return ClientMessage.Ring(to = contact.address, ringId = id, room = room, name = myName, video = video, inbox = myInboxKey)
    }

    /** Applies a server message; true when the status changed. */
    fun onMessage(message: ServerMessage): Boolean {
        val before = status
        when (message) {
            is ServerMessage.RingStatus -> if (message.ringId == ringId && !gaveUp && status != Status.ANSWERED) {
                status = if (message.status == "unreachable") Status.UNREACHABLE else Status.RINGING
            }
            is ServerMessage.RingAnswered -> if (message.ringId == ringId && status != Status.ANSWERED) {
                status = when {
                    message.accepted -> Status.ANSWERED
                    message.reason == "busy" -> Status.BUSY
                    message.reason == "no-answer" -> Status.NO_ANSWER
                    else -> Status.DECLINED
                }
            }
            else -> Unit
        }
        return status != before
    }

    /**
     * The server answered our ring with an error (a server from before ringing existed):
     * their phone can't be rung, but the invite link still works.
     */
    fun refused(): Boolean {
        if (status != Status.CALLING) return false
        status = Status.UNREACHABLE
        expired = true
        return true
    }

    /** They're in the room: the call is on. */
    fun onJoined() {
        status = Status.ANSWERED
        joined = true
    }

    /**
     * The minute is up. A ring still going becomes "no answer" (and the message cancels it);
     * an unreachable phone stops being retried, leaving the room open for the invite link.
     */
    fun timeOut(): ClientMessage.RingCancel? {
        expired = true
        if (status != Status.CALLING && status != Status.RINGING) return null
        status = Status.NO_ANSWER
        return ringId?.let { ClientMessage.RingCancel(it) }
    }

    /** We hung up first: stop their phone ringing. */
    fun hangUp(): ClientMessage.RingCancel? {
        if (status != Status.CALLING && status != Status.RINGING) return null
        return ringId?.let { ClientMessage.RingCancel(it) }
    }

    companion object {
        /** A little longer than the server's minute, so its "no answer" usually comes first. */
        const val TIMEOUT_MS = 65_000L
    }
}
