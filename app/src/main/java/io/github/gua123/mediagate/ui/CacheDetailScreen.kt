package io.github.gua123.mediagate.ui

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
import io.github.gua123.mediagate.app.cacheEntryLine
import io.github.gua123.mediagate.app.cacheMegabytes
import io.github.gua123.mediagate.app.cacheSummaryLine
import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend

/**
 * **缓存明细**（2026-10-15 用户要求：「关于缓存部分，是否可以增加一个可视化界面，告诉我哪些缓存过，哪些没有」）。
 *
 * 回答两个问题，一行一个文件：
 * - **哪些缓存过**：文件名（老版本留下的无名缓存如实标注）+ 路径（小字，便于对上号）；
 * - **缓存到什么程度**：已缓存多少 MB、几段、占该文件的比例（总大小未知时显示「总大小未知」）。
 *
 * 另外给两条出口：**单条删除**（只清这个文件的段）与**清空全部**。整页零 IO——数据由 :app 传进来。
 */
object CacheDetailRoutes {

    /** 这一页不需要参数（缓存是全局的）。 */
    const val ROUTE = "cache-detail"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheDetailScreen(
    entries: List<SegmentedCacheBackend.CachedFileEntry>,
    cacheLimitBytes: Long,
    onRemove: (SegmentedCacheBackend.CachedFileEntry) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_detail_title)) },
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
                    Text(stringResource(R.string.cache_detail_summary), style = MaterialTheme.typography.titleMedium)
                    Text(cacheSummaryLine(entries), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(
                            R.string.cache_detail_limit,
                            cacheMegabytes(cacheLimitBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = onClearAll) { Text(stringResource(R.string.cache_detail_clear_all)) }
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.cache_detail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.dirName }) { entry ->
                        CacheEntryRow(entry = entry, onRemove = { onRemove(entry) })
                    }
                }
            }
        }
    }
}

/** 一条缓存记录：名字 + 程度 + 路径 + 删除。 */
@Composable
private fun CacheEntryRow(
    entry: SegmentedCacheBackend.CachedFileEntry,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) { Text(stringResource(R.string.cache_detail_remove)) }
            }
            Text(cacheEntryLine(entry), style = MaterialTheme.typography.bodySmall)
            entry.path?.let { path ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
