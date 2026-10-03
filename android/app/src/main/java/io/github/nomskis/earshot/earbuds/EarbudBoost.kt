package io.github.nomskis.earshot.earbuds

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Earbud game mode for the length of a call: on when the call starts, back
 * to how it was when the call ends. Never turns off a mode you had on.
 */
class EarbudBoost(private val control: EarbudControl) {
    data class Status(val earbuds: String, val text: String, val active: Boolean)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** What to switch back off after the call; null when we changed nothing. */
    private var restore: EarbudControl.Target? = null

    suspend fun begin() {
        if (!control.hasPermission()) {
            _status.value = Status("Earbuds", "Allow Nearby devices to let Earshot switch earbud game mode.", false)
            return
        }
        val target = control.currentTarget() ?: return
        val driver = target.driver ?: run {
            _status.value = Status(target.name, "No game-mode switch known for these earbuds.", false)
            return
        }
        _status.value = Status(target.name, "Switching on game mode…", false)
        val result = withTimeoutOrNull(SWITCH_TIMEOUT_MS) { driver.setLowLatency(target.device, true) }
            ?: DriverResult.NoAnswer
        Log.i(TAG, "${driver.family} game mode on: $result")
        _status.value = when (result) {
            is DriverResult.Ok -> {
                if (result.wasOn != true) restore = target
                Status(target.name, if (result.wasOn == true) "Earbud game mode already on" else "Earbud game mode on", true)
            }
            else -> Status(target.name, result.describe(control.context, driver.family), false)
        }
    }

    suspend fun end() {
        val target = restore
        restore = null
        _status.value = null
        val driver = target?.driver ?: return
        val result = withTimeoutOrNull(SWITCH_TIMEOUT_MS) { driver.setLowLatency(target.device, false) }
        Log.i(TAG, "${driver.family} game mode restored: $result")
    }

    private companion object {
        const val TAG = "EarbudBoost"
        const val SWITCH_TIMEOUT_MS = 15_000L
    }
}
