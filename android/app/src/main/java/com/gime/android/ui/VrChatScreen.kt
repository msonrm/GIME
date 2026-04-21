package com.gime.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gime.android.osc.CustomOscValueType
import com.gime.android.osc.OscReceiver
import com.gime.android.osc.OscSender
import com.gime.android.osc.VrChatOscSettings
import kotlinx.coroutines.launch

/**
 * VRChat OSC 連携の設定画面。
 *
 * - 有効化トグル + 送信先 IP/Port 設定
 * - テスト送信 (設定した送信先 / loopback の両方)
 * - デバッグ受信ログ表示 (Pixel 10 単独で送受信確認可能)
 */
@Composable
fun VrChatScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VrChatOscSettings(context) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var host by remember { mutableStateOf(settings.host) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var commitOnly by remember { mutableStateOf(settings.commitOnlyMode) }
    var typingIndicator by remember { mutableStateOf(settings.typingIndicatorEnabled) }
    var autoRelease by remember { mutableStateOf(settings.autoReleaseAfterSend) }
    var customTypingEnabled by remember { mutableStateOf(settings.customTypingEnabled) }
    var customTypingAddress by remember { mutableStateOf(settings.customTypingAddress) }
    var customTypingValueType by remember { mutableStateOf(settings.customTypingValueType) }
    var customTypingStart by remember { mutableStateOf(settings.customTypingStartValue) }
    var customTypingEnd by remember { mutableStateOf(settings.customTypingEndValue) }
    var receiverEnabled by remember { mutableStateOf(settings.receiverEnabled) }
    var receiverPortText by remember { mutableStateOf(settings.receiverPort.toString()) }
    val logLines = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // 受信機: receiverEnabled の状態に応じて起動/停止
    var receiver by remember { mutableStateOf<OscReceiver?>(null) }
    LaunchedEffect(receiverEnabled, receiverPortText) {
        receiver?.destroy()
        receiver = null
        val port = receiverPortText.toIntOrNull()
        if (receiverEnabled && port != null && port in 1..65535) {
            val r = OscReceiver(port)
            receiver = r
            launch {
                r.messages.collect { ev ->
                    val line = when (ev) {
                        is OscReceiver.Event.Received ->
                            "← ${ev.message} (from ${ev.fromHost})"
                        is OscReceiver.Event.Error -> "! ${ev.reason}"
                    }
                    logLines.add(0, line)
                    if (logLines.size > 50) logLines.removeAt(logLines.lastIndex)
                }
            }
            r.start()
        }
    }
    DisposableEffect(Unit) {
        onDispose { receiver?.destroy() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VRChat OSC 連携", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("閉じる") }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 説明
        Text(
            "VRChat Mobile や PC VRChat の chatbox に OSC 経由で文字を送信します。\n" +
                "通信は指定した送信先だけで、第三者サーバーには一切流れません。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 有効化
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VRChat OSC モード", fontSize = 15.sp)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.enabled = it
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 送信先
        Text("送信先", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        LabeledField(
            label = "IP",
            value = host,
            onChange = {
                host = it
                settings.host = it
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        LabeledField(
            label = "Port",
            value = portText,
            onChange = {
                portText = it.filter(Char::isDigit)
                val p = portText.toIntOrNull()
                if (p != null && p in 1..65535) settings.port = p
            },
        )
        if (!host.startsWith("127.") && !host.startsWith("10.") &&
            !host.startsWith("192.168.") && host.isNotBlank()
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "⚠ 外部 IP を指定しています。意図した送信先か確認してください",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 確定時のみ送信（VRChat Mobile 向けワークアラウンド）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("確定時のみ送信", fontSize = 15.sp)
                Text(
                    "下書き（typing indicator）を送らず、LS 確定時だけ送信します。\n" +
                        "VRChat Mobile で chatbox 入力 UI が開いてしまう場合に ON にしてください。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = commitOnly,
                onCheckedChange = {
                    commitOnly = it
                    settings.commitOnlyMode = it
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // typing indicator（/chatbox/typing）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("タイピングインジケータ", fontSize = 15.sp)
                Text(
                    "composing 中に /chatbox/typing を送り、相手側でアバター頭上に\n" +
                        "3 点の typing インジケータを表示します。\n" +
                        "commitOnly と独立。VRChat Mobile で chatbox UI が開くなら OFF に。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = typingIndicator,
                onCheckedChange = {
                    typingIndicator = it
                    settings.typingIndicatorEnabled = it
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 送信後のフォーカス自動解放（バブル限定）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("送信後にフォーカスを戻す", fontSize = 15.sp)
                Text(
                    "バブル表示で LS 送信したあと、自動でバブルを非アクティブ化し、\n" +
                        "ゲームパッド入力を下の VRChat に戻します。\n" +
                        "連続して話したい場合は OFF にしてください。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoRelease,
                onCheckedChange = {
                    autoRelease = it
                    settings.autoReleaseAfterSend = it
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // 入力中アクション（typing 開始/終了で任意の avatar parameter を叩く）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("入力中アクションを送信", fontSize = 15.sp)
                Text(
                    "composing 開始時に「開始時の値」、終了時に「終了時の値」を\n" +
                        "指定したアドレスへ送ります。アバター側に対応パラメータの\n" +
                        "アニメーションが組まれている必要があります。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = customTypingEnabled,
                onCheckedChange = {
                    customTypingEnabled = it
                    settings.customTypingEnabled = it
                },
            )
        }

        if (customTypingEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            LabeledField(
                label = "アドレス",
                value = customTypingAddress,
                onChange = {
                    customTypingAddress = it
                    settings.customTypingAddress = it
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("値の型", fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CustomOscValueType.values().forEach { t ->
                        FilterChip(
                            selected = customTypingValueType == t,
                            onClick = {
                                customTypingValueType = t
                                settings.customTypingValueType = t
                            },
                            label = { Text(t.raw) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LabeledField(
                label = "開始時の値",
                value = customTypingStart,
                onChange = {
                    customTypingStart = it
                    settings.customTypingStartValue = it
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            LabeledField(
                label = "終了時の値",
                value = customTypingEnd,
                onChange = {
                    customTypingEnd = it
                    settings.customTypingEndValue = it
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    customTypingAddress = "/avatar/parameters/VRCEmote"
                    customTypingValueType = CustomOscValueType.INT
                    customTypingStart = "7"
                    customTypingEnd = "0"
                    settings.customTypingAddress = customTypingAddress
                    settings.customTypingValueType = customTypingValueType
                    settings.customTypingStartValue = customTypingStart
                    settings.customTypingEndValue = customTypingEnd
                },
            ) { Text("プリセット: VRCEmote=7 (sadness)") }

            if (settings.resolvedCustomTypingMessages() == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "⚠ アドレスまたは値を解釈できません（アドレスは / で始まる必要あり、値は型に合わせて int/float/bool で記述）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // テスト送信ボタン
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val p = portText.toIntOrNull() ?: VrChatOscSettings.DEFAULT_PORT
                        val h = host.ifBlank { VrChatOscSettings.DEFAULT_HOST }
                        status = try {
                            val s = OscSender(h, p)
                            s.send("/test/ping", "hello from GIME", true, false)
                            s.close()
                            "送信: /test/ping → $h:$p"
                        } catch (t: Throwable) {
                            "送信失敗: ${t.message}"
                        }
                    }
                },
                enabled = portText.toIntOrNull() != null,
            ) { Text("送信先へテスト送信") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val rp = receiverPortText.toIntOrNull()
                            ?: VrChatOscSettings.DEFAULT_RECEIVER_PORT
                        status = try {
                            val s = OscSender("127.0.0.1", rp)
                            s.send("/test/loopback", "self-test", 42, 3.14f, true)
                            s.close()
                            "送信: /test/loopback → 127.0.0.1:$rp"
                        } catch (t: Throwable) {
                            "送信失敗: ${t.message}"
                        }
                    }
                },
                enabled = receiverPortText.toIntOrNull() != null,
            ) { Text("自受信へ送信 (loopback)") }
        }
        status?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // 受信
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("デバッグ受信", fontSize = 15.sp)
            Switch(
                checked = receiverEnabled,
                onCheckedChange = {
                    receiverEnabled = it
                    settings.receiverEnabled = it
                },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LabeledField(
            label = "Listen Port",
            value = receiverPortText,
            onChange = {
                receiverPortText = it.filter(Char::isDigit)
                val p = receiverPortText.toIntOrNull()
                if (p != null && p in 1..65535) settings.receiverPort = p
            },
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (logLines.isEmpty()) {
            Text(
                if (receiverEnabled) "受信待機中..." else "受信を有効化すると届いた OSC メッセージがここに表示されます",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                logLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.startsWith("!")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { logLines.clear() }) { Text("ログクリア") }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
