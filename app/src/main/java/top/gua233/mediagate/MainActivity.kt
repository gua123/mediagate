package io.github.gua123.mediagate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.ui.theme.MediaGateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaGateTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SkeletonScreen()
                }
            }
        }
    }
}

/** M0 占位页：工程骨架自检，M1 起由首页替换。 */
@Composable
private fun SkeletonScreen() {
    val modules = listOf(
        "core/{common, model, database, crypto, network}",
        "data/storage-{api, local, webdav, sftp, ftp}",
        "media/{engine, proxy, playback, thumbnail, tsext, ffmpeg, asr, subtitle}",
        "feature/{home, browser, player-video, player-audio, viewer-image, connections, settings, tasks}",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "MediaGate", style = MaterialTheme.typography.headlineMedium)
        Text(text = "工程骨架已就绪（M0）", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "多协议媒体播放器：本地 / WebDAV / SFTP / FTP，双播放内核，字幕与本地音转字幕（R1–R19）。",
            style = MaterialTheme.typography.bodyMedium,
        )
        modules.forEach { m ->
            Text(text = "· $m", style = MaterialTheme.typography.bodySmall)
        }
    }
}
