package io.anonero.ui.home

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.services.WalletState
import io.anonero.services.TorService
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import io.anonero.ui.theme.AnonOutlineButton
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import timber.log.Timber
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.json.JSONObject

// Target intervals are used only to choose the initial search window. The height
// returned to the UI is selected from actual block-header timestamps returned by monerod.
private const val MONERO_GENESIS_TIMESTAMP = 1_397_818_193L
private const val MONERO_V2_FORK_HEIGHT = 1_009_827L
private const val MONERO_BLOCK_TIME_V1_SECONDS = 60L
private const val MONERO_BLOCK_TIME_V2_SECONDS = 120L
private const val BLOCK_SEARCH_RADIUS = 250L

private data class ActualBlockHeader(val height: Long, val timestamp: Long)
private data class RpcHttpResponse(
    val code: Int,
    val body: String,
    val authenticateChallenge: String?
)

private class MoneroBlockTimeLookup(
    private val prefs: SharedPreferences,
    private val torService: TorService
) {
    fun headerAtHeight(height: Long): ActualBlockHeader {
        require(height >= 0L) { "区块高度不能小于 0" }
        val result = rpcCall(
            "get_block_header_by_height",
            JSONObject().put("height", height)
        )
        val header = result.optJSONObject("block_header")
            ?: throw IOException("节点没有返回区块头")
        return ActualBlockHeader(
            height = header.getLong("height"),
            timestamp = header.getLong("timestamp")
        )
    }

    fun latestHeight(): Long {
        val count = rpcCall("get_block_count").optLong("count", 0L)
        if (count <= 0L) throw IOException("节点没有返回有效区块高度")
        return count - 1L
    }

    // The estimate is only a search starting point; it is never used as the result.
    fun heightNearTimestamp(targetTimestamp: Long): Long {
        val tipHeight = latestHeight()
        val genesis = headerAtHeight(0L)
        if (targetTimestamp < genesis.timestamp) {
            throw IOException("所选日期早于 Monero 创世区块")
        }
        val tip = headerAtHeight(tipHeight)
        if (targetTimestamp > tip.timestamp) {
            throw IOException("所选日期晚于节点最新区块时间")
        }
        if (targetTimestamp == tip.timestamp) return tip.height

        var center = guessRestoreHeight(targetTimestamp).coerceIn(0L, tipHeight)
        repeat(12) {
            val start = (center - BLOCK_SEARCH_RADIUS).coerceAtLeast(0L)
            val end = (center + BLOCK_SEARCH_RADIUS).coerceAtMost(tipHeight)
            val headers = blockHeadersRange(start, end)
            if (headers.isEmpty()) throw IOException("节点没有返回区块头列表")

            val minTimestamp = headers.minOf { it.timestamp }
            val maxTimestamp = headers.maxOf { it.timestamp }
            if (targetTimestamp < minTimestamp && start > 0L) {
                val secondsPerBlock = if (center < MONERO_V2_FORK_HEIGHT) {
                    MONERO_BLOCK_TIME_V1_SECONDS
                } else {
                    MONERO_BLOCK_TIME_V2_SECONDS
                }
                val distance = ((minTimestamp - targetTimestamp) / secondsPerBlock)
                    .coerceAtLeast(BLOCK_SEARCH_RADIUS)
                center = (center - distance).coerceAtLeast(0L)
                return@repeat
            }
            if (targetTimestamp > maxTimestamp && end < tipHeight) {
                val secondsPerBlock = if (center < MONERO_V2_FORK_HEIGHT) {
                    MONERO_BLOCK_TIME_V1_SECONDS
                } else {
                    MONERO_BLOCK_TIME_V2_SECONDS
                }
                val distance = ((targetTimestamp - maxTimestamp) / secondsPerBlock)
                    .coerceAtLeast(BLOCK_SEARCH_RADIUS)
                center = (center + distance).coerceAtMost(tipHeight)
                return@repeat
            }
            return headers.minByOrNull {
                kotlin.math.abs(it.timestamp - targetTimestamp)
            }!!.height
        }
        throw IOException("无法根据节点实际区块时间定位该日期，请检查节点是否同步")
    }

    private fun blockHeadersRange(start: Long, end: Long): List<ActualBlockHeader> {
        val result = rpcCall(
            "get_block_headers_range",
            JSONObject()
                .put("start_height", start)
                .put("end_height", end)
        )
        val headers = result.optJSONArray("headers")
            ?: throw IOException("节点没有返回区块头列表")
        return buildList {
            for (index in 0 until headers.length()) {
                val header = headers.optJSONObject(index) ?: continue
                if (header.has("height") && header.has("timestamp")) {
                    add(
                        ActualBlockHeader(
                            height = header.getLong("height"),
                            timestamp = header.getLong("timestamp")
                        )
                    )
                }
            }
        }
    }

    private fun rpcCall(method: String, params: JSONObject = JSONObject()): JSONObject {
        val host = prefs.getString(NodeFields.RPC_HOST.value, "").orEmpty().trim()
        val port = prefs.getInt(NodeFields.RPC_PORT.value, 0)
        if (host.isBlank() || port !in 1..65535) {
            throw IOException("没有已配置的 XMR 节点，请先连接节点")
        }
        val urlHost = if (host.contains(':') && !host.startsWith("[")) "[" + host + "]" else host
        val endpoint = URL("http://" + urlHost + ":" + port + "/json_rpc")
        val username = prefs.getString(NodeFields.RPC_USERNAME.value, "").orEmpty()
        val password = prefs.getString(NodeFields.RPC_PASSWORD.value, "").orEmpty()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "0")
            .put("method", method)
            .put("params", params)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val first = post(endpoint, payload, null)
        val response = if (first.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            if (username.isBlank() && password.isBlank()) {
                throw IOException("节点 RPC 需要认证，但未配置用户名和密码")
            }
            val challenge = first.authenticateChallenge
                ?: throw IOException("节点 RPC 认证失败：缺少 Digest challenge")
            post(endpoint, payload, digestAuthorization(challenge, username, password))
        } else {
            first
        }
        if (response.code !in 200..299) {
            throw IOException("节点 RPC 请求失败：HTTP " + response.code)
        }
        val root = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw IOException("节点 RPC 返回了无效数据", e)
        }
        root.optJSONObject("error")?.let { error ->
            throw IOException("节点 RPC 错误：" + error.optString("message", "未知错误"))
        }
        val result = root.optJSONObject("result")
            ?: throw IOException("节点 RPC 没有返回结果")
        val status = result.optString("status", "")
        if (status.isNotBlank() && status != "OK") {
            throw IOException("节点 RPC 状态异常：" + status)
        }
        return result
    }

    private fun post(endpoint: URL, payload: ByteArray, authorization: String?): RpcHttpResponse {
        val connection = endpoint.openConnection(rpcProxy()) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return RpcHttpResponse(
                code = code,
                body = body,
                authenticateChallenge = connection.getHeaderField("WWW-Authenticate")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun rpcProxy(): Proxy {
        val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
        val customProxyHost = prefs.getString(WALLET_PROXY, "").orEmpty().trim()
        val customProxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
        if (!useTor && customProxyHost.isNotBlank() && customProxyPort in 1..65535) {
            return Proxy(Proxy.Type.SOCKS, InetSocketAddress(customProxyHost, customProxyPort))
        }
        val socks = torService.socks
            ?: throw IOException("Tor SOCKS 代理尚未就绪，请等待节点连接后重试")
        val socksHost = socks.address.toString().substringAfterLast('/').trim()
        if (socksHost.isBlank() || socks.port.value !in 1..65535) {
            throw IOException("Tor SOCKS 代理地址无效")
        }
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socks.port.value))
    }

    private fun digestAuthorization(challenge: String, username: String, password: String): String {
        if (!challenge.startsWith("Digest", ignoreCase = true)) {
            throw IOException("节点 RPC 使用了不支持的认证方式")
        }
        val values = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            .findAll(challenge.removePrefixIgnoreCase("Digest").trim())
            .associate { match ->
                match.groupValues[1].lowercase(Locale.US) to
                    (match.groupValues[2].ifEmpty { match.groupValues[3] })
            }
        val realm = values["realm"] ?: throw IOException("节点认证信息缺少 realm")
        val nonce = values["nonce"] ?: throw IOException("节点认证信息缺少 nonce")
        val qop = values["qop"]?.split(',')?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
            ?: throw IOException("节点认证未提供 Digest qop=auth")
        val algorithm = values["algorithm"]?.uppercase(Locale.US) ?: "MD5"
        if (algorithm != "MD5" && algorithm != "MD5-SESS") {
            throw IOException("节点认证算法不受支持：" + algorithm)
        }
        val uri = "/json_rpc"
        val nonceCount = "00000001"
        val clientNonce = UUID.randomUUID().toString().replace("-", "")
        var ha1 = md5Hex(username + ":" + realm + ":" + password)
        if (algorithm == "MD5-SESS") ha1 = md5Hex(ha1 + ":" + nonce + ":" + clientNonce)
        val ha2 = md5Hex("POST:" + uri)
        val response = md5Hex(ha1 + ":" + nonce + ":" + nonceCount + ":" + clientNonce + ":" + qop + ":" + ha2)
        return buildString {
            append("Digest username=\"" + digestQuote(username) + "\"")
            append(", realm=\"" + digestQuote(realm) + "\"")
            append(", nonce=\"" + digestQuote(nonce) + "\"")
            append(", uri=\"" + uri + "\"")
            append(", algorithm=" + algorithm)
            append(", response=\"" + response + "\"")
            append(", qop=" + qop + ", nc=" + nonceCount + ", cnonce=\"" + clientNonce + "\"")
            values["opaque"]?.let { append(", opaque=\"" + digestQuote(it) + "\"") }
        }
    }
}

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

