package io.anonero.rusttrial

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import io.anonero.rust.RustNativeBridge

/**
 * Minimal test harness for the standalone Rust backend.
 *
 * It is deliberately not presented as a working wallet: seed restoration,
 * daemon synchronization, balance/history, subaddress management and sending
 * are not implemented by this first-stage trial.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = "ANONERO · Rust Trial"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "独立 Rust 后端测试版（不加载旧 C++ 钱包库）"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        root.addView(TextView(this).apply {
            text = RustNativeBridge.backendInfoOrNull()
                ?.let { "Rust 后端已加载：\n$it" }
                ?: "Rust 后端加载失败：未使用 C++ 回退"
            textSize = 14f
            setPadding(0, 0, 0, dp(20))
        })

        val network = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("主网 Mainnet", "测试网 Testnet", "Stagenet")
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        root.addView(network)

        val addressInput = EditText(this).apply {
            hint = "输入 Monero 地址"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 3
            setText("")
        }
        root.addView(
            addressInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val validateButton = Button(this).apply {
            text = "用 Rust 校验地址"
        }
        root.addView(validateButton)

        val result = TextView(this).apply {
            text = "此版本仅验证地址格式和网络类型。"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(result)

        root.addView(TextView(this).apply {
            text = "尚不支持助记词恢复、钱包同步、余额、交易记录、子地址管理或转账。请勿输入助记词或用于存放真实资金。"
            textSize = 13f
        })

        validateButton.setOnClickListener {
            val address = addressInput.text?.toString()?.trim().orEmpty()
            if (address.isEmpty()) {
                result.text = "请先输入地址。"
                return@setOnClickListener
            }

            result.text = when (
                RustNativeBridge.validateAddressOrNull(address, network.selectedItemPosition)
            ) {
                true -> "地址格式正确，且网络类型匹配。"
                false -> "地址无效，或与所选网络不匹配。"
                null -> "Rust 后端不可用；本试验版不会回退到 C++。"
            }
        }

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
