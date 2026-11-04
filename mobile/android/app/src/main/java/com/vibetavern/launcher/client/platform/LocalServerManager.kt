package com.vibetavern.launcher.client.platform

import android.content.Context
import com.vibetavern.launcher.ServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LocalServerState {
    data object Stopped : LocalServerState
    data class Preparing(val copied: Int, val total: Int) : LocalServerState
    data class Starting(val seconds: Int) : LocalServerState

    /** [ownedByApp] false means another Vibe Tavern server (e.g. the old Termux one) already answers on the port. */
    data class Running(val ownedByApp: Boolean) : LocalServerState
    data class Failed(val message: String) : LocalServerState
}

/**
 * Starts and stops the server that runs on this phone, reusing the launcher's
 * [ServerService] (foreground service + native binary) unchanged.
 */
class LocalServerManager(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<LocalServerState>(LocalServerState.Stopped)
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchJob: Job? = null

    /** Looks at what is actually running; call when the app comes to the foreground. */
    suspend fun refresh() {
        if (job?.isActive == true) return
        val ready = withContext(Dispatchers.IO) { ServerService.apiReady() }
        _state.value = when {
            ready -> LocalServerState.Running(ownedByApp = ServerService.hasOwnedServerProcess())
            else -> LocalServerState.Stopped
        }
        if (ready) watchWhileRunning()
    }

    /** Suspends until the server is [LocalServerState.Running] or has [LocalServerState.Failed]. */
    suspend fun start() {
        val current = _state.value
        if (current is LocalServerState.Running) return
        if (job?.isActive == true) {
            job?.join()
            return
        }
        val starter = scope.launch { runStart() }
        job = starter
        starter.join()
    }

    private suspend fun runStart() {
        try {
            if (withContext(Dispatchers.IO) { ServerService.apiReady() }) {
                _state.value = LocalServerState.Running(ownedByApp = ServerService.hasOwnedServerProcess())
                watchWhileRunning()
                return
            }
            _state.value = LocalServerState.Preparing(0, 0)
            withContext(Dispatchers.IO) {
                PayloadInstaller.ensureInstalled(context) { copied, total ->
                    if (copied == 1 || copied == total || copied % 25 == 0) _state.value = LocalServerState.Preparing(copied, total)
                }
            }
            _state.value = LocalServerState.Starting(0)
            ServerService.start(context)
            for (second in 0..READY_TIMEOUT_SECONDS) {
                if (withContext(Dispatchers.IO) { ServerService.apiReady() }) {
                    _state.value = LocalServerState.Running(ownedByApp = true)
                    watchWhileRunning()
                    return
                }
                // The service sets its process a moment after it starts, so only judge "died" after a grace period.
                if (second > 3 && !ServerService.hasOwnedServerProcess()) {
                    _state.value = LocalServerState.Failed("The server process exited. See the server log in the launcher screen.")
                    return
                }
                _state.value = LocalServerState.Starting(second)
                delay(1_000)
            }
            _state.value = LocalServerState.Failed("The server did not become ready in time.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = LocalServerState.Failed(e.message ?: "Could not start the server.")
        }
    }

    fun stop() {
        job?.cancel()
        ServerService.stop(context)
        _state.value = LocalServerState.Stopped
    }

    /** While running, notice when the server goes away (killed by the OS, stopped from the notification). */
    private fun watchWhileRunning() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive && _state.value is LocalServerState.Running) {
                delay(3_000)
                val ready = withContext(Dispatchers.IO) { ServerService.apiReady() }
                if (!ready && _state.value is LocalServerState.Running) {
                    _state.value = LocalServerState.Stopped
                }
            }
        }
    }

    private companion object {
        const val READY_TIMEOUT_SECONDS = 120
    }
}
