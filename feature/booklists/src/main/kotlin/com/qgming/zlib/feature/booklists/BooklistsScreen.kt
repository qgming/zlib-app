package com.qgming.zlib.feature.booklists

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibStateCard

@Composable
fun BooklistsScreen(padding: PaddingValues) {
    ZlibCardList(padding) {
        item(key = "booklists-unavailable") {
            ZlibStateCard(
                title = "云端书单尚未接入",
                message = "暂时无法在应用中读取书单，可前往 Z-Library 网站的「我的图书馆」查看。",
            )
        }
    }
}
