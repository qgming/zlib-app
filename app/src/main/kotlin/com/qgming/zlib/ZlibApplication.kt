package com.qgming.zlib

import android.app.Application
import android.content.pm.ApplicationInfo
import com.qgming.zlib.core.common.SafeLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZlibApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SafeLog.enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
