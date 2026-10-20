package io.anonero.services

import android.content.SharedPreferences
import android.util.Log
import io.anonero.AnonConfig
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.DaemonInfo
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class InvalidPin : Exception("invalid pin")

private const val TAG = "AnonWalletHandler"

class UnableToCloseWallet : Exception()

class AnonWalletHandler(
    private val prefs: SharedPreferences,
    private val walletState: WalletState,
    private val torService: TorService
) {

    private val _scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    private var handler: MoneroHandlerThread? = null
    private val wipeInProgress = AtomicBoolean(false)
    // Covers post-native cleanup too, so Activity recreation cannot race the final
    // settings/node/log cleanup after AnonWalletHandler.wipe() has returned.
    private val secureWipeCleanupTasks = AtomicInteger(0)
    // Sticky for this process after a validated delete request. It distinguishes a
    // genuine process restart from an Activity/ViewModel recreation after a failure.
    private val secureWipeRequestStartedInProcess = AtomicBoolean(false)
    private val serviceStartMutex = Mutex()
    private val walletInitializationMutex = Mutex()
    private val manualNodeConnectionRequests = AtomicInteger(0)
    @Volatile private var serviceStartedWallet: Wallet? = null

    val scope get() = _scope

    /** True while deletion is active or a validated request is unresolved in this process. */
    fun isSecureWipeInProgress(): Boolean =
        wipeInProgress.get() || secureWipeCleanupTasks.get() > 0 ||
            secureWipeRequestStartedInProcess.get()

    fun markSecureWipeRequestStartedInProcess() {
        secureWipeRequestStartedInProcess.set(true)
    }

    fun markSecureWipeCleanupStarted() {
        secureWipeCleanupTasks.incrementAndGet()
    }

    fun markSecureWipeCleanupFinished() {
        secureWipeCleanupTasks.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    private fun hasPersistentSecureWipeRequest(): Boolean {
        val context = AnonConfig.context ?: return false
        val appPreferences = context.getSharedPreferences(
            AnonConfig.APP_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
        return appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false) ||
            appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
    }

    private fun secureWipePriorityActive(): Boolean =
        wipeInProgress.get() || walletState.isWalletWipeInProgress ||
            hasPersistentSecureWipeRequest()

    /**
     * Raise the sticky deletion gate after the deletion password is verified and
     * pause the foreground refresh. Never restart refresh after a failed deletion attempt.
     */
    suspend fun prepareForSecureWipeRequest() {
        secureWipeRequestStartedInProcess.set(true)
        walletState.reserveWalletWipeRequest()
        runCatching {
            walletState.awaitPendingNativeQueries()
            walletInitializationMutex.withLock {
                val wallet = WalletManager.instance?.wallet
                if (wallet?.isInitialized == true && !walletState.backgroundSync) {
                    wallet.pauseRefreshAndWait()
                }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "Unable to pause wallet refresh while secure deletion has priority")
        }
    }

    /** Finalize the sticky state gate after every secure-delete cleanup phase succeeded. */
    fun releaseSecureWipePriority() {
        walletState.releaseWalletWipeRequest()
        secureWipeRequestStartedInProcess.set(false)
    }

    init {
        scope.launch {
            torService.socksFlow.collect { socket ->
                val candidateWallet = WalletManager.instance?.wallet
                if (
                    !secureWipePriorityActive() &&
                    !walletState.isResyncPriorityActive &&
                    prefs.getBoolean(WALLET_USE_TOR, false) &&
                    candidateWallet?.isInitialized == true
                ) {
                    // Tor updates must share the same mutex as node init and reset;
                    // otherwise a late SOCKS event could call setProxy during rescan.
                    walletInitializationMutex.withLock {
                        val currentWallet = WalletManager.instance?.wallet
                        if (
                            !secureWipePriorityActive() &&
                            !walletState.isResyncPriorityActive &&
                            prefs.getBoolean(WALLET_USE_TOR, false) &&
                            currentWallet === candidateWallet &&
                            currentWallet?.isInitialized == true
                        ) {
                            val proxyAddress = "${socket.address}:${socket.port.value}"
                            WalletManager.instance?.setProxy(proxyAddress)
                            currentWallet.setProxy(proxyAddress)
                            Timber.tag(TAG).d("Applied Tor SOCKS proxy %s", proxyAddress)
                        }
                    }
                }
            }
        }
    }

    suspend fun openWallet(pin: String): Boolean {
        val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
        val anonWallet = WalletManager.instance?.openWallet(
            walletFile.path,
            pin,
        ) ?: throw InvalidPin()
        val opened = anonWallet.status.isOk
        if (!opened) {
            val error = anonWallet.status.errorString
            Timber.tag(TAG).e("openWallet error: %s", error)
            // WalletManager manages the native handle even when the password was wrong.
            // Close it before returning so another correct PIN attempt can retry cleanly.
            runCatching { anonWallet.close() }
                .onFailure { closeError ->
                    Timber.tag(TAG).w(closeError, "Unable to close wallet after failed PIN attempt")
                }
            return false
        }

        // Publish local wallet data and create a child receive address before starting
        // node initialization/refresh. This avoids racing the first native address
        // creation against wallet.init() or the scan thread, and does not wait for sync.
        walletState.publishWalletSnapshot(anonWallet)
        try {
            val address = walletState.ensureNextReceiveAddress()
            Timber.tag(TAG).i(
                "Prepared initial receive subaddress index=%d",
                address.addressIndex
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unlock should still succeed if local address creation temporarily fails.
            // ReceiveScreen keeps retrying until a usable child address is available.
            Timber.tag(TAG).w(e, "Initial receive subaddress not ready; screen will retry")
        }
        return true
    }

    suspend fun startService() {
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        // Do not queue another automatic startup behind a user-initiated connection.
        // Home and onboarding may both call startService; queued retries used to run
        // immediately after each other and compete for the same native wallet handle.
        if (isManualNodeConnectionInProgress()) {
            Timber.tag(TAG).i("Skipping automatic service start while manual node connection is pending")
            return
        }
        serviceStartMutex.lock()
        try {
            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
            if (isManualNodeConnectionInProgress()) {
                Timber.tag(TAG).i("Skipping queued automatic service start; manual node connection has priority")
                return
            }
            startServiceLocked()
        } finally {
            serviceStartMutex.unlock()
        }
    }

    private suspend fun startServiceLocked() {
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        val wallet = WalletManager.instance?.wallet ?: return
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        walletState.prepareScanPreferenceIdentity(wallet)
        if (
            serviceStartedWallet === wallet &&
            wallet.isInitialized &&
            wallet.fullStatus.connectionStatus ==
                Wallet.ConnectionStatus.ConnectionStatus_Connected
        ) {
            // Even the connected-wallet fast path must share the init mutex with reset.
            // Otherwise this startRefresh() could race a reset that has already published
            // progress and is about to pause the normal refresh worker.
            val reused = walletInitializationMutex.withLock {
                if (secureWipePriorityActive() || walletState.isResyncPriorityActive) {
                    false
                } else if (
                    serviceStartedWallet === wallet &&
                    wallet.isInitialized &&
                    wallet.fullStatus.connectionStatus ==
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                ) {
                    wallet.startRefresh()
                    if (!secureWipePriorityActive() && !walletState.isResyncPriorityActive) {
                        walletState.update()
                        // Re-entering an already initialized wallet bypasses initializeWalletLocked()
                        // and beginWalletScan(). Kick the live daemon-height check explicitly so
                        // blocks received while the app was closed show progress without requiring
                        // the user to press the ordinary Refresh action.
                        val checkingSyncProgress = walletState.checkSyncProgressOnStartup(wallet)
                        if (!checkingSyncProgress) walletState.setLoading(false)
                    }
                    true
                } else {
                    false
                }
            }
            if (reused) return
            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        }

        handler = MoneroHandlerThread(wallet, walletState, this)
        wallet.setListener(handler)
        handler?.start()

        // Keep the listener installed even when a manual request wins the race:
        // the manual connect path starts scanning but relies on this listener to
        // publish block progress and connection changes.
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        if (isManualNodeConnectionInProgress()) {
            Timber.tag(TAG).i("Skipping automatic wallet startup while manual node connection is pending")
            return
        }
        walletState.setLoading(true)

        // Publish the configured endpoint before waiting for Tor. This lets the node
        // list show the selected node immediately, while its status correctly remains
        // Disconnected until the native wallet reports a real connection. This is UI
        // state only: do not set the native daemon or start refresh before SOCKS is ready.
        val configuredHost = prefs.getString(NodeFields.RPC_HOST.value, "").orEmpty().trim()
        val configuredPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
        if (configuredHost.isNotBlank() && configuredPort in 1..65535) {
            walletState.updateDaemon(
                DaemonInfo(
                    daemon = "$configuredHost:$configuredPort",
                    connectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    daemonHeight = 0L
                )
            )
        }

        try {
            // Allow Tor time to bootstrap, including a restart if SOCKS is stalled,
            // but never leave wallet startup showing "loading" indefinitely.
            val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

            if (useTor) {
                val readySocket = torService.awaitSocksWithRetry(maxAttempts = 6)
                    ?: throw IllegalStateException(
                        "Tor 尚未在约 3 分钟内就绪，请在节点设置中再次点击连接"
                    )
                if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
                val proxyAddress = "${readySocket.address}:${readySocket.port.value}"
                WalletManager.instance?.setProxy(proxyAddress)
                wallet.setProxy(proxyAddress)
            } else if (!proxyHost.isNullOrBlank() && proxyPort != -1) {
                if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
                val proxyAddress = "${proxyHost}:${proxyPort}"
                WalletManager.instance?.setProxy(proxyAddress)
                wallet.setProxy(proxyAddress)
            } else {
                // Tor is disabled and no custom proxy is configured: use a direct node
                // connection rather than trying a stale or unavailable SOCKS endpoint.
                setProxy(null, null)
            }

            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
            // Tor may have become ready while the user was opening Node Management.
            // Re-check before locking/initializing so automatic startup cannot steal
            // the native init slot from the manual connection request.
            if (isManualNodeConnectionInProgress()) {
                Timber.tag(TAG).i("Deferring automatic wallet init to the pending manual node connection")
                return
            }
            walletInitializationMutex.withLock {
                if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return@withLock
                if (isManualNodeConnectionInProgress()) {
                    Timber.tag(TAG).i("Skipping automatic wallet init because manual node connection took priority")
                    return@withLock
                }
                if (
                    serviceStartedWallet === wallet &&
                    wallet.isInitialized &&
                    wallet.fullStatus.connectionStatus ==
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                ) {
                    if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return@withLock
                    // The service may have started this wallet previously but its daemon
                    // can be disconnected now. Only reuse it when the connection is live;
                    // otherwise we must run the same daemon init path as manual Connect.
                    wallet.startRefresh()
                    walletState.update()
                    // The daemon may have reconnected while Tor/bootstrap was in progress.
                    // This fast path bypasses initializeWalletLocked(), so explicitly check
                    // for blocks missed while the app was away before clearing the loader.
                    val checkingSyncProgress = walletState.checkSyncProgressOnStartup(wallet)
                    if (!checkingSyncProgress) walletState.setLoading(false)
                } else {
                    initializeWalletLocked(wallet)
                }
            }
        } catch (e: CancellationException) {
            if (secureWipePriorityActive()) return
            throw e
        } catch (e: Exception) {
            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
            Timber.tag(TAG).e(e, "Wallet service startup failed")
            walletState.clearSyncProgress()
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
        }
    }

    /**
     * Shared with the node settings flow so manual connects cannot race startup's
     * native wallet initialization while Tor finishes bootstrapping.
     */
    suspend fun <T> withWalletInitializationLock(block: () -> T): T =
        walletInitializationMutex.withLock { block() }

    /** Mark a user-triggered connection while it prepares Tor and waits for native init. */
    private fun canRunNodeChange(allowActiveResyncScan: Boolean): Boolean =
        !secureWipePriorityActive() &&
            (!walletState.isResyncPriorityActive ||
                (allowActiveResyncScan && walletState.canRetargetNodeDuringResync))

    fun beginManualNodeConnection(allowActiveResyncScan: Boolean = false): Boolean {
        if (!canRunNodeChange(allowActiveResyncScan)) return false
        manualNodeConnectionRequests.incrementAndGet()
        // Close the race with a reset/wipe arriving just after the first check.
        if (!canRunNodeChange(allowActiveResyncScan)) {
            endManualNodeConnection()
            return false
        }
        return true
    }

    fun endManualNodeConnection() {
        manualNodeConnectionRequests.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun isManualNodeConnectionInProgress(): Boolean = manualNodeConnectionRequests.get() > 0

    fun markWalletServiceStarted(wallet: Wallet) {
        if (wallet.isInitialized) serviceStartedWallet = wallet
    }

    private fun initializeWalletLocked(wallet: Wallet) {
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        val host = prefs.getString(NodeFields.RPC_HOST.value, "")
        val rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
        val rpcUsername = prefs.getString(NodeFields.RPC_USERNAME.value, "")
        val rpcPassphrase = prefs.getString(NodeFields.RPC_PASSWORD.value, "")

        if (host?.isNotEmpty() == true) {
            if (secureWipePriorityActive()) return
            val nodeObj = JSONObject()
                .apply {
                    put(NodeFields.RPC_HOST.value, host)
                    put(NodeFields.RPC_PORT.value, rpcPort)
                    put(
                        NodeFields.RPC_SSL.value,
                        prefs.getBoolean(
                            NodeFields.RPC_SSL.value,
                            Node.defaultUseSsl(host, rpcPort)
                        )
                    )
                    put(NodeFields.RPC_USERNAME.value, rpcUsername)
                    put(NodeFields.RPC_PASSWORD.value, rpcPassphrase)
                    put(NodeFields.RPC_NETWORK.value, AnonConfig.getNetworkType().toString())
                    put(NodeFields.NODE_NAME.value, "anon")
                }
            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
            val node = Node.fromJson(nodeObj)
            // initializeWalletLocked() immediately calls wallet.init() below. Do not
            // start a refresh in updateDaemon() before that init; the duplicate native
            // refresh can contend with first connection and slow the startup handshake.
            updateDaemon(node, restartRefresh = false)
            if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
            walletState.setLoading(true)
        }

        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        // Native init may replace a fresh seed restore's selected scan height with the
        // daemon tip. Capture it before init and reapply it before starting the scan.
        val forceFreshScan = prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
        val selectedRestoreHeightBeforeInit =
            if (forceFreshScan) walletState.pendingSelectedRestoreHeight(wallet) else null

        val initialized = wallet.init(0)
        if (secureWipePriorityActive() || walletState.isResyncPriorityActive) return
        if (initialized && wallet.isInitialized) {
            if (forceFreshScan && selectedRestoreHeightBeforeInit != null) {
                val reappliedHeight = walletState.preservePendingRestoreHeightAfterInit(wallet)
                check(reappliedHeight == selectedRestoreHeightBeforeInit) {
                    "Selected restore height changed during native init"
                }
                Timber.tag(TAG).i(
                    "Preserved selected restore height after wallet init: %d",
                    reappliedHeight
                )
            }

            val actualRestoreHeight = wallet.getRestoreHeight().coerceAtLeast(0L)
            prefs.edit(commit = true) { putLong(RESTORE_HEIGHT, actualRestoreHeight) }
            wallet.setTrustedDaemon(true)
            val resumeScan = walletState.beginWalletScan(
                wallet = wallet,
                scanStartHeight = actualRestoreHeight,
                forceStartAtSelectedHeight = forceFreshScan
            )
            walletState.publishWalletSnapshot(wallet)
            if (forceFreshScan) {
                Timber.tag(TAG).i(
                    "Starting fresh wallet scan from selected restore height: %d",
                    actualRestoreHeight
                )
                wallet.startRefresh()
            } else {
                if (resumeScan) wallet.rescanBlockchainAsync()
                wallet.startRefresh()
            }
            try {
                wallet.refreshHistory()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Initial history refresh failed; continuing wallet startup")
            }
            try {
                walletState.update()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Initial wallet state refresh failed; continuing wallet startup")
            }
            serviceStartedWallet = wallet
        } else {
            Timber.tag(TAG).e("Wallet initialization failed: %s", wallet.status.errorString)
            walletState.clearSyncProgress()
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
        }
    }

    fun updateDaemon(
        node: Node?,
        restartRefresh: Boolean = true,
        allowActiveResyncScan: Boolean = false,
        refreshAlreadyPaused: Boolean = false
    ) {
        if (!canRunNodeChange(allowActiveResyncScan)) return
        walletState.setLoading(true)
        val wallet = WalletManager.instance?.wallet
        val walletManager = WalletManager.instance
        try {
            if (!refreshAlreadyPaused) {
                if (wallet?.isInitialized == true) wallet.pauseRefreshAndWait()
                else wallet?.pauseRefresh()
            }
            if (!canRunNodeChange(allowActiveResyncScan)) {
                if (!secureWipePriorityActive()) walletState.setLoading(false)
                return
            }
            if (node != null) {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = "${node.host}:${node.rpcPort}",
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            } else {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = null,
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            }
            walletManager?.setDaemon(node)
            wallet?.setTrustedDaemon(true)

            if (restartRefresh && node != null && wallet?.isInitialized == true &&
                canRunNodeChange(allowActiveResyncScan)
            ) {
                wallet.startRefresh()
            }
            if (canRunNodeChange(allowActiveResyncScan)) walletState.setLoading(false)
        } catch (e: Exception) {
            if (!canRunNodeChange(allowActiveResyncScan)) {
                if (!secureWipePriorityActive()) walletState.setLoading(false)
                return
            }
            walletState.updateDaemon(
                DaemonInfo(
                    daemon = null,
                    Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    daemonHeight = 0L
                )
            )
            e.printStackTrace()
        }
    }

    fun setProxy(
        proxy: String?,
        port: Int?,
        allowActiveResyncScan: Boolean = false
    ) {
        if (!canRunNodeChange(allowActiveResyncScan)) return
        Timber.tag(TAG).d("setProxy %s%s", proxy, port.toString())
        //disable proxy
        if (proxy == null && port == null) {
            prefs.edit {
                remove(WALLET_PROXY)
                remove(WALLET_PROXY_PORT)
            }
            WalletManager.instance?.setProxy("")
            WalletManager.instance?.wallet?.setProxy("")
        } else {
            prefs.edit {
                putString(WALLET_PROXY, proxy)
                putInt(WALLET_PROXY_PORT, port ?: -1)
            }
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val proxyStr = if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
                "${proxyHost}:$proxyPort"
            } else {
                return
            }
            WalletManager.instance?.setProxy(proxyStr)
            WalletManager.instance?.wallet?.setProxy(proxyStr)
        }
    }

    fun getProxy(): Pair<String, Int>? {
        val proxyHost = prefs.getString(WALLET_PROXY, "")
        val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

        if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
            return Pair(proxyHost, proxyPort)
        }
        return null
    }

    suspend fun wipe(passPhrase: String): Boolean {
        check(wipeInProgress.compareAndSet(false, true)) {
            "Wallet wipe is already in progress"
        }

        val walletManager = WalletManager.instance
        val wallet = walletManager?.wallet
            ?: run {
                // A previous retry may already have closed/unmanaged the native wallet
                // but failed to remove its files. With no native handle left, retry the
                // on-disk cleanup directly instead of looping forever on "wallet not open".
                try {
                    val context = AnonConfig.context
                        ?: throw IllegalStateException("Application context unavailable")
                    val walletDir = java.io.File(context.filesDir, "wallets")
                    val removed = !walletDir.exists() || walletDir.deleteRecursively()
                    check(removed && !java.io.File(walletDir, "anon").exists()) {
                        "Wallet files could not be deleted"
                    }
                    walletManager?.setDaemon(null)
                    walletState.resetAfterWalletWipe()
                    return true
                } finally {
                    wipeInProgress.set(false)
                }
            }
        if (!walletState.beginWalletWipe()) {
            wipeInProgress.set(false)
            throw IllegalStateException("Wallet wipe is already in progress")
        }
        var refreshPaused = false
        var listenerDetached = false
        var walletClosed = false

        try {
            // The wipe gate is already raised. Drain reset/height-query operations and
            // node initialization/connect work before invoking native wallet methods.
            walletState.awaitPendingNativeQueries()
            walletInitializationMutex.withLock { Unit }

            // Stop background sync only if the app actually entered that mode.
            // Calling the native stop routine unconditionally can stall normal wipes.
            if (walletState.backgroundSync) {
                check(wallet.stopBackgroundSync(passPhrase)) {
                    "Unable to stop background sync; wallet was not deleted"
                }
                walletState.setBackGroundSync(false)
            }

            // App-side JNI calls are gated; only now stop native refresh. This prevents
            // an in-flight scan task from restarting refresh after pause.
            Timber.tag(TAG).i("Secure wipe: pausing wallet refresh")
            wallet.pauseRefresh()
            refreshPaused = true

            // No native listener may call into WalletState while closeWallet() releases
            // the native wallet and its JNI listener reference.
            Timber.tag(TAG).i("Secure wipe: detaching wallet listener")
            wallet.setListener(null)
            listenerDetached = true

            Timber.tag(TAG).i("Secure wipe: closing native wallet")
            check(wallet.close()) { throw UnableToCloseWallet() }
            walletClosed = true

            WalletManager.instance?.setDaemon(null)

            val context = AnonConfig.context
                ?: throw IllegalStateException("Application context unavailable")
            val walletDir = java.io.File(context.filesDir, "wallets")
            val removed = !walletDir.exists() || walletDir.deleteRecursively()
            check(removed && !java.io.File(walletDir, "anon").exists()) {
                "Native wallet closed, but wallet files could not be deleted"
            }
            walletState.resetAfterWalletWipe()

            Timber.tag(TAG).i("Secure wipe: wallet files deleted")
            return true
        } catch (e: Exception) {
            if (!walletClosed) {
                // Closing was rejected: reconnect the old listener and resume the
                // existing wallet rather than leaving it open but inert.
                if (listenerDetached) {
                    runCatching { wallet.setListener(handler) }
                        .onFailure { Timber.tag(TAG).e(it, "Unable to restore wallet listener after failed wipe") }
                }
                if (refreshPaused && !hasPersistentSecureWipeRequest()) {
                    runCatching { wallet.startRefresh() }
                        .onFailure { Timber.tag(TAG).e(it, "Unable to resume wallet refresh after failed wipe") }
                }
            }
            throw e
        } finally {
            if (!walletClosed) walletState.endWalletWipe()
            wipeInProgress.set(false)
        }
    }

}
