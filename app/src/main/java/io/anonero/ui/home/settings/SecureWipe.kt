package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.anonero.AnonConfig
import io.anonero.services.AnonWalletHandler
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.ui.MainActivity
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.ShakeConfig
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber
import androidx.core.content.edit
import io.anonero.R


private const val TAG = "SecureWipe"

class SecureWipeViewModel(
    private val anonWalletHandler: AnonWalletHandler,
    private val sharedPreferences: SharedPreferences,
    private val nodesRepository: NodesRepository,
    private val logRepository: LogRepository
) : ViewModel() {

    private val _wipeProgressMessage = MutableLiveData<String?>()
    val wipeProgressMessage: LiveData<String?> = _wipeProgressMessage

    private val _wipeProgress = MutableLiveData(0.1f)
    val wipeProgress: LiveData<Float> = _wipeProgress
    private val wipeTaskStarted = AtomicBoolean(false)

    /**
     * Resume a deletion request after process death. At this point the old process and
     * native wallet handle are gone, so recovery must finish through the persisted
     * cleanup workflow and must never ask the user for the password again.
     */
    fun resumeInterruptedWipe(activity: Activity): Job? {
        if (anonWalletHandler.isSecureWipeInProgress() ||
            !wipeTaskStarted.compareAndSet(false, true)
        ) return null

        anonWalletHandler.markSecureWipeRequestStartedInProcess()
        anonWalletHandler.markSecureWipeCleanupStarted()
        return anonWalletHandler.scope.launch(Dispatchers.IO) {
            val recoveryPrefs = activity.getSharedPreferences(
                AnonConfig.APP_PREFERENCES, android.content.Context.MODE_PRIVATE
            )
            try {
                while (true) {
                    try {
                        check(recoveryPrefs.edit()
                            .putBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, true)
                            .putBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, true)
                            .commit()) { "Unable to persist secure-delete recovery state" }

                        (activity as? MainActivity)?.stopNotificationService()
                        _wipeProgress.postValue(.15f)
                        _wipeProgressMessage.postValue("正在恢复安全删除钱包")

                        // The previous process has ended, releasing its native wallet handle.
                        val walletDir = File(activity.filesDir, "wallets")
                        check(!walletDir.exists() || walletDir.deleteRecursively()) {
                            "Wallet files could not be deleted"
                        }
                        _wipeProgress.postValue(.45f)
                        _wipeProgressMessage.postValue("正在清除钱包设置")

                        check(sharedPreferences.edit().clear().commit()) {
                            "Unable to clear wallet PIN and passphrase preferences"
                        }
                        _wipeProgress.postValue(.6f)
                        _wipeProgressMessage.postValue("正在清除节点配置")
                        nodesRepository.clearAll()
                        _wipeProgress.postValue(.75f)
                        _wipeProgressMessage.postValue("正在清除日志")
                        logRepository.clear()
                        AnonConfig.disposeState()

                        check(recoveryPrefs.edit()
                            .remove(AnonConfig.PREF_WALLET_WIPE_PENDING)
                            .remove(AnonConfig.PREF_WALLET_WIPE_REQUESTED)
                            .commit()) { "Unable to finalize secure-delete state" }

                        anonWalletHandler.releaseSecureWipePriority()
                        _wipeProgress.postValue(1f)
                        _wipeProgressMessage.postValue("钱包已成功安全删除")
                        activity.applicationContext.sendBroadcast(
                            Intent(MainActivity.ACTION_WALLET_CLEARED)
                                .setPackage(activity.packageName)
                        )
                        return@launch
                    } catch (e: Exception) {
                        // Log for diagnostics only. Never display deletion exceptions.
                        // Reassert both flags if a failed commit partly removed them.
                        Timber.tag(TAG).e(e, "Secure-delete recovery failed; retrying")
                        runCatching {
                            recoveryPrefs.edit()
                                .putBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, true)
                                .putBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, true)
                                .commit()
                        }
                        _wipeProgress.postValue(.2f)
                        _wipeProgressMessage.postValue("正在继续安全删除钱包")
                        delay(1_000)
                    }
                }
            } finally {
                anonWalletHandler.markSecureWipeCleanupFinished()
            }
        }
    }

    fun wipe(passPhrase: String, activity: Activity): Job {
        wipeTaskStarted.set(true)
        anonWalletHandler.markSecureWipeRequestStartedInProcess()
        anonWalletHandler.markSecureWipeCleanupStarted()
        return anonWalletHandler.scope.launch(Dispatchers.IO) {
            val recoveryPrefs = activity.getSharedPreferences(
                AnonConfig.APP_PREFERENCES, android.content.Context.MODE_PRIVATE
            )
            var nativeWalletDeletionCompleted = false
            try {
                // A validated request is non-cancellable. Retry until all steps complete;
                // only progress messages are published to the UI.
                while (true) {
                    try {
                        check(recoveryPrefs.edit()
                            .putBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, true)
                            .putBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, true)
                            .commit()) { "Unable to persist secure-delete state" }

                        (activity as? MainActivity)?.stopNotificationService()

                        if (!nativeWalletDeletionCompleted) {
                            _wipeProgress.postValue(.3f)
                            _wipeProgressMessage.postValue("正在安全删除钱包")
                            // If close succeeded but file removal failed, the handler can
                            // resume cleanup when no native wallet handle remains.
                            anonWalletHandler.wipe(passPhrase)
                            nativeWalletDeletionCompleted = true
                            _wipeProgress.postValue(.5f)
                            _wipeProgressMessage.postValue("钱包数据已清除")
                        }

                        _wipeProgress.postValue(.6f)
                        _wipeProgressMessage.postValue("正在清除钱包设置")
                        check(sharedPreferences.edit().clear().commit()) {
                            "Unable to clear wallet PIN and passphrase preferences"
                        }
                        _wipeProgress.postValue(.7f)
                        _wipeProgressMessage.postValue("正在清除节点配置")
                        nodesRepository.clearAll()
                        _wipeProgress.postValue(.78f)
                        _wipeProgressMessage.postValue("正在清除日志")
                        logRepository.clear()
                        AnonConfig.disposeState()

                        check(recoveryPrefs.edit()
                            .remove(AnonConfig.PREF_WALLET_WIPE_PENDING)
                            .remove(AnonConfig.PREF_WALLET_WIPE_REQUESTED)
                            .commit()) { "Unable to finalize secure-delete state" }

                        anonWalletHandler.releaseSecureWipePriority()
                        _wipeProgress.postValue(1f)
                        _wipeProgressMessage.postValue("钱包已成功安全删除")
                        return@launch
                    } catch (e: Exception) {
                        // Diagnostics stay in logs. Keep the screen on progress while
                        // transient failures are retried automatically.
                        Timber.tag(TAG).e(e, "Secure deletion failed; automatically retrying")
                        runCatching {
                            recoveryPrefs.edit()
                                .putBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, true)
                                .putBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, true)
                                .commit()
                        }
                        _wipeProgress.postValue(if (nativeWalletDeletionCompleted) .65f else .3f)
                        _wipeProgressMessage.postValue(
                            if (nativeWalletDeletionCompleted) {
                                "正在继续清除钱包设置"
                            } else {
                                "正在继续安全删除钱包"
                            }
                        )
                        delay(1_000)
                    }
                }
            } finally {
                anonWalletHandler.markSecureWipeCleanupFinished()
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureWipe(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    requestClearScreen: (bool: Boolean) -> Unit = {},
    goToHome: () -> Unit = {},
) {

    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val anonWalletHandler = koinInject<AnonWalletHandler>()
    val secureWipeViewModel = koinViewModel<SecureWipeViewModel>()
    val focusRequester = remember { FocusRequester() }
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val progressMessage by secureWipeViewModel.wipeProgressMessage.observeAsState(null)
    val wipeProgress by secureWipeViewModel.wipeProgress.observeAsState(.1f)
    val view = LocalView.current
    val activity = LocalActivity.current
    val appPreferences = remember(activity) {
        activity?.getSharedPreferences(
            AnonConfig.APP_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
    }
    val wipePending = appPreferences?.getBoolean(
        AnonConfig.PREF_WALLET_WIPE_PENDING, false
    ) ?: false
    val wipeRequestPersisted = appPreferences?.getBoolean(
        AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
    ) ?: false
    var passPhraseDialog by remember {
        mutableStateOf(
            !anonWalletHandler.isSecureWipeInProgress() &&
                !wipePending && !wipeRequestPersisted
        )
    }
    var passPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var wipeSubmitting by remember { mutableStateOf(false) }

    BackHandler(
        enabled = wipeSubmitting || wipeRequestPersisted || wipePending ||
            anonWalletHandler.isSecureWipeInProgress()
    ) {
        // Once a correct deletion password started the request, it cannot be cancelled.
    }


    val animatedProgress by animateFloatAsState(
        targetValue = wipeProgress,
        animationSpec = tween(
            durationMillis = 300,//animation duration
            delayMillis = 50,//delay before animation start
            easing = LinearOutSlowInEasing
        ), label = "animatedProgress"
    )

    val backgroundBlur: Float by animateFloatAsState(
        if (passPhraseDialog) 6f else 0f,
        label = "blur-radius"
    )

    LaunchedEffect(passPhraseDialog) {
        if (passPhraseDialog) {
            delay(100)
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(progressMessage) {
        if (progressMessage != null)
            requestClearScreen.invoke(false)
    }

    LaunchedEffect(wipeRequestPersisted, wipePending, wipeSubmitting) {
        if ((wipeRequestPersisted || wipePending) &&
            !wipeSubmitting &&
            !anonWalletHandler.isSecureWipeInProgress() &&
            activity != null
        ) {
            secureWipeViewModel.resumeInterruptedWipe(activity)
        }
    }

    fun clearWallet() {
        if (wipeSubmitting) return
        wipeSubmitting = true
        error = null
        scope.launch(Dispatchers.IO) {
            val hash = prefs.getString(PREFS_PASSPHRASE_HASH, "")
            val hashedPass = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            if (hash != hashedPass) {
                withContext(Dispatchers.Main) {
                    wipeSubmitting = false
                    requestClearScreen(false)
                    errorShake.shake(ShakeConfig(6, translateX = 5f))
                    repeat(6) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        delay(50)
                    }
                    error = null
                }
                return@launch
            }

            val seed = getWalletSeed(passPhrase)?.split(" ")
            if (seed == null) {
                withContext(Dispatchers.Main) {
                    wipeSubmitting = false
                    error = "无法读取当前钱包助记词，安全删除尚未启动"
                }
                return@launch
            }

            val currentActivity = activity
            if (currentActivity == null) {
                withContext(Dispatchers.Main) {
                    wipeSubmitting = false
                    error = "页面状态已失效，请重新打开安全删除页面"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                // Password and wallet seed are now validated. Hide the prompt before
                // the priority gate is raised so no cancel path races deletion startup.
                passPhraseDialog = false
                requestClearScreen(false)
            }

            try {
                // Correct password is verified. Only now does deletion become the
                // highest-priority, non-cancellable wallet operation.
                val recoveryPrefs = currentActivity.getSharedPreferences(
                    AnonConfig.APP_PREFERENCES,
                    android.content.Context.MODE_PRIVATE
                )
                check(recoveryPrefs.edit()
                    .putBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, true)
                    .commit()) { "无法保存安全删除状态" }

                anonWalletHandler.prepareForSecureWipeRequest()
                (currentActivity as? MainActivity)?.stopNotificationService()

                secureWipeViewModel.wipe(passPhrase, currentActivity)
                    .invokeOnCompletion { cause ->
                        if (cause == null) {
                            if (!currentActivity.isFinishing && !currentActivity.isDestroyed) {
                                currentActivity.runOnUiThread {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                }
                            }
                            currentActivity.applicationContext.sendBroadcast(
                                Intent(MainActivity.ACTION_WALLET_CLEARED)
                                    .setPackage(currentActivity.packageName)
                            )
                        } else {
                            Timber.tag(TAG).e(cause, "Secure deletion did not finish")
                            currentActivity.runOnUiThread {
                                wipeSubmitting = false
                                error = cause.message ?: "安全删除未完成，删除优先级仍然保留"
                                passPhrase = ""
                                // Deletion has already started: remain on the progress screen.
                                passPhraseDialog = false
                                requestClearScreen(false)
                            }
                        }
                    }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unable to begin secure deletion")
                withContext(Dispatchers.Main) {
                    wipeSubmitting = false
                    passPhrase = ""
                    val appPrefsNow = currentActivity.getSharedPreferences(
                        AnonConfig.APP_PREFERENCES, android.content.Context.MODE_PRIVATE
                    )
                    val deletionAlreadyStarted =
                        appPrefsNow.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false) ||
                            appPrefsNow.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
                    passPhraseDialog = !deletionAlreadyStarted
                    error = e.message ?: "无法启动安全删除"
                }
            }
        }
    }

    if (passPhraseDialog)
        AlertDialog(
            modifier = Modifier
                .shake(errorShake)
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnClickOutside = false, dismissOnBackPress = false
            ),
            title = {
                Column {
                    Text(
                        text = stringResource(id = R.string.securely_wipe_title),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 18.sp
                        )
                    )
                    Spacer(Modifier.padding(12.dp))
                    Text(
                        text = stringResource(id = R.string.securely_wipe_warning),
                        style = MaterialTheme.typography.bodyMedium.copy(
                        )
                    )
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        text = stringResource(id = R.string.securely_wipe_passphrase_prompt),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Light
                    )
                }
            },
            text = {
                OutlinedTextField(
                    value = passPhrase,
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {

                        }
                    ),
                    onValueChange = {
                        passPhrase = it
                    },

                    )
            },
            onDismissRequest = {
                // Secure deletion is a persisted request and cannot be dismissed here.
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onError
                    ),
                    colors = ButtonDefaults.buttonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        containerColor = MaterialTheme.colorScheme.errorContainer,

                        ),
                    enabled = !wipeSubmitting,
                    onClick = {
                        clearWallet()
                    }) { Text(stringResource(id = R.string.confirm)) }
            },
            // Before a correct password is accepted, this is only a confirmation screen.
            // Once the request marker is persisted, cancellation is no longer offered.
            dismissButton = {
                if (!wipeSubmitting && !wipeRequestPersisted && !wipePending &&
                    !anonWalletHandler.isSecureWipeInProgress()
                ) {
                    Button(
                        onClick = {
                            passPhraseDialog = false
                            onBackPress.invoke()
                        },
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(
                            1.dp,
                            color = MaterialTheme.colorScheme.onSecondary
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    ) {
                        Text(
                            stringResource(id = R.string.cancel),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                            )
                        )
                    }
                }
            }
        )


    Scaffold(
        Modifier.blur(
            backgroundBlur.dp
        ),
    ) {
        Column(
            Modifier
                .padding(it)
                .padding(0.dp)
                .fillMaxSize(),
            Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if ((progressMessage != null || wipePending || wipeRequestPersisted || wipeSubmitting) && error == null)
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(320.dp)
                    )
                    Text(
                        progressMessage ?: if (wipePending || wipeRequestPersisted) {
                            "正在恢复安全删除钱包"
                        } else {
                            "正在验证删除密码"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(
                            Alignment.Center
                        )
                    )
                }
            if (error != null) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.labelSmall
                            .copy(
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            ),
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.align(
                            Alignment.Center
                        )
                    )
                }
            }
        }
    }
}

@Preview(device = "id:pixel_5")
@Composable
private fun SecureWipePrev() {
    AnonNeroTheme {
        SecureWipe()
    }
}