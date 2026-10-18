package io.anonero.services

import android.content.SharedPreferences
import io.anonero.AnonConfig
import io.matthewnelson.kmp.tor.runtime.Action.Companion.restartDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.IPSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


private const val TAG = "TorService"

class TorService(prefs: SharedPreferences) {

    val scope = CoroutineScope(SupervisorJob())

    private val _socksSharedFlow =
        MutableSharedFlow<IPSocketAddress>(replay = 1)

    private val _torLogs =
        MutableSharedFlow<String>(
            replay = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            extraBufferCapacity = 50
        )
    @Volatile
    private var _socks: IPSocketAddress? = null
    private val startRequested = AtomicBoolean(false)
    private val restartInFlight = AtomicBoolean(false)
    private val startupStartedAtMillis = AtomicLong(0L)
    private val lastRestartAtMillis = AtomicLong(0L)
    val socks get() = _socks
    val socksFlow get() = _socksSharedFlow
    val torLogs: Flow<String> get() = _torLogs.asSharedFlow()

    val runtime = TorRuntime.Builder(environment = AnonConfig.getTorConfig(scope)) {

        TorEvent.entries().forEach { event ->
            if (event == TorEvent.ERR)
                observerStatic(event, OnEvent.Executor.Immediate) { data ->
                    _torLogs.tryEmit(data)
                }
        }
        RuntimeEvent.entries().forEach { event ->
            observerStatic(event, OnEvent.Executor.Immediate) { data ->
                _torLogs.tryEmit(data.toString())
            }
        }
        observerStatic(RuntimeEvent.LISTENERS) {
            val socketAddr = it.socks.firstOrNull()
            if (socketAddr != null) {
                _socks = socketAddr
                scope.launch {
                    _socksSharedFlow.emit(socketAddr) // Ensure the value is emitted
                }
            }
        }
        config { _ ->
            TorOption.SocksPort.configure { auto() }
        }
        required(TorEvent.ERR)
        required(TorEvent.WARN)
        required(TorEvent.INFO)
    }

    /**
     * Tor can be requested from application startup, node setup, and wallet startup.
     * Launching the same runtime repeatedly while SOCKS is coming up can race listener
     * initialization. Callers must wait for the actual SOCKS listener with a deadline.
     */
    fun start() {
        if (_socks != null || !startRequested.compareAndSet(false, true)) return
        startupStartedAtMillis.set(System.currentTimeMillis())
        scope.launch {
            try {
                runtime.startDaemonAsync()
            } catch (e: CancellationException) {
                startRequested.set(false)
                startupStartedAtMillis.set(0L)
                throw e
            } catch (e: Exception) {
                startRequested.set(false)
                startupStartedAtMillis.set(0L)
                Timber.tag(TAG).e(e, "Tor startup failed")
            }
        }
    }

    /**
     * Returns only after the live SOCKS listener is available.
     * On timeout, stop the stuck attempt and clear the replayed socket so a later
     * connection attempt can start Tor afresh instead of waiting forever.
     */
    /**
     * Wait for SOCKS a bounded number of times. Wallet startup must not retain an
     * indefinite loading state if Tor cannot bootstrap; callers can return control
     * to the user and allow an explicit retry instead of looping forever.
     */
    suspend fun awaitSocksWithRetry(
        maxAttempts: Int,
        timeoutMillis: Long = 30_000L,
        retryDelayMillis: Long = 2_000L
    ): IPSocketAddress? {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        repeat(maxAttempts) { attempt ->
            val readySocket = awaitSocks(timeoutMillis)
            if (readySocket != null) return readySocket
            if (attempt < maxAttempts - 1) delay(retryDelayMillis)
        }
        return null
    }

    suspend fun awaitSocks(timeoutMillis: Long = 30_000L): IPSocketAddress? {
        socks?.let { return it }
        start()

        val readySocket = withTimeoutOrNull(timeoutMillis) {
            socks?.let { it } ?: _socksSharedFlow.first()
        }
        if (readySocket != null) return readySocket

        Timber.tag(TAG).w(
            "Tor SOCKS listener is not ready after %d ms; keeping Tor alive so bootstrap can continue",
            timeoutMillis
        )
        restartIfStalled()
        return null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearSocksState() {
        _socks = null
        _socksSharedFlow.resetReplayCache()
    }

    private fun restartIfStalled() {
        val now = System.currentTimeMillis()
        val startedAt = startupStartedAtMillis.get()
        val lastRestart = lastRestartAtMillis.get()
        if (startedAt <= 0L || now - startedAt < 120_000L) return
        if (lastRestart > 0L && now - lastRestart < 120_000L) return
        restartDaemon()
    }

    private fun restartDaemon() {
        if (!restartInFlight.compareAndSet(false, true)) return
        clearSocksState()
        startRequested.set(true)
        scope.launch {
            try {
                Timber.tag(TAG).w("Restarting Tor runtime after prolonged SOCKS startup delay")
                runtime.restartDaemonAsync()
                val restartedAt = System.currentTimeMillis()
                startupStartedAtMillis.set(restartedAt)
                lastRestartAtMillis.set(restartedAt)
                startRequested.set(true)
            } catch (e: CancellationException) {
                startRequested.set(false)
                startupStartedAtMillis.set(0L)
                throw e
            } catch (e: Exception) {
                startRequested.set(false)
                startupStartedAtMillis.set(0L)
                Timber.tag(TAG).e(e, "Tor runtime restart failed")
            } finally {
                restartInFlight.set(false)
            }
        }
    }

    fun stop() {
        clearSocksState()
        startRequested.set(false)
        startupStartedAtMillis.set(0L)
        lastRestartAtMillis.set(0L)
        scope.launch {
            try {
                runtime.stopDaemonAsync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Tor shutdown failed")
            }
        }
    }

    fun restart() {
        restartDaemon()
    }

    fun dispose() {
        scope.launch {
            runtime.stopDaemonAsync()
        }.invokeOnCompletion {
            it?.let {
                Timber.tag(TAG).i("Tor dispose exception: ${it.message}")
            }
            scope.cancel()
        }
    }
}
