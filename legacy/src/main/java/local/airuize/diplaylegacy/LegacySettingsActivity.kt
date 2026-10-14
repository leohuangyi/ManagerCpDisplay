package local.airuize.diplaylegacy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The settings screen of the legacy port, modeled after the reference project's settings
 * groups (DiPlayActivity), trimmed to what the legacy stack can actually obey: auto-connect,
 * network mode, router password, device management, frame rate and right-hand drive.
 * Registered as its own launcher entry so the head unit's app list shows "CarPlay设置".
 */
class LegacySettingsActivity : Activity() {

    private companion object {
        const val COLOR_BACKGROUND = 0xFF10141A.toInt()
        const val COLOR_CARD = 0xFF1B222B.toInt()
        const val COLOR_SECTION = 0xFF8195A5.toInt()
        const val COLOR_LABEL = 0xFFECEFF1.toInt()
        const val COLOR_VALUE = 0xFF90A4AE.toInt()
        const val COLOR_HINT = 0xFF5E6E7B.toInt()

        val NETWORK_MODE_LABELS = mapOf(
            "auto" to "自动热点（推荐）",
            "direct" to "Wi-Fi 直连",
            "router" to "同一路由器",
            "reuse" to "复用已有热点",
        )
        val FPS_LABELS = listOf("30 FPS（更省资源）", "60 FPS（更费资源）")
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        root.addView(TextView(this).apply {
            text = "CarPlay设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), 0, dp(24f), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(4f), dp(24f), dp(24f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Rebuilds every card from the persisted values, like the reference settings screen. */
    private fun render() {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        content.removeAllViews()

        fun caption(text: String) {
            content.addView(TextView(this).apply {
                setText(text)
                setTextColor(COLOR_SECTION)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(6f), dp(18f), 0, dp(8f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun card(block: (LinearLayout) -> Unit) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(COLOR_CARD)
                }
                setPadding(dp(20f), dp(14f), dp(20f), dp(8f))
            }
            content.addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            block(column)
        }

        fun row(card: LinearLayout, block: (LinearLayout) -> Unit) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
            }
            card.addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            block(column)
        }

        fun title(text: String): TextView = TextView(this).apply {
            setText(text)
            setTextColor(COLOR_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        fun summary(text: String): TextView = TextView(this).apply {
            setText(text)
            setTextColor(COLOR_HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4f), 0, 0)
        }

        fun toggleRow(card: LinearLayout, title: String, summary: String, checked: Boolean,
                      onChanged: (Boolean) -> Unit) {
            row(card) { r ->
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(title(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val toggle = Switch(this).apply { isChecked = checked }
                toggle.setOnCheckedChangeListener { _, now -> if (now != checked) onChanged(now) }
                top.addView(toggle, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                r.addView(top, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (summary.isNotEmpty()) r.addView(summary(summary))
            }
        }

        fun choiceRow(card: LinearLayout, title: String, value: String, summary: String,
                      options: List<String>, onSelected: (Int) -> Unit) {
            row(card) { r ->
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(title(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(TextView(this).apply {
                    text = value
                    setTextColor(COLOR_VALUE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                top.addView(TextView(this).apply {
                    text = "\u203a" // ›
                    setTextColor(COLOR_VALUE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(6f), 0, 0, 0)
                })
                r.addView(top)
                if (summary.isNotEmpty()) r.addView(summary(summary))
                r.setOnClickListener {
                    AlertDialog.Builder(this@LegacySettingsActivity)
                        .setTitle(title)
                        .setItems(options.toTypedArray()) { _, which -> onSelected(which) }
                        .show()
                }
            }
        }

        fun routerPasswordRow(card: LinearLayout, title: String, value: String, summary: String) {
            row(card) { r ->
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(title(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(TextView(this).apply {
                    text = value
                    setTextColor(COLOR_VALUE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                top.addView(TextView(this).apply {
                    text = "\u203a" // ›
                    setTextColor(COLOR_VALUE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(6f), 0, 0, 0)
                })
                r.addView(top)
                if (summary.isNotEmpty()) r.addView(summary(summary))
                r.setOnClickListener {
                    val input = EditText(this).apply {
                        setText(LegacySettings.routerPassword(this@LegacySettingsActivity))
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    AlertDialog.Builder(this@LegacySettingsActivity)
                        .setTitle(title)
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            LegacySettings.setRouterPassword(this@LegacySettingsActivity, input.text.toString())
                            render()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        fun deviceRow(card: LinearLayout, device: LegacySettings.PairedDevice) {
            row(card) { r ->
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(title(device.label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(TextView(this).apply {
                    text = "删除"
                    setTextColor(0xFFEF5350.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(20f), dp(4f), 0, dp(4f))
                })
                r.addView(top)
                r.addView(summary("删除后需与该 iPhone 重新配对才能再次连接"))
                r.setOnClickListener {
                    AlertDialog.Builder(this@LegacySettingsActivity)
                        .setTitle("删除设备")
                        .setMessage("确定删除「${device.label}」吗？删除后需与该 iPhone 重新配对才能再次连接。")
                        .setPositiveButton("删除") { _, _ ->
                            LegacySettings.deletePairedDevice(this@LegacySettingsActivity, device.key)
                            render()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        caption("连接")
        card { c ->
            toggleRow(c, "打开即连接", "打开 CarPlay 应用后自动连接 iPhone",
                LegacySettings.autoConnect(this)) { enabled ->
                LegacySettings.setAutoConnect(this, enabled)
                render()
            }
            val mode = LegacySettings.networkMode(this)
            choiceRow(c, "网络模式", NETWORK_MODE_LABELS[mode] ?: NETWORK_MODE_LABELS.getValue("auto"),
                "更改将在下次连接时生效",
                LegacySettings.networkModes.map { NETWORK_MODE_LABELS[it] ?: it }) { index ->
                LegacySettings.setNetworkMode(this, LegacySettings.networkModes[index])
                render()
            }
            routerPasswordRow(c, "路由器密码",
                if (LegacySettings.routerPassword(this).isEmpty()) "未设置" else "已设置",
                "同一路由器模式下，iPhone 加入路由器所用的 WPA2 密码，更改将在下次连接时生效")
        }

        caption("设备管理")
        card { c ->
            val devices = LegacySettings.pairedDevices(this)
            if (devices.isEmpty()) {
                row(c) { r ->
                    r.addView(title("暂无已配对设备"))
                    r.addView(summary("iPhone 连接配对后，会在此列出并可删除"))
                }
            } else {
                devices.forEach { deviceRow(c, it) }
            }
        }

        caption("显示")
        card { c ->
            val fps = LegacySettings.fps(this)
            choiceRow(c, "帧率", if (fps == 60) FPS_LABELS[1] else FPS_LABELS[0],
                "更改将在下次连接时生效", FPS_LABELS) { index ->
                LegacySettings.setFps(this, if (index == 1) 60 else 30)
                render()
            }
        }

        caption("CarPlay 控制")
        card { c ->
            toggleRow(c, "右手驾驶", "车辆靠左行驶时使用，重新连接后生效",
                LegacySettings.rightHandDrive(this)) { enabled ->
                LegacySettings.setRightHandDrive(this, enabled)
                render()
            }
        }
    }
}