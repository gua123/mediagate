package io.github.gua123.mediagate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 出问题时**把原因摆到屏幕上**（**2026-10-03 P0 教训**）。
 *
 * 背景：0.1.22–0.1.25 因为启动即崩，用户看到的是"打不开"——什么都没留下，
 * 我们也拿不到任何线索（没有 adb，崩溃报告又在应用私有目录里，而 App 根本进不去）。
 *
 * 做法两条：
 * 1. 它跑在**自己的进程**（清单里 `android:process=":crash"`）——主进程崩掉/被杀，这个页面照样在，
 *    用户能看清、能截图、能复制；
 * 2. 任何未捕获异常、以及 `Application.onCreate` 里的失败，都会把它拉起来并带上完整文本。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "MediaGate 出错了"
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val clipboard = getSystemService(ClipboardManager::class.java)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashContent(
                        title = title,
                        body = body,
                        onCopy = {
                            runCatching {
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("mediagate-crash", title + "\n\n" + body),
                                )
                            }
                        },
                        onShare = { share(title, body) },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun share(title: String, body: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, title + "\n\n" + body)
            startActivity(Intent.createChooser(intent, "分享错误信息"))
        }
    }

    companion object {

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"

        /** 弹出错误页（失败就算了——它只是"告诉用户"的手段，不能反过来把主流程弄崩）。 */
        fun show(context: Context, title: String, body: String) {
            runCatching {
                context.startActivity(
                    Intent(context, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(EXTRA_TITLE, title)
                        .putExtra(EXTRA_BODY, body),
                )
            }
        }
    }
}

@Composable
private fun CrashContent(
    title: String,
    body: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = "把下面这段发给我就行（也可以直接截图）：", style = MaterialTheme.typography.bodyMedium)
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy) { Text("复制全部") }
            TextButton(onClick = onShare) { Text("分享") }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}
