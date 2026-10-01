package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.app.TrustedHostKey

/**
 * 设置页「已信任的 SFTP 主机密钥」区块（**R2 / plan 4.9**）。
 *
 * TOFU 落库之后必须给出这条复原路径：服务器真的换了密钥时，拒绝连接是**正确**行为，
 * 但用户得有地方"我看过了，确实是我换的"，否则只能清 App 数据。
 */
@Composable
fun TrustedHostKeysSection(
    keys: List<TrustedHostKey>,
    onForget: (TrustedHostKey) -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.trusted_host_keys_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.trusted_host_keys_subtitle), style = MaterialTheme.typography.bodySmall)

            if (keys.isEmpty()) {
                Text(stringResource(R.string.trusted_host_keys_empty), style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            keys.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(key.endpoint, style = MaterialTheme.typography.bodyMedium)
                        Text(key.keyType, style = MaterialTheme.typography.bodySmall)
                        Text(key.fingerprint, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onForget(key) }) {
                        Text(stringResource(R.string.trusted_host_keys_forget))
                    }
                }
            }

            TextButton(onClick = onForgetAll) {
                Text(stringResource(R.string.trusted_host_keys_forget_all))
            }
        }
    }
}
