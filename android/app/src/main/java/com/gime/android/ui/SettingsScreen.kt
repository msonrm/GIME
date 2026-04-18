package com.gime.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gime.android.engine.GamepadInputMode
import com.gime.android.input.GamepadInputManager
import com.gime.android.settings.GimeModeSettings

/**
 * GIME 設定画面。
 *
 * - Start ボタンの言語サイクル: 取捨選択トグル + 並び順の上下入替
 * - アプリ情報（バージョン・著作権）
 * - オープンソースライセンス（展開式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inputManager: GamepadInputManager,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { GimeModeSettings(context) }

    // Store が変わるたびに InputManager の enabledModes に push
    LaunchedEffect(store.enabledModes) {
        inputManager.updateEnabledModes(store.enabledModes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("閉じる") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // --- Start ボタンの言語切替 ---
            SectionHeader("Start ボタンの言語切替")
            SectionCard {
                GamepadInputMode.entries.forEach { mode ->
                    val isEnabled = store.enabledModes.contains(mode)
                    val isLastEnabled = isEnabled && store.enabledModes.size <= 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            mode.label,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = isEnabled,
                            enabled = !isLastEnabled,
                            onCheckedChange = { store.setEnabled(mode, it) },
                        )
                    }
                }
                Text(
                    "少なくとも 1 つのモードは常に有効である必要があります。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- 切替順序 ---
            SectionHeader("切替順序")
            SectionCard {
                val enabled = store.enabledModes
                enabled.forEachIndexed { i, mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.widthIn(min = 24.dp),
                        )
                        Text(
                            mode.label,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = { store.moveUp(i) },
                            enabled = i > 0,
                        ) {
                            Text("▲", color = if (i > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { store.moveDown(i) },
                            enabled = i < enabled.size - 1,
                        ) {
                            Text("▼", color = if (i < enabled.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Start ボタンは上から順にサイクルします。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- このアプリについて ---
            SectionHeader("このアプリについて")
            SectionCard {
                val versionLabel = remember(context) {
                    runCatching {
                        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                        @Suppress("DEPRECATION")
                        val code = pkg.longVersionCode
                        "v${pkg.versionName ?: "—"} ($code)"
                    }.getOrDefault("v—")
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("GIME", fontWeight = FontWeight.SemiBold)
                    Text(
                        versionLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "© 2024-2026 Masao Narumi",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- オープンソースライセンス ---
            SectionHeader("オープンソースライセンス")
            SectionCard {
                LicenseEntry(
                    title = "KazumaProject / JapaneseKeyboard",
                    subtitle = "MIT License Copyright (c) 2024 Kazuma Naka",
                    body = "日本語かな漢字変換エンジン本体（LOUDS trie + N-gram 言語モデル）および辞書データを vendor して利用しています。著作権表示・ライセンス全文は上流リポジトリの LICENSE を参照してください。",
                    licenseText = MIT_LICENSE_KAZUMA,
                )
                HorizontalDivider()
                LicenseEntry(
                    title = "CC-CEDICT",
                    subtitle = "Creative Commons Attribution-ShareAlike 4.0 International",
                    body = "中国語簡体字辞書データの語彙・ピンイン情報に使用しています。",
                    licenseText = null,
                )
                HorizontalDivider()
                LicenseEntry(
                    title = "libchewing",
                    subtitle = "LGPL v2.1 — libchewing contributors",
                    body = "中国語繁體字辞書データの語彙・注音情報に使用しています。",
                    licenseText = null,
                )
                HorizontalDivider()
                LicenseEntry(
                    title = "Jetpack Compose / AndroidX / Kotlin",
                    subtitle = "Apache License 2.0",
                    body = "UI・ランタイム・標準ライブラリ。Google / JetBrains。",
                    licenseText = null,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun LicenseEntry(
    title: String,
    subtitle: String,
    body: String,
    licenseText: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▼" else "▶",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (licenseText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = licenseText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val MIT_LICENSE_KAZUMA = """
    MIT License
    Copyright (c) 2024 Kazuma Naka

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
""".trimIndent()
