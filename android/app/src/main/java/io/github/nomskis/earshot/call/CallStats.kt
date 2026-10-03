package io.github.nomskis.earshot.call

/**
 * Reads WebRTC's stats report (W3C webrtc-stats). Kept free of WebRTC types
 * so it can be unit tested: each entry is id -> (type, members).
 */
object CallStats {
    data class Entry(val type: String, val members: Map<String, Any?>)

    /** "wifi", "cellular", ... for the local side of the candidate pair in use. */
    fun selectedNetworkType(report: Map<String, Entry>): String? {
        val pair = selectedPair(report) ?: return null
        val localId = pair.members["localCandidateId"] as? String ?: return null
        return report[localId]?.members?.get("networkType") as? String
    }

    private fun selectedPair(report: Map<String, Entry>): Entry? {
        // Preferred: the transport names its selected pair.
        report.values.firstOrNull { it.type == "transport" }
            ?.members?.get("selectedCandidatePairId")?.let { id -> report[id as? String]?.let { return it } }
        // Fallback: the nominated, succeeded pair.
        return report.values.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
        }
    }
}
