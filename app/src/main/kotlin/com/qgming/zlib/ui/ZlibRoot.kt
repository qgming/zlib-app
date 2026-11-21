package com.qgming.zlib.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.qgming.zlib.core.designsystem.ZlibAppShell
import com.qgming.zlib.core.designsystem.ZlibPlaceholder
import com.qgming.zlib.core.designsystem.ZlibTab
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings

@Composable
fun ZlibRoot() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            ZlibTab("搜索", MiuixIcons.Search),
            ZlibTab("书架", MiuixIcons.ContactsBook),
            ZlibTab("下载", MiuixIcons.Download),
            ZlibTab("我的", MiuixIcons.Settings),
        )
    }

    ZlibAppShell(
        title = tabs[selectedTab].label,
        tabs = tabs,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
    ) { padding ->
        when (selectedTab) {
            0 -> ZlibPlaceholder(
                title = "搜索",
                body = "登录后在这里搜索书名、作者或 ISBN。",
                padding = padding,
            )

            1 -> ZlibPlaceholder(
                title = "书架",
                body = "已下载、收藏和历史会汇总到这里。",
                padding = padding,
            )

            2 -> ZlibPlaceholder(
                title = "下载",
                body = "下载队列、进度和失败重试会显示在这里。",
                padding = padding,
            )

            else -> ZlibPlaceholder(
                title = "我的",
                body = "账号、域名、下载目录和主题设置。",
                padding = padding,
            )
        }
    }
}
