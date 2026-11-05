package io.anonero.ui.home.settings

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.services.AnonWalletHandler
import io.anonero.services.WalletReplacement
import io.anonero.services.WalletState
import io.anonero.ui.MainActivity
import io.anonero.ui.onboard.OnboardLoadingComposable
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.onboard.RestorePayload
import io.anonero.ui.onboard.RestoreWallet
import io.anonero.ui.onboard.graph.OnboardLoading
import io.anonero.util.AppPinStore
import io.anonero.util.PREFS_PINLESS_WALLET
import io.anonero.util.KeyStoreHelper
import io.anonero.util.WALLET_PREFERENCES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.io.File

private const val TAG = "ImportWalletPage"

@Composable
fun ImportWalletPage(
    onBackPress: () -> Unit = {},
    onImportCompleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val onboardViewModel = koinViewModel<OnboardViewModel>()
    val walletHandler = koinInject<AnonWalletHandler>()
    val walletState = koinInject<WalletState>()
    val walletPrefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val initialPassphrase = ""
    var importing by remember { mutableStateOf(false) }

    fun removePartialWallet(walletFile: File) {
        runCatching { walletFile.delete() }
        runCatching { File("${walletFile.path}.keys").delete() }
        val editor = walletPrefs.edit()
        walletPrefs.all.keys
            .filter { it.startsWith("${walletFile.name}::") }
            .forEach(editor::remove)
        editor.commit()
        KeyStoreHelper.removeWalletUserPass(appContext, walletFile.name)
    }

    fun submitImport(payload: RestorePayload) {
        if (importing) return
        importing = true

        if (!walletHandler.tryBeginWalletTransition()) {
            importing = false
            Toast.makeText(
                context,
                "钱包正在执行其他操作，请稍后重试导入",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        onboardViewModel.setRestorePayload(payload)
        onboardViewModel.setPassPhrase(payload.passPhrase)
        walletState.preparePendingWalletHome()
        activity?.stopNotificationService()

        val previousWalletName = AnonConfig.getActiveWalletName(appContext)
        scope.launch {
            var previousReplacement: WalletReplacement? = null
            var createdWalletFile: File? = null
            var previousWalletDetached = false
            var transitionReleased = false

            try {
                val importedWallet = withContext(Dispatchers.IO) {
                    val managedWalletPin = WalletManager.instance
                        ?.getManagedWalletPassword().orEmpty()
                    val globalPin = AppPinStore.get(appContext)
                        ?: managedWalletPin.takeIf {
                            it.isNotEmpty() && !walletPrefs.getBoolean(
                                AnonConfig.walletPreferenceKey(appContext, PREFS_PINLESS_WALLET),
                                false
                            )
                        }.orEmpty()

                    AppPinStore.rememberIfMissing(appContext, globalPin)

                    previousReplacement =
                        walletHandler.prepareWalletReplacement(managedWalletPin)
                    walletHandler.detachWalletForReplacement(previousReplacement)
                    previousWalletDetached = true

                    createdWalletFile = if (AnonConfig.getWalletFiles(appContext).isEmpty()) {
                        AnonConfig.setActiveWallet(appContext, "anon")
                        AnonConfig.getDefaultWalletFile(appContext)
                    } else {
                        AnonConfig.createNewWalletFile(appContext)
                    }

                    onboardViewModel.restoreFromSeed(globalPin, payload.passPhrase, rememberPassphraseGlobally = false)
                    val newWallet = WalletManager.instance?.wallet
                        ?: error("导入完成后没有可用的钱包句柄")
                    check(newWallet.name == createdWalletFile?.name) {
                        "导入的钱包与新建的钱包文件不匹配"
                    }
                    walletState.prepareImmediateWalletHome(newWallet)
                    runCatching {
                        walletState.publishWalletSnapshot(newWallet)
                    }.onFailure {
                        Timber.tag(TAG).w(it, "Unable to publish imported wallet snapshot")
                    }
                    runCatching {
                        walletState.ensureNextReceiveAddress()
                    }.onFailure {
                        Timber.tag(TAG).w(it, "Unable to prepare imported wallet receive address")
                    }
                    newWallet
                }

                check(importedWallet.name == createdWalletFile?.name) {
                    "导入的钱包文件状态异常"
                }

                walletHandler.endWalletTransition()
                transitionReleased = true

                withContext(Dispatchers.Main) {
                    importing = false
                    onImportCompleted()
                }

                walletHandler.scope.launch(Dispatchers.IO) {
                    runCatching {
                        walletHandler.finishPendingWalletReplacement()
                    }.onFailure {
                        Timber.tag(TAG).w(it, "Unable to close previous wallet after import")
                    }

                    val configuredNode = appContext.getSharedPreferences(
                        AnonConfig.PREFS,
                        android.content.Context.MODE_PRIVATE
                    ).getString(NodeFields.RPC_HOST.value, "")
                    if (!configuredNode.isNullOrBlank()) {
                        runCatching { walletHandler.startService() }
                            .onFailure {
                                Timber.tag(TAG).w(it, "Unable to start service for imported wallet")
                            }
                    }

                    withContext(Dispatchers.Main) {
                        runCatching { activity?.startNotificationService() }
                            .onFailure {
                                Timber.tag(TAG).w(it, "Unable to start notifications for imported wallet")
                            }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Unable to import wallet from seed")

                withContext(Dispatchers.IO) {
                    if (previousWalletDetached) {
                        if (previousReplacement != null) {
                            runCatching {
                                walletHandler.restoreWalletAfterFailedCreation(previousReplacement!!)
                            }.onFailure {
                                Timber.tag(TAG).e(it, "Unable to restore previous wallet after import failure")
                            }
                        } else {
                            WalletManager.instance?.wallet?.let { failedWallet ->
                                runCatching { WalletManager.instance?.close(failedWallet) }
                                    .onFailure {
                                        Timber.tag(TAG).w(it, "Unable to close incomplete imported wallet")
                                    }
                            }
                        }

                        createdWalletFile?.let(::removePartialWallet)
                        AnonConfig.setActiveWallet(appContext, previousWalletName)
                    }

                    WalletManager.instance?.wallet?.let { restoredWallet ->
                        walletState.prepareImmediateWalletHome(restoredWallet)
                        runCatching {
                            walletState.publishWalletSnapshot(restoredWallet)
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Unable to republish previous wallet after import failure")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    importing = false
                    Toast.makeText(
                        context,
                        error.message ?: "导入钱包失败",
                        Toast.LENGTH_LONG
                    ).show()
                }

                walletHandler.endWalletTransition()
                transitionReleased = true
                walletHandler.scope.launch(Dispatchers.IO) {
                    val configuredNode = appContext.getSharedPreferences(
                        AnonConfig.PREFS,
                        android.content.Context.MODE_PRIVATE
                    ).getString(NodeFields.RPC_HOST.value, "")
                    if (!configuredNode.isNullOrBlank()) {
                        runCatching { walletHandler.startService() }
                            .onFailure {
                                Timber.tag(TAG).w(it, "Unable to restart previous wallet service")
                            }
                    }
                    withContext(Dispatchers.Main) {
                        runCatching { activity?.startNotificationService() }
                            .onFailure {
                                Timber.tag(TAG).w(it, "Unable to restart previous wallet notifications")
                            }
                    }
                }
            } finally {
                if (!transitionReleased) {
                    walletHandler.endWalletTransition()
                }
            }
        }
    }

    if (importing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            OnboardLoadingComposable(
                loadingState = OnboardLoading(message = "正在导入钱包...")
            )
        }
    } else {
        RestoreWallet(
            onBackPressed = onBackPress,
            oNextPressed = ::submitImport,
            onboardViewModel = onboardViewModel,
            initialPassphrase = initialPassphrase,
            requirePassphrase = false
        )
    }
}
