package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.call.CallQuality
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One call in the history, like a phone's call log. */
@Serializable
data class CallRecord(
    /** Who it was with, as shown at the time. */
    val name: String,
    /** Their inbox address when known, so they can be called back from the history. */
    val address: String? = null,
    val direction: Direction,
    val outcome: Outcome,
    val video: Boolean,
    /** When the call started ringing or was joined. */
    val atMillis: Long,
    /** How long you talked; 0 when you didn't. */
    val durationSeconds: Long = 0,
    /** How the connection held up, for calls that connected. */
    val quality: CallQuality? = null,
) {
    enum class Direction { INCOMING, OUTGOING }

    enum class Outcome {
        /** You talked. */
        ANSWERED,
        /** It rang here and nobody answered (or you were on another call). */
        MISSED,
        /** Declined: by you for incoming calls, by them for outgoing ones. */
        DECLINED,
        /** They were on another call. */
        BUSY,
        NO_ANSWER,
        /** Their phone couldn't be reached. */
        UNREACHABLE,
        /** You hung up before they answered. */
        CANCELLED,
        /** Answered, but the call never connected. */
        FAILED,
    }

    /** The phone app's wording: what happened, for the history row. */
    val summary: String
        get() = when (outcome) {
            Outcome.ANSWERED -> durationText(durationSeconds)
            Outcome.MISSED -> "Missed"
            Outcome.DECLINED -> "Declined"
            Outcome.BUSY -> "Busy"
            Outcome.NO_ANSWER -> "No answer"
            Outcome.UNREACHABLE -> "Couldn't reach"
            Outcome.CANCELLED -> "Cancelled"
            Outcome.FAILED -> "Didn't connect"
        }

    /** Shown in red, like a phone's missed calls. */
    val missed: Boolean get() = direction == Direction.INCOMING && outcome == Outcome.MISSED

    companion object {
        /** "45 sec", "12 min", "1 hr 5 min". */
        fun durationText(seconds: Long): String = when {
            seconds < 60 -> "$seconds sec"
            seconds < 3_600 -> "${seconds / 60} min"
            else -> "${seconds / 3_600} hr" + (seconds % 3_600 / 60).let { if (it > 0) " $it min" else "" }
        }
    }
}

object CallLog {
    /** About a month of calls between two people; the oldest go first. */
    const val MAX = 100

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CallRecord.serializer())

    fun decode(text: String?): List<CallRecord> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(records: List<CallRecord>): String = json.encodeToString(serializer, records)

    /** Newest first. */
    fun add(records: List<CallRecord>, record: CallRecord): List<CallRecord> =
        (listOf(record) + records).sortedByDescending { it.atMillis }.take(MAX)

    /** The latest call with [address], for a contact's row. */
    fun latestWith(records: List<CallRecord>, address: String): CallRecord? = records.firstOrNull { it.address == address }

    /** Forget someone's calls along with them. */
    fun removeWith(records: List<CallRecord>, address: String): List<CallRecord> = records.filter { it.address != address }
}

/** What goes into the history when a call ends. */
object CallRecords {
    /**
     * [connectedForMs] is how long the call was connected (null if it never was), [ringStatus]
     * how ringing [calling] ended. A call by room code that nobody joined isn't recorded.
     */
    fun ended(
        calling: Contact?,
        answering: IncomingRing?,
        room: String,
        peerName: String?,
        learnedAddress: String?,
        ringStatus: OutgoingRing.Status?,
        connectedForMs: Long?,
        video: Boolean,
        startedAtMillis: Long,
        quality: CallQuality? = null,
    ): CallRecord? {
        val outcome = when {
            connectedForMs != null -> CallRecord.Outcome.ANSWERED
            calling != null -> when (ringStatus) {
                OutgoingRing.Status.DECLINED -> CallRecord.Outcome.DECLINED
                OutgoingRing.Status.BUSY -> CallRecord.Outcome.BUSY
                OutgoingRing.Status.NO_ANSWER -> CallRecord.Outcome.NO_ANSWER
                OutgoingRing.Status.UNREACHABLE -> CallRecord.Outcome.UNREACHABLE
                OutgoingRing.Status.ANSWERED -> CallRecord.Outcome.FAILED
                OutgoingRing.Status.CALLING, OutgoingRing.Status.RINGING, null -> CallRecord.Outcome.CANCELLED
            }
            answering != null -> CallRecord.Outcome.FAILED
            else -> return null
        }
        return CallRecord(
            name = peerName?.takeIf { it.isNotBlank() } ?: calling?.name ?: answering?.callerName ?: "Room $room",
            address = calling?.address ?: answering?.callerAddress ?: learnedAddress,
            direction = if (answering != null) CallRecord.Direction.INCOMING else CallRecord.Direction.OUTGOING,
            outcome = outcome,
            video = video,
            atMillis = startedAtMillis,
            durationSeconds = (connectedForMs ?: 0) / 1000,
            quality = quality,
        )
    }

    /** A call that rang here and wasn't taken. */
    fun notTaken(ring: IncomingRing, outcome: CallRecord.Outcome, atMillis: Long): CallRecord =
        CallRecord(ring.callerName, ring.callerAddress, CallRecord.Direction.INCOMING, outcome, ring.video, atMillis)
}
