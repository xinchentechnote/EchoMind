package com.xinchen.echomind.work

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 前台服务类型必须写在**合并后的清单**里（AGENTS.md §1「UI/交互改动必须模拟器实测」踩出来的坑）。
 *
 * 实测到的现象：转录页点「开始转录」之后 WorkManager 确实起来了 `SystemForegroundService`，
 * 但 `setForeground()` 立刻把进程打死——
 * `IllegalArgumentException: foregroundServiceType 0x00000001 is not a subset of
 * foregroundServiceType attribute 0x00000000 in service element of manifest file`。
 * 原因是 work-runtime 2.12.0 自带清单里那条 service 声明**没有** `foregroundServiceType`，
 * Android 14+（本项目 targetSdk 36）要求类型既在 service 上声明、又有配对的权限。
 *
 * 这两条断言读的是设备上的真清单与真权限，所以它测的是「装出来的东西」而不是代码里的字符串——
 * 谁把 `tools:node="merge"` 那段删了，这里立刻红。
 */
class TranscriptForegroundServiceTypeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `workManager的前台服务在合并清单里声明了dataSync类型`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail("这条测试要 API 29+ 的设备（foregroundServiceType 从 29 才有）")
        }
        val info = context.packageManager.getServiceInfo(
            ComponentName(context.packageName, SYSTEM_FOREGROUND_SERVICE),
            0,
        )
        assertTrue(
            "${SYSTEM_FOREGROUND_SERVICE} 的 foregroundServiceType 是 ${info.foregroundServiceType}，" +
                "不含 dataSync —— 转录的 setForeground() 会直接把进程打崩",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0,
        )
    }

    @Test
    fun `前台服务类型与配对的权限一起声明`() {
        // 只有 type 没有 FOREGROUND_SERVICE_DATA_SYNC，startForeground 会抛 SecurityException：
        // 两处必须成对，所以成对地断言
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue("清单缺权限 $FGS_PERMISSION", requested.contains(FGS_PERMISSION))
        assertTrue("清单缺权限 $FGS_BASE_PERMISSION", requested.contains(FGS_BASE_PERMISSION))
        assertTrue("清单缺权限 POST_NOTIFICATIONS", requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    private companion object {
        const val SYSTEM_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        const val FGS_PERMISSION = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        const val FGS_BASE_PERMISSION = "android.permission.FOREGROUND_SERVICE"
    }
}
