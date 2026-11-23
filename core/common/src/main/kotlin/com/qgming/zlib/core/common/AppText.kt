package com.qgming.zlib.core.common

import android.content.Context

/** Resource access for repositories, notifications and ViewModel events outside composition. */
object AppText {
    @Volatile private var application: Context? = null

    fun initialize(context: Context) {
        application = context.applicationContext
    }

    fun get(id: Int, vararg arguments: Any?): String = application?.let { context ->
        if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments)
    } ?: DefaultAppTexts.get(id, arguments)
}

fun appText(id: Int, vararg arguments: Any?): String = AppText.get(id, *arguments)
