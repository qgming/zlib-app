package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.model.AccountEntry
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibAccountCard(entry: AccountEntry, busy: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val profile = entry.profile
    val quota = profile?.quota
    val quotaKnown = quota?.known == true && quota.amount != null && quota.remaining != null
    val colors = MiuixTheme.colorScheme
    val primary = colors.primary
    val track = colors.onSurfaceVariantSummary.copy(alpha = 0.18f)
    ZlibCard(
        cornerRadius = 24.dp,
        modifier = Modifier.combinedClickable(
            enabled = !busy, role = Role.Button, onClickLabel = "切换账号", onLongClickLabel = "账号操作",
            onClick = onClick, onLongClick = onLongClick,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile?.name?.takeIf(String::isNotBlank) ?: "Z-Library 用户", style = MiuixTheme.textStyles.title3,
                        modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (profile?.membershipKnown == true) { if (profile.isPremium) "Premium 会员" else "标准账号" } else "账号",
                        style = MiuixTheme.textStyles.footnote2, color = primary,
                        modifier = Modifier.background(primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(profile?.email?.takeIf(String::isNotBlank) ?: "账号 ${entry.id}", style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text(
                    when { busy -> "正在更新…"; entry.expired -> "登录已失效，点击重新验证"; entry.current -> "当前使用"; else -> "点击切换" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (entry.current) primary else colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(80.dp).semantics { contentDescription = if (quotaKnown) "今日剩余 ${quota?.remaining} 本，共 ${quota?.allowed} 本" else "额度未知" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(76.dp)) {
                    val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(track, -90f, 360f, false, style = stroke)
                    if (quotaKnown && quota != null) {
                        val fraction = if ((quota.allowed ?: 0) > 0) ((quota.remaining ?: 0).toFloat() / quota.allowed!!).coerceIn(0f, 1f) else 0f
                        drawArc(primary, -90f, fraction * 360, false, style = stroke)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (quotaKnown) "${quota?.remaining}" else "—", style = MiuixTheme.textStyles.title3)
                    Text("剩余", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
fun ZlibAutoSwitchPreference(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ZlibCard(cornerRadius = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("额度耗尽自动切换", style = MiuixTheme.textStyles.body2)
                Text("仅切换至仍有额度的已登录账号", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}
