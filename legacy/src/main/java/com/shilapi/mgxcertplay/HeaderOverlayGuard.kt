package com.shilapi.mgxcertplay

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * 压制车机原厂顶部状态栏（HeaderService）的中断弹窗。
 *
 * 背景：MY16ADA 车机在蓝牙 AVRCP 曲目元数据（歌名/曲目号，即「歌词」）变化时，会通过
 * HeaderService 在顶部状态栏弹出 AUDIOINTERRUPT_SONG(17) 中断，覆盖在 DiPlay 的 CarPlay
 * 界面上。原生做法是进入独占界面时向 HeaderService 发送「禁止打断」命令、退出时恢复。
 *
 * 这里直接复刻 [com.mitsubishielectric.ada.app.headerservice.HeaderControlApService]
 * 的 [setHeaderInterruptStatus] 所发的广播：
 *   - 禁止打断 = FUNCTION_BAN_INTERRUPT_SET(6)
 *   - 恢复打断 = FUNCTION_BAN_INTERRUPT_CANCEL(7)
 * 广播 action 为 com.mitsubishielectric.ada.header.UPDATE，主要载荷是 dataFunc=[func,0]。
 */
object HeaderOverlayGuard {
    private const val TAG = "HeaderOverlayGuard"

    private const val INTENT_ACTION = "com.mitsubishielectric.ada.header.UPDATE"
    private const val FUNCTION_BAN_INTERRUPT_SET = 6
    private const val FUNCTION_BAN_INTERRUPT_CANCEL = 7

    private const val EXTRAS_KEY_FUNC = "dataFunc"
    private const val EXTRAS_KEY_TYPE = "dataType"
    private const val EXTRAS_KEY_LAYOUT = "dataLayout"
    private const val EXTRAS_KEY_PARTSINT = "dataPartsInt"
    private const val EXTRAS_KEY_PARTSSTRING = "dataPartsString"
    private const val EXTRAS_KEY_CHANGESOURCE = "dataChangeSource"
    private const val EXTRAS_KEY_EXSTATE = "dataExtraStatus"

    /** DiPlay 进入 CarPlay 前台时调用：禁止状态栏中断，避免歌词/曲目覆盖界面。 */
    fun banInterrupt(context: Context) {
        send(context, FUNCTION_BAN_INTERRUPT_SET)
    }

    /** DiPlay 退出 CarPlay 前台时调用：恢复状态栏中断的正常显示。 */
    fun restoreInterrupt(context: Context) {
        send(context, FUNCTION_BAN_INTERRUPT_CANCEL)
    }

    private fun send(context: Context, funcId: Int) {
        runCatching {
            val intent = Intent(INTENT_ACTION).apply {
                putExtra(EXTRAS_KEY_TYPE, -1)
                putExtra(EXTRAS_KEY_LAYOUT, -1)
                putExtra(EXTRAS_KEY_FUNC, intArrayOf(funcId, 0))
                putExtra(EXTRAS_KEY_PARTSINT, IntArray(0))
                putExtra(EXTRAS_KEY_PARTSSTRING, arrayOf<String>())
                putExtra(EXTRAS_KEY_CHANGESOURCE, null as Array<String>?)
                putExtra(EXTRAS_KEY_EXSTATE, -1)
                putExtra("PID", Process.myPid())
                putExtra("TID", Process.myTid())
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "header interrupt ${if (funcId == FUNCTION_BAN_INTERRUPT_SET) "ban" else "restore"} sent")
        }.onFailure { Log.e(TAG, "failed to send header interrupt command", it) }
    }
}