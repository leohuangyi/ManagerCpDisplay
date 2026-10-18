package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * 共享的 Robolectric shadow：以 [allowed] 模拟 WRITE_SETTINGS 授权状态，供
 * CarHotspotFallbackTest / CarHotspotSwitchTest 复用。
 *
 * 原先它嵌套在基于本地 ADB 协议（FakeAdbd）的 CarHotspotAdbGrantTest 中；该协议随
 * LocalAdb 迁移到小助手广播桥后删除，shadow 独立保留于此。
 */
@Implements(Settings.System::class)
class WritePermission {
    companion object {
        @Volatile var allowed = false

        @JvmStatic
        @Implementation
        fun canWrite(context: Context): Boolean = allowed
    }
}