private fun digestQuote(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun guessRestoreHeight(timestamp: Long): Long {
    val elapsed = (timestamp - MONERO_GENESIS_TIMESTAMP).coerceAtLeast(0L)
    val forkElapsed = MONERO_V2_FORK_HEIGHT * MONERO_BLOCK_TIME_V1_SECONDS
    return if (elapsed <= forkElapsed) {
        elapsed / MONERO_BLOCK_TIME_V1_SECONDS
    } else {
        MONERO_V2_FORK_HEIGHT +
            (elapsed - forkElapsed) / MONERO_BLOCK_TIME_V2_SECONDS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResyncBlockchainScreen(
    onBackPress: () -> Unit,
    toastState: ToasterState,
    modifier: Modifier = Modifier
) {
    var restoreHeightInput by remember { mutableStateOf("") }
    var restoreDateInput by remember { mutableStateOf("") }
    var isQuerying by remember { mutableStateOf(false) }
    var queryCompleted by remember { mutableStateOf(false) }
    val walletState = koinInject<WalletState>()
    val walletPrefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val torService = koinInject<TorService>()
    val scope = rememberCoroutineScope()
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("重置同步区块") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(8f)
                    .padding(top = 56.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top
            ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "高度恢复",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = restoreHeightInput,
                                onValueChange = {
                                    restoreHeightInput = it.filter(Char::isDigit)
                                    if (restoreHeightInput.isNotBlank()) restoreDateInput = ""
                                    queryCompleted = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                ),
                                placeholder = {
                                    Text("输入恢复区块高度", color = labelColor)
                                }
                            )
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "时间恢复",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = restoreDateInput,
                                onValueChange = {
                                    restoreDateInput = it
                                    if (restoreDateInput.isNotBlank()) restoreHeightInput = ""
                                    queryCompleted = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                placeholder = {
                                    Text("YYYY-MM-DD", color = labelColor)
                                }
                            )
                        }
                    )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f)
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                AnonOutlineButton(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    onClick = {
                        if (isQuerying) {
                            // Avoid duplicate RPC lookups or rescan requests.
                        } else if (!queryCompleted) {
                            isQuerying = true
                            scope.launch {
                                try {
                                    val restoreInfo = withContext(Dispatchers.IO) {
                                        val wallet = WalletManager.instance?.wallet
                                            ?: throw IllegalStateException("钱包未打开")
                                        if (!wallet.isInitialized) {
                                            throw IllegalStateException("钱包尚未初始化")
                                        }
                                        val lookup = MoneroBlockTimeLookup(walletPrefs, torService)
                                        val selectedHeight = when {
                                            restoreHeightInput.isNotBlank() -> {
                                                restoreHeightInput.toLongOrNull()
                                                    ?: throw IOException("请输入有效的区块高度")
                                            }
                                            restoreDateInput.isNotBlank() -> {
                                                if (!Regex("""\d{4}-\d{2}-\d{2}""")
                                                        .matches(restoreDateInput)) {
                                                    throw IOException("日期格式应为 YYYY-MM-DD")
                                                }
                                                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                                    isLenient = false
                                                }
                                                val date = try {
                                                    parser.parse(restoreDateInput)
                                                } catch (_: Exception) {
                                                    null
                                                } ?: throw IOException("请输入有效日期（YYYY-MM-DD）")
                                                lookup.heightNearTimestamp(date.time / 1000L)
                                            }
                                            else -> wallet.getRestoreHeight().also {
                                                if (it < 0L) throw IOException("无法获取恢复区块高度")
                                            }
                                        }
                                        val latestHeight = lookup.latestHeight()
                                        if (selectedHeight < 0L || selectedHeight > latestHeight) {
                                            throw IOException("恢复高度必须在 0 到节点最新高度 $latestHeight 之间")
                                        }
                                        val header = lookup.headerAtHeight(selectedHeight)
                                        header.height to SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                            .format(Date(header.timestamp * 1000L))
                                    }
                                    restoreHeightInput = restoreInfo.first.toString()
                                    restoreDateInput = restoreInfo.second
                                    queryCompleted = true
                                    toastState.show(
                                        "查询完成：已定位真实区块高度 " + restoreInfo.first,
                                        type = ToastType.Success,
                                        duration = 4.seconds
                                    )
                                } catch (e: Exception) {
                                    queryCompleted = false
                                    toastState.show(
                                        "查询失败：" + (e.message ?: "无法查询真实区块信息"),
                                        type = ToastType.Warning,
                                        duration = 6.seconds
                                    )
                                } finally {
                                    isQuerying = false
                                }
                            }
                        } else {
                            val wallet = WalletManager.instance?.wallet
                            if (wallet == null) {
                                scope.launch {
                                    toastState.show("钱包未打开", type = ToastType.Warning)
                                }
                            } else if (!wallet.isInitialized) {
                                scope.launch {
                                    toastState.show("钱包尚未初始化，无法重置同步", type = ToastType.Warning)
                                }
                            } else {
                                isQuerying = true
                                scope.launch {
                                    var previousHeight: Long? = null
                                    try {
                                        val lookup = MoneroBlockTimeLookup(walletPrefs, torService)
                                        val selectedHeight = withContext(Dispatchers.IO) {
                                            when {
                                                restoreHeightInput.isNotBlank() ->
                                                    restoreHeightInput.toLongOrNull()
                                                restoreDateInput.isNotBlank() -> {
                                                    val date = try {
                                                        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                                            isLenient = false
                                                        }.parse(restoreDateInput)
                                                    } catch (_: Exception) {
                                                        null
                                                    }
                                                    date?.let {
                                                        lookup.heightNearTimestamp(it.time / 1000L)
                                                    }
                                                }
                                                else -> wallet.getRestoreHeight()
                                            }
                                        }
                                        if (selectedHeight == null || selectedHeight < 0L) {
                                            toastState.show(
                                                "请输入有效的区块高度或日期（YYYY-MM-DD）",
                                                type = ToastType.Warning
                                            )
                                            return@launch
                                        }

                                        val knownDaemonHeight = walletState.currentDaemonHeight
                                        val daemonTipHeight = if (knownDaemonHeight > 0L) {
                                            knownDaemonHeight
                                        } else {
                                            withContext(Dispatchers.IO) { lookup.latestHeight() }
                                        }
                                        if (daemonTipHeight > 0L && selectedHeight > daemonTipHeight) {
                                            toastState.show(
                                                "恢复高度不能高于当前节点高度（" + daemonTipHeight + "）",
                                                type = ToastType.Warning,
                                                duration = 6.seconds
                                            )
                                            return@launch
                                        }

                                        previousHeight = withContext(Dispatchers.IO) {
                                            wallet.getRestoreHeight()
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            wallet.setRestoreHeight(selectedHeight)
                                            if (!wallet.store()) {
                                                throw IllegalStateException("保存恢复高度失败")
                                            }
                                            walletState.resyncBlockchain()
                                        }
                                        if (result.isFailure) {
                                            withContext(Dispatchers.IO) {
                                                previousHeight?.let {
                                                    wallet.setRestoreHeight(it)
                                                    wallet.store()
                                                }
                                            }
                                            toastState.show(
                                                "重置失败：" + (result.exceptionOrNull()?.message ?: "未知错误"),
                                                type = ToastType.Warning,
                                                duration = 6.seconds
                                            )
                                        } else {
                                            walletPrefs.edit(commit = true) {
                                                putLong(RESTORE_HEIGHT, selectedHeight)
                                            }
                                            toastState.show(
                                                "已从所选真实区块高度开始重新扫描交易",
                                                type = ToastType.Success
                                            )
                                            onBackPress()
                                        }
                                    } catch (e: Exception) {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                previousHeight?.let {
                                                    wallet.setRestoreHeight(it)
                                                    wallet.store()
                                                }
                                            }
                                        } catch (rollbackError: Exception) {
                                            Timber.tag("ResyncBlockchain").e(
                                                rollbackError,
                                                "Restore-height rollback failed"
                                            )
                                        }
                                        toastState.show(
                                            "重置失败：" + (e.message ?: "无法查询真实区块信息"),
                                            type = ToastType.Warning,
                                            duration = 6.seconds
                                        )
                                    } finally {
                                        isQuerying = false
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (isQuerying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                        if (queryCompleted) "重置" else "查询"
                    )
                    }
                }
            }
        }
    }
}

