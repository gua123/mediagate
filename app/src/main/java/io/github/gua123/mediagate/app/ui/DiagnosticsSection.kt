package io.github.gua123.mediagate.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.gua123.mediagate.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「诊断」卡片（**2026-10-03** 真机闪退之后加的）。
 *
 * 自用 sideload 拿不到 logcat：出问题时用户只能描述"闪退了"。这里把崩溃报告摆到界面上，
 * 一眼能看到时间与异常首行，点开看全文。
 *
 * **2026-10-03 用户反馈「错误报告需要增加复制文字或者另存为文件，否则只能截图」** →
 * 对话框里补三件事：① 「复制全文」写进系统剪贴板（Android 13+ 系统自己会弹"已复制"提示）；
 * ② 「另存为文件」走 SAF 的 `ACTION_CREATE_DOCUMENT`，存成 .txt 任意位置；
 * ③ 「分享」走 `ACTION_SEND`（可以直接发到微信/邮件）。
 * 正文本身也套了 [SelectionContainer]，长按还能手动选。
 */
@Composable
fun DiagnosticsSection(
    crashReport: String?,
    crashTimeMs: Long?,
    reportCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** 上次进程退出原因（现读 ApplicationExitInfo），形如"内存不足被系统杀掉 · 10-02 07:13"。 */
    lastExit: String? = null,
    /** 磁盘上的面包屑（旧 → 新）：原生崩溃时唯一能留下的"最后走到哪一步"。 */
    breadcrumbs: List<String> = emptyList(),
) {
    var showFull by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toast = remember { ToastHolder(context) }
    // 没有崩溃报告时，把"上次退出原因 + 面包屑"凑成一份能复制发走的诊断文本（用户的原始诉求：
    // "没有捕捉到崩溃日志"——那就至少给点别的证据）
    val fallbackReport = remember(lastExit, breadcrumbs) {
        fallbackDiagnosticText(lastExit, breadcrumbs)
    }

    // 「另存为」：系统文件选择器（SAF），用户挑位置，我们只负责写文本
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val report = crashReport ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(report.toByteArray(Charsets.UTF_8))
            } ?: error("打不开写入通道")
        }.isSuccess
        toast.show(if (ok) context.getString(R.string.diagnostics_saved) else context.getString(R.string.diagnostics_save_failed))
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostics_subtitle), style = MaterialTheme.typography.bodySmall)

            if (crashReport == null) {
                Text(stringResource(R.string.diagnostics_no_crash), style = MaterialTheme.typography.bodySmall)
                lastExit?.let { Text(stringResource(R.string.diagnostics_last_exit, it), style = MaterialTheme.typography.bodySmall) }
                if (breadcrumbs.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.diagnostics_breadcrumbs) + "\n" + breadcrumbs.takeLast(8).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (fallbackReport != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { toast.show(copyReport(context, fallbackReport)) }) {
                            Text(stringResource(R.string.diagnostics_copy))
                        }
                        TextButton(onClick = { shareReport(context, fallbackReport) }) {
                            Text(stringResource(R.string.diagnostics_share))
                        }
                    }
                }
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
            // 卡片上直接给"一次性拿到全部"的三个出口（不用打开对话框）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { toast.show(copyReport(context, crashReport)) }) {
                    Text(stringResource(R.string.diagnostics_copy))
                }
                TextButton(onClick = { saveLauncher.launch(defaultReportFileName(crashTimeMs)) }) {
                    Text(stringResource(R.string.diagnostics_save))
                }
                TextButton(onClick = { shareReport(context, crashReport) }) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            }
        }
    }

    if (showFull && crashReport != null) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                // 可滚动 + 可选中：长按能手动选，也可以直接用下面的三个按钮
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            text = crashReport,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFull = false }) {
                    Text(stringResource(R.string.diagnostics_close))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { toast.show(copyReport(context, crashReport)) }) {
                        Text(stringResource(R.string.diagnostics_copy))
                    }
                    TextButton(onClick = { saveLauncher.launch(defaultReportFileName(crashTimeMs)) }) {
                        Text(stringResource(R.string.diagnostics_save))
                    }
                    TextButton(onClick = { shareReport(context, crashReport) }) {
                        Text(stringResource(R.string.diagnostics_share))
                    }
                }
            },
        )
    }
}

/**
 * 没有崩溃报告时的"兜底诊断文本"（**2026-10-03 用户反馈"没有捕捉到崩溃日志"**）。
 *
 * 把"上次进程退出原因 + 面包屑"凑成一段可复制的文本——原生崩溃 / 被系统杀掉这类情形，
 * 崩溃处理器根本不会跑，但这两样信息足以判断"是不是 VLC 那条路把进程带走了"。
 *
 * @return 两者都没有时返回 null（那时界面上也没什么可给的）。
 */
internal fun fallbackDiagnosticText(lastExit: String?, breadcrumbs: List<String>): String? {
    if (lastExit == null && breadcrumbs.isEmpty()) return null
    val builder = StringBuilder()
    builder.append("== mediagate 诊断信息（没有崩溃报告）==").append('\n')
    builder.append("上次进程退出：").append(lastExit ?: "（系统没给原因）").append('\n')
    builder.append('\n').append("== 最后走到哪一步（面包屑）==").append('\n')
    if (breadcrumbs.isEmpty()) {
        builder.append("（没有面包屑）").append('\n')
    } else {
        breadcrumbs.forEach { builder.append(it).append('\n') }
    }
    return builder.toString()
}

/** 把报告写进系统剪贴板；返回给用户看的提示语。 */
private fun copyReport(context: Context, report: String): String {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_title), report))
    return context.getString(R.string.diagnostics_copied)
}

/** 分享（ACTION_SEND）：可以直接发到微信/邮件，报告作为纯文本正文。 */
private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_title))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.diagnostics_share))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
        .onFailure { error ->
            Toast.makeText(context, context.getString(R.string.diagnostics_share_failed), Toast.LENGTH_SHORT).show()
            android.util.Log.w("diagnostics", "分享报告失败", error)
        }
}

/** 默认文件名：崩溃时间（拿不到就用当前时间）。 */
internal fun defaultReportFileName(crashTimeMs: Long?): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(crashTimeMs ?: System.currentTimeMillis()))
    return "mediagate-崩溃报告-" + stamp + ".txt"
}

/** 从报告全文里抽出异常那一行（「== 异常 ==」之后的第一行）；抽不到就退回首行。 */
internal fun crashSummary(report: String): String {
    val lines = report.lines()
    val header = lines.indexOfFirst { it.contains("== 异常 ==") }
    val candidate = if (header >= 0) lines.getOrNull(header + 1) else null
    return (candidate ?: lines.firstOrNull()).orEmpty().trim().ifEmpty { "（报告为空）" }
}

/** 小工具：Toast 只在需要时创建，避免每次重组都 new 一个。 */
private class ToastHolder(private val context: Context) {
    fun show(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
