package io.github.nomskis.earshot.calls

/** A call ringing on this phone right now. */
data class IncomingRing(
    val ringId: String,
    val room: String,
    /** Their saved name if they're a contact, else the name they gave. */
    val callerName: String,
    /** Their inbox address, when they proved it; lets you call back. */
    val callerAddress: String?,
    val video: Boolean,
)
