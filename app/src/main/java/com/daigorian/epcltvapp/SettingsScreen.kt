@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.daigorian.epcltvapp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private data class TextEditState(
    val prefKey: String,
    val title: String,
    val currentValue: String,
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    val keyIpAddr    = context.getString(R.string.pref_key_ip_addr)
    val keyPortNum   = context.getString(R.string.pref_key_port_num)
    val keyPlayer    = context.getString(R.string.pref_key_player)
    val keyNumHist   = context.getString(R.string.pref_key_num_of_history)
    val keyUseCustom = context.getString(R.string.pref_key_use_custom_base_url)
    val keyCustomUrl = context.getString(R.string.pref_key_custom_base_url)

    var ipAddress by remember { mutableStateOf(prefs.getString(keyIpAddr,    "192.168.0.0")                    ?: "192.168.0.0") }
    var portNumber by remember { mutableStateOf(prefs.getString(keyPortNum,   "8888")                            ?: "8888") }
    var player     by remember { mutableStateOf(prefs.getString(keyPlayer,    "")                                ?: "") }
    var numHist    by remember { mutableStateOf(prefs.getString(keyNumHist,   "3")                               ?: "3") }
    var useCustom  by remember { mutableStateOf(prefs.getBoolean(keyUseCustom, false)) }
    var customUrl  by remember { mutableStateOf(prefs.getString(keyCustomUrl, "http://192.168.0.0:8888/api/")   ?: "http://192.168.0.0:8888/api/") }

    var textEdit          by remember { mutableStateOf<TextEditState?>(null) }
    var showPlayerDialog  by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    val playerLabels  = context.resources.getStringArray(R.array.pref_options_movie_player_label)
    val playerValues  = context.resources.getStringArray(R.array.pref_options_movie_player_val)
    val historyLabels = context.resources.getStringArray(R.array.pref_options_num_of_history_label)
    val historyValues = context.resources.getStringArray(R.array.pref_options_num_of_history_val)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 32.dp)) {
            Text(
                text = context.getString(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!useCustom) {
                    item {
                        ListItem(
                            selected = false,
                            onClick = {
                                textEdit = TextEditState(
                                    keyIpAddr,
                                    context.getString(R.string.ip_addr_of_epgstation),
                                    ipAddress
                                )
                            },
                            headlineContent = { Text(context.getString(R.string.ip_addr_of_epgstation)) },
                            supportingContent = { Text(ipAddress) }
                        )
                    }
                    item {
                        ListItem(
                            selected = false,
                            onClick = {
                                textEdit = TextEditState(
                                    keyPortNum,
                                    context.getString(R.string.port_number_of_epgstation),
                                    portNumber
                                )
                            },
                            headlineContent = { Text(context.getString(R.string.port_number_of_epgstation)) },
                            supportingContent = { Text(portNumber) }
                        )
                    }
                }
                item {
                    val playerLabel = playerValues.indexOf(player).let {
                        if (it >= 0) playerLabels[it] else player
                    }
                    ListItem(
                        selected = false,
                        onClick = { showPlayerDialog = true },
                        headlineContent = { Text(context.getString(R.string.movie_player)) },
                        supportingContent = { Text(playerLabel) }
                    )
                }
                item {
                    val histLabel = historyValues.indexOf(numHist).let {
                        if (it >= 0) historyLabels[it] else numHist
                    }
                    ListItem(
                        selected = false,
                        onClick = { showHistoryDialog = true },
                        headlineContent = { Text(context.getString(R.string.num_of_history)) },
                        supportingContent = { Text(histLabel) }
                    )
                }
                item {
                    ListItem(
                        selected = false,
                        onClick = {
                            val next = !useCustom
                            useCustom = next
                            prefs.edit().putBoolean(keyUseCustom, next).apply()
                        },
                        headlineContent = { Text(context.getString(R.string.use_custom_base_url)) },
                        trailingContent = { Switch(checked = useCustom, onCheckedChange = null) }
                    )
                }
                if (useCustom) {
                    item {
                        ListItem(
                            selected = false,
                            onClick = {
                                textEdit = TextEditState(
                                    keyCustomUrl,
                                    context.getString(R.string.customize_base_url),
                                    customUrl
                                )
                            },
                            headlineContent = { Text(context.getString(R.string.customize_base_url)) },
                            supportingContent = { Text(customUrl) }
                        )
                    }
                }
            }
        }
    }

    // テキスト入力ダイアログ (IPアドレス / ポート番号 / カスタムURL)
    textEdit?.let { state ->
        var draft by remember(state.prefKey) { mutableStateOf(state.currentValue) }
        AlertDialog(
            onDismissRequest = { textEdit = null },
            title = { androidx.compose.material3.Text(state.title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = when (state.prefKey) {
                        keyIpAddr -> if (draft.matches(Regex(SettingsPrefs.IP_REGEX_PATTERN))) {
                            ipAddress = draft; true
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.not_a_valid_ipv4_addr, draft),
                                Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                        keyPortNum -> if (draft.matches(Regex(SettingsPrefs.PORT_REGEX_PATTERN))) {
                            portNumber = draft; true
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.not_a_valid_port_num, draft),
                                Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                        else -> { customUrl = draft; true }
                    }
                    if (ok) {
                        prefs.edit().putString(state.prefKey, draft).apply()
                        textEdit = null
                    }
                }) { androidx.compose.material3.Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { textEdit = null }) {
                    androidx.compose.material3.Text(context.getString(R.string.cancel))
                }
            }
        )
    }

    // プレーヤー選択ダイアログ
    if (showPlayerDialog) {
        AlertDialog(
            onDismissRequest = { showPlayerDialog = false },
            title = { androidx.compose.material3.Text(context.getString(R.string.movie_player)) },
            text = {
                LazyColumn {
                    itemsIndexed(playerLabels.toList()) { i, label ->
                        TextButton(
                            onClick = {
                                player = playerValues[i]
                                prefs.edit().putString(keyPlayer, playerValues[i]).apply()
                                showPlayerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { androidx.compose.material3.Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlayerDialog = false }) {
                    androidx.compose.material3.Text(context.getString(R.string.cancel))
                }
            }
        )
    }

    // 履歴件数選択ダイアログ
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { androidx.compose.material3.Text(context.getString(R.string.num_of_history)) },
            text = {
                LazyColumn {
                    itemsIndexed(historyLabels.toList()) { i, label ->
                        TextButton(
                            onClick = {
                                numHist = historyValues[i]
                                prefs.edit().putString(keyNumHist, historyValues[i]).apply()
                                showHistoryDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { androidx.compose.material3.Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    androidx.compose.material3.Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}
