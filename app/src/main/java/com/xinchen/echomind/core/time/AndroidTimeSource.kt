package com.xinchen.echomind.core.time

import android.os.SystemClock

class AndroidTimeSource : TimeSource {
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}
