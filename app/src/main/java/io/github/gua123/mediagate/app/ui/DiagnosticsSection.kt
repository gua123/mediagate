package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「诊断」卡片（**2026-10-03** 真机闪退之后加的）。
 *
 * 自用 sideload 拿不到 logcat：出问题时用户只能描述"闪退了"。这里把崩溃报告摆到界面上，
 * 一眼能看到时间与异常首行，点开看全文（可长按选中复制），粘给我即可定位。
 */
@Composable
fun DiagnosticsSection(
    crashReport: String?,
    crashTimeMs: Long?,
    reportCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFull by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostics_subtitle), style = MaterialTheme.typography.bodySmall)

            if (crashReport == null) {
                Text(stringResource(R.string.diagnostics_no_crash), style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            val time = crashTimeMs?.let {
                SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))
            } ?: "未知时间"
            Text(
                text = stringResource(R.string.diagnostics_last_crash, time, reportCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            // 首行摘要：异常类型 + message，一眼看是哪一类问题
            Text(
                text = crashSummary(crashReport),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showFull = true }) {
                    Text(stringResource(R.string.diagnostics_view))
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
            }
        }
    }

    if (showFull && crashReport != null) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                // 可滚动 + 可选中：用户可以长按复制，直接发给我
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = crashReport,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFull = false }) {
                    Text(stringResource(R.string.diagnostics_close))
                }
            },
        )
    }
}

/** 从报告全文里抽出异常那一行（「== 异常 ==」之后的第一行）；抽不到就退回首行。 */
internal fun crashSummary(report: String): String {
    val lines = report.lines()
    val header = lines.indexOfFirst { it.contains("== 异常 ==") }
    val candidate = if (header >= 0) lines.getOrNull(header + 1) else null
    return (candidate ?: lines.firstOrNull()).orEmpty().trim().ifEmpty { "（报告为空）" }
}
