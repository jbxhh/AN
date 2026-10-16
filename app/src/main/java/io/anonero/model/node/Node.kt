package io.anonero.model.node

import io.anonero.AnonConfig
import io.anonero.model.NetworkType
import io.anonero.model.WalletManager
import kotlinx.serialization.Serializable
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.UnsupportedEncodingException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException


enum class NodeFields(val value: String) {
    RPC_HOST("host"),
    RPC_PORT("rpcPort"),
    RPC_USERNAME("username"),
    RPC_PASSWORD("passphrase"),
    RPC_NETWORK("network"),
    NODE_TRUSTED("trusted"),
    NODE_NAME("name");

    override fun toString(): String {
        return value
    }
}

@Serializable
open class Node {
    var networkType: NetworkType? = null
    var rpcPort = 0
    var name: String? = null
        private set
    var host: String? = null
    private var levinPort = 0
    var username = ""
    var password = ""
    var trusted = true
        private set

    internal constructor(nodeString: String?) {
        require(!nodeString.isNullOrEmpty()) { "daemon is empty" }
        var daemonAddress: String
        val a = nodeString.split("@".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        when (a.size) {
            1 -> { // no credentials
                daemonAddress = a[0]
                this.username = ""
                this.password = ""
            }

            2 -> { // credentials
                val userPassword =
                    a[0].split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                require(userPassword.size == 2) { "User:Password invalid" }
                this.username = userPassword[0]
                this.password = if (this.username.isNotEmpty()) {
                    userPassword[1]
                } else {
                    ""
                }
                daemonAddress = a[1]
            }

            else -> {
                throw IllegalArgumentException("Too many @")
            }
        }

        val daParts =
            daemonAddress.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        require(!(daParts.size > 3 || daParts.isEmpty())) { "Too many '/' or too few" }
        daemonAddress = daParts[0]
        val da = daemonAddress.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        require(!(da.size > 2 || da.isEmpty())) { "Too many ':' or too few" }
        val host = da[0]


        this.networkType = if (daParts.size == 1) {
            NetworkType.NetworkType_Mainnet
        } else {
            when (daParts[1]) {
                MAINNET -> NetworkType.NetworkType_Mainnet
                STAGENET -> NetworkType.NetworkType_Stagenet
                TESTNET -> NetworkType.NetworkType_Testnet
                else -> AnonConfig.getNetworkType()
            }
        }

//        require(networkType == WalletManager.instance?.networkType) { "wrong net: $networkType" }
        var name: String? = host
        if (daParts.size == 3) {
            try {
                name = URLDecoder.decode(daParts[2], "UTF-8")
            } catch (ex: UnsupportedEncodingException) {
                Timber.tag("Node.kt").w(ex) // if we can't encode it, we don't use it
            }
        }
        this.name = name
        val port: Int = if (da.size == 2) {
            try {
                da[1].toInt()
            } catch (ex: NumberFormatException) {
                throw IllegalArgumentException("Port not numeric")
            }
        } else {
            defaultRpcPort
        }
        try {
            this.host = host
        } catch (ex: UnknownHostException) {
            throw IllegalArgumentException("cannot resolve host $host")
        }
        this.rpcPort = port
        this.levinPort = this.defaultLevinPort
    }

    internal constructor(jsonObject: JSONObject?) {
        requireNotNull(jsonObject) { "daemon is empty" }
        if (jsonObject.has(NodeFields.RPC_USERNAME.value)) {
            username = jsonObject.getString(NodeFields.RPC_USERNAME.value)
        }
        if (jsonObject.has(NodeFields.RPC_PASSWORD.value)) {
            password = jsonObject.getString(NodeFields.RPC_PASSWORD.value)
        }
        if (jsonObject.has(NodeFields.RPC_HOST.value)) {
            this.host = jsonObject.getString(NodeFields.RPC_HOST.value)
        }
        this.rpcPort = if (jsonObject.has(NodeFields.RPC_PORT.value)) {
            jsonObject.getInt(NodeFields.RPC_PORT.value)
        } else {
            defaultRpcPort
        }
        if (jsonObject.has(NodeFields.NODE_NAME.value)) {
            this.name = jsonObject.getString(NodeFields.NODE_NAME.value)
        }
        if (jsonObject.has(NodeFields.RPC_NETWORK.value)) {
            networkType = when (jsonObject.getString(NodeFields.RPC_NETWORK.value)) {
                MAINNET -> NetworkType.NetworkType_Mainnet
                STAGENET -> NetworkType.NetworkType_Stagenet
                TESTNET -> NetworkType.NetworkType_Testnet
                else -> throw IllegalArgumentException(
                    "invalid net: " + jsonObject.getString(
                        NodeFields.RPC_NETWORK.value
                    )
                )
            }
            require(networkType == WalletManager.instance?.networkType) { "wrong net: $networkType" }
        }
        if (jsonObject.has(NodeFields.NODE_TRUSTED.value)) {
            this.trusted = jsonObject.getBoolean(NodeFields.NODE_TRUSTED.value)
        }
    }

    constructor() {
        networkType = WalletManager.instance?.networkType
    }

    // constructor used for created nodes from retrieved peer lists
    constructor(socketAddress: InetSocketAddress) : this() {
        host = socketAddress.hostString
        rpcPort = 0 // unknown
        levinPort = socketAddress.port
        username = ""
        password = ""
    }

    constructor(anotherNode: Node) {
        networkType = anotherNode.networkType
        overwriteWith(anotherNode)
    }

    override fun hashCode(): Int {
        return host.hashCode()
    }

    // Nodes are equal if they are the same host address:port & are on the same network
    override fun equals(other: Any?): Boolean {
        if (other !is Node) return false
        return trusted == other.trusted && host == other.host && address == other.address && rpcPort == other.rpcPort && networkType == other.networkType && username == other.username && password == other.password
    }


    fun toNodeString(): String {
        return "http://${host}:${rpcPort}"
    }

    fun toJson(): JSONObject {
        val jsonObject = JSONObject()
        try {
            jsonObject.put("username", username)
            jsonObject.put("password", password)
            jsonObject.put("host", host)
            jsonObject.put("rpcPort", rpcPort)
            when (networkType) {
                NetworkType.NetworkType_Mainnet -> jsonObject.put("network", MAINNET)
                NetworkType.NetworkType_Stagenet -> jsonObject.put("network", STAGENET)
                NetworkType.NetworkType_Testnet -> jsonObject.put("network", TESTNET)
                null -> TODO()
            }
            if (name?.isNotEmpty() == true) jsonObject.put("name", name)
            jsonObject.put("trusted", trusted)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        return jsonObject
    }

    override fun toString(): String {
        val sb = StringBuilder()
        if (username.isNotEmpty() && password.isNotEmpty()) {
            sb.append(username).append(":").append(password).append("@")
        }
        sb.append(host).append(":").append(rpcPort)
        sb.append("/")
        when (networkType) {
            NetworkType.NetworkType_Mainnet -> sb.append(MAINNET)
            NetworkType.NetworkType_Stagenet -> sb.append(STAGENET)
            NetworkType.NetworkType_Testnet -> sb.append(TESTNET)
            null -> TODO()
        }
        if (name != null) try {
            sb.append("/").append(URLEncoder.encode(name, "UTF-8"))
        } catch (ex: UnsupportedEncodingException) {
            Timber.tag("Node.kt").w(ex) // if we can't encode it, we don't store it
        }
        return sb.toString()
    }

    val address: String
        get() = "$host:$rpcPort"

    private fun overwriteWith(anotherNode: Node) {
        check(networkType == anotherNode.networkType) { "network types do not match" }
        name = anotherNode.name
        host = anotherNode.host
        rpcPort = anotherNode.rpcPort
        levinPort = anotherNode.levinPort
        username = anotherNode.username
        password = anotherNode.password
        trusted = anotherNode.trusted
    }

    companion object {
        const val MAINNET = "mainnet"
        const val STAGENET = "stagenet"
        const val TESTNET = "testnet"
        private var DEFAULT_LEVIN_PORT = 0
        private var DEFAULT_RPC_PORT = 0

        val defaultRpcPort: Int
            // every node knows its network, but they are all the same
            get() {

                if (DEFAULT_RPC_PORT > 0) return DEFAULT_RPC_PORT
                DEFAULT_RPC_PORT = when (WalletManager.instance?.networkType) {
                    NetworkType.NetworkType_Mainnet -> 18081
                    NetworkType.NetworkType_Testnet -> 28081
                    NetworkType.NetworkType_Stagenet -> 38081
                    else -> throw IllegalStateException("unsupported net " + WalletManager.instance?.networkType)
                }
                return DEFAULT_RPC_PORT
            }


        fun fromString(nodeString: String?): Node? {
            return try {
                Node(nodeString)
            } catch (ex: IllegalArgumentException) {
                Timber.tag("Node.kt").w(ex)
                null
            }
        }

        fun fromJson(jsonObject: JSONObject?): Node? {
            return try {
                Node(jsonObject)
            } catch (ex: IllegalArgumentException) {
                Timber.tag("Node.kt").w(ex)
                null
            } catch (ex: UnknownHostException) {
                Timber.tag("Node.kt").w(ex)
                null
            } catch (ex: JSONException) {
                Timber.tag("Node.kt").w(ex)
                null
            }
        }
    }

    private val defaultLevinPort: Int
        // every node knows its network, but they are all the same
        get() {
            if (DEFAULT_LEVIN_PORT > 0) return DEFAULT_LEVIN_PORT
            DEFAULT_LEVIN_PORT = when (WalletManager.instance?.networkType) {
                NetworkType.NetworkType_Mainnet -> 18080
                NetworkType.NetworkType_Testnet -> 28080
                NetworkType.NetworkType_Stagenet -> 38080
                else -> throw IllegalStateException("unsupported net " + WalletManager.instance?.networkType)
            }
            return DEFAULT_LEVIN_PORT
        }
}