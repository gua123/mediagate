package io.github.gua123.mediagate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.R

/**
 * **挑文件夹加进缓存队列**（2026-10-15 用户要求：「将添加文件夹进队列和清理缓存界面放到一块」）。
 *
 * 与浏览页长按的分工：长按是"我正好在看着这个文件夹"；这一页是"我要管理缓存，顺手把某个文件夹排上队"——
 * 所以入口做在缓存明细页里，跟队列、已缓存列表、清空放在一起。
 *
 * 零 IO：目录由 :app 传进来（列目录是后端的活）。
 */
object CacheFolderPickerRoutes {

    /** 不需要参数（进来就是根目录，自己往下走）。 */
    const val ROUTE = "cache-folder-picker"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheFolderPickerScreen(
    /** 当前所在目录（相对根，空串＝根）。 */
    path: String,
    /** 子目录：路径 → 名字。 */
    folders: List<Pair<String, String>>,
    loading: Boolean,
    error: String?,
    onEnter: (String) -> Unit,
    /** 把某个子文件夹（含子目录）加进队列。 */
    onPickFolder: (path: String, name: String) -> Unit,
    /** 把当前所在文件夹（含子目录）加进队列。 */
    onPickCurrent: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_picker_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.cache_detail_back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.cache_picker_current), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = path.ifEmpty { stringResource(R.string.cache_picker_root) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(R.string.cache_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onPickCurrent) {
                        Text(stringResource(R.string.cache_picker_pick_current))
                    }
                }
            }

            when {
                loading -> Text(
                    text = stringResource(R.string.cache_picker_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                folders.isEmpty() -> Text(
                    text = stringResource(R.string.cache_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders, key = { it.first }) { (folderPath, name) ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onEnter(folderPath) }
                                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                                ) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = stringResource(R.string.cache_picker_tap_to_enter),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onPickFolder(folderPath, name) }) {
                                    Text(stringResource(R.string.cache_picker_add))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
