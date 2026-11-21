package com.qgming.zlib.core.designsystem

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.DomainStatus

fun DomainStatus.label(): String = when (this) {
    DomainStatus.UNKNOWN -> "未检测"
    DomainStatus.PROBING -> "检测中"
    DomainStatus.HEALTHY -> "可用"
    DomainStatus.CHALLENGED -> "需要人机验证"
    DomainStatus.DIAMWALL -> "被 DiamWall 拦截"
    DomainStatus.REDIRECT_LOOP -> "重定向异常"
    DomainStatus.HTTP_ERROR -> "HTTP 异常"
    DomainStatus.NETWORK_ERROR -> "网络不可达"
    DomainStatus.INVALID -> "域名无效"
}

/**
 * Single place that turns [AppError] into actionable, user-facing zh-CN copy.
 * Feature modules must not invent their own raw error strings.
 */
fun AppError.userMessage(): String = when (this) {
    AppError.NoDomain ->
        "还没有可用的域名。请检查网络后重试，或在设置中手动添加镜像。"

    AppError.NotLoggedIn -> "请先登录。"

    AppError.SessionExpired -> "登录状态已过期，请重新登录。"

    is AppError.AuthFailed ->
        message?.takeIf { it.isNotBlank() }?.let { "登录失败：$it；也可以尝试 Cookie 导入。" }
            ?: "邮箱或密码不正确。部分账号需改用 WebView 或 Cookie 导入。"

    is AppError.Quota -> buildString {
        append("今日下载额度已用完")
        if (remaining != null) append("（剩余 $remaining 本）")
        append("。")
        if (!resetHint.isNullOrBlank()) append("服务端提示：$resetHint")
    }

    is AppError.DomainBlocked -> when (status) {
        DomainStatus.DIAMWALL -> "当前域名被 DiamWall 拦截，请切换域名或使用 VPN/代理。"
        DomainStatus.CHALLENGED -> "当前域名要求人机验证，请切换域名。"
        DomainStatus.REDIRECT_LOOP -> "域名重定向异常，请切换域名。"
        DomainStatus.INVALID -> "域名无效或不在允许列表。"
        DomainStatus.HTTP_ERROR -> "域名返回异常响应，请切换域名。"
        DomainStatus.NETWORK_ERROR -> "无法连接该域名，请检查网络。"
        else -> "当前域名不可用，请切换域名。"
    }

    is AppError.Network -> "网络连接失败，请检查网络或 VPN 后重试。"

    is AppError.Server -> when (code) {
        502, 503, 504 -> "服务暂时不可用，请稍后重试。"
        401, 403 -> "没有访问权限，登录可能已失效。"
        404 -> "接口不存在，域名可能已失效，请切换域名。"
        else -> if (message.isNotBlank()) "服务端错误：$message" else "服务端错误（HTTP $code）。"
    }

    is AppError.Storage -> "写入本地存储失败。"

    is AppError.Parse -> message.ifBlank { "服务器响应格式异常，请稍后重试。" }

    is AppError.Unsupported -> message

    is AppError.Unknown -> "发生未知错误，请重试。"
}
