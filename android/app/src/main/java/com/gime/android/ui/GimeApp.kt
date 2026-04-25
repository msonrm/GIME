package com.gime.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gime.android.bubble.BubbleService
import com.gime.android.engine.PinyinEngine
import com.gime.android.input.GamepadInputManager
import com.gime.android.osc.VrChatOscSettings
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository

/// GIME メイン画面（エディタ + ゲームパッドビジュアライザ）
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun GimeApp(
    inputManager: GamepadInputManager,
    pinyinEngine: PinyinEngine,
    userDict: UserDictionaryRepository? = null,
    learnRepo: LearnRepository? = null,
) {
    val context = LocalContext.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var showDictionary by remember { mutableStateOf(false) }
    var showVrChat by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // ビジュアライザ右上の VRChat OSC バッジ用。
    // VrChatScreen から戻ってきたタイミングで再読込（シンプルな SharedPreferences 反映）
    var vrChatEnabled by remember { mutableStateOf(VrChatOscSettings(context).enabled) }
    LaunchedEffect(showVrChat) {
        if (!showVrChat) {
            vrChatEnabled = VrChatOscSettings(context).enabled
        }
    }

    if (showDictionary && userDict != null && learnRepo != null) {
        DictionaryScreen(
            userDict = userDict,
            learnRepo = learnRepo,
            onClose = { showDictionary = false },
        )
        return
    }
    if (showVrChat) {
        VrChatScreen(onClose = { showVrChat = false })
        return
    }
    if (showSettings) {
        SettingsScreen(
            inputManager = inputManager,
            onClose = { showSettings = false },
        )
        return
    }

    // コールバックを設定
    LaunchedEffect(Unit) {
        inputManager.onDirectInsert = { text, replaceCount ->
            val currentText = textFieldValue.text
            val cursor = textFieldValue.selection.start
            if (replaceCount > 0 && cursor >= replaceCount) {
                // 直前の文字を置換
                val before = currentText.substring(0, cursor - replaceCount)
                val after = currentText.substring(cursor)
                val newText = before + text + after
                val newCursor = before.length + text.length
                textFieldValue = TextFieldValue(newText, TextRange(newCursor))
            } else {
                // 追加挿入
                val before = currentText.substring(0, cursor)
                val after = currentText.substring(cursor)
                val newText = before + text + after
                val newCursor = cursor + text.length
                textFieldValue = TextFieldValue(newText, TextRange(newCursor))
            }
        }

        inputManager.onDeleteBackward = {
            val currentText = textFieldValue.text
            val cursor = textFieldValue.selection.start
            if (cursor > 0) {
                val before = currentText.substring(0, cursor - 1)
                val after = currentText.substring(cursor)
                textFieldValue = TextFieldValue(before + after, TextRange(cursor - 1))
            }
        }

        inputManager.onCursorMove = { offset ->
            val cursor = textFieldValue.selection.start
            val newCursor = (cursor + offset).coerceIn(0, textFieldValue.text.length)
            textFieldValue = textFieldValue.copy(selection = TextRange(newCursor))
        }

        inputManager.onCursorMoveVertical = { _ ->
            // Phase 1 では上下カーソル未実装
        }

        inputManager.onGetLastCharacter = {
            val cursor = textFieldValue.selection.start
            if (cursor > 0) textFieldValue.text[cursor - 1] else null
        }

        inputManager.onConfirmOrNewline = {
            val currentText = textFieldValue.text
            val cursor = textFieldValue.selection.start
            val before = currentText.substring(0, cursor)
            val after = currentText.substring(cursor)
            val newText = before + "\n" + after
            textFieldValue = TextFieldValue(newText, TextRange(cursor + 1))
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GIME", style = MaterialTheme.typography.titleLarge)
                        if (inputManager.isConnected) {
                            Badge(
                                text = inputManager.gamepadName ?: "Gamepad",
                                color = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            )
                        } else {
                            Badge(
                                text = "未接続",
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // アクションボタン群（横並び。画面幅に収まらない場合は折返し）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("IME設定") }
                TextButton(onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.showInputMethodPicker()
                }) { Text("IME切替") }
                TextButton(onClick = { showVrChat = true }) { Text("VRChat") }
                TextButton(onClick = {
                    if (BubbleService.hasOverlayPermission(context)) {
                        BubbleService.start(context)
                        // バブル単体でゲームパッド入力を受け取れるようアクティビティは閉じる。
                        (context as? android.app.Activity)?.moveTaskToBack(true)
                    } else {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + context.packageName),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("バブル") }
                if (userDict != null && learnRepo != null) {
                    TextButton(onClick = { showDictionary = true }) { Text("辞書") }
                }
                TextButton(onClick = { showSettings = true }) { Text("設定") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // テキストエディタ
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                )

                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = "ゲームパッドで入力してください...",
                        style = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ゲームパッドビジュアライザ
            GamepadVisualizer(
                inputManager = inputManager,
                vrChatEnabled = vrChatEnabled,
                onVrChatBadgeClick = { showVrChat = true },
                chatboxLength = textFieldValue.text.length,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// MARK: - ビジュアライザ

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GamepadVisualizer(
    inputManager: GamepadInputManager,
    vrChatEnabled: Boolean = false,
    onVrChatBadgeClick: () -> Unit = {},
    /// compact モードでは D-pad 配列情報を省き、composing / 候補行のみ表示する。
    /// バブル表示で慣れたユーザー向けの省スペースモード。
    compact: Boolean = false,
    /// VRChat OSC アクティブバッジを表示するか。
    /// バブル（VRChat 専用運用）では冗長なので false。MainActivity / IME は true。
    showVrChatBadge: Boolean = true,
    /// chatbox に送る下書きの長さ。VrChatOscOutput.MAX_CHATBOX_LEN と突き合わせて
    /// `N/144` カウンターをバッジ横に出す。0 のときは非表示。バブル側は
    /// タイトルバーで別途カウンターを出すので showVrChatBadge=false の際は隠す。
    chatboxLength: Int = 0,
) {
    // compact モードでは外枠の背景とパディングを省いて省スペース化。
    // 内部の composing/候補ブロックは個別に背景を持っているので見栄えは崩れない。
    val outerModifier = if (compact) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp)
    }
    Column(modifier = outerModifier) {
        // VRChat OSC 有効時のバッジ（iPad 版と対称。タップで VRChat 設定画面を開く）
        if (vrChatEnabled && showVrChatBadge) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VrChatActiveBadge(onClick = onVrChatBadgeClick)
                if (chatboxLength > 0) {
                    ChatboxLengthCounter(length = chatboxLength)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // かな漢字変換エンジンのエラー（診断用。logcat 代替）
        val jpError = inputManager.japaneseConverter?.lastError
        if (jpError != null) {
            Text(
                text = "Conv ERR: $jpError",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 候補 / composing 表示は別コンポーザブルに切り出し、IME でも再利用する。
        // ヒント行は compact モードでは出さない。
        CandidateOverlay(
            inputManager = inputManager,
            showHints = !compact,
        )

        // 未接続時のみ注意表示（接続中は何も表示しない）
        if (!inputManager.isConnected) {
            Text(
                text = "コントローラーを接続してください",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
        }

        // 日本語の変換中のみビジュアライザを隠す（候補に集中できるように）。
        // 中国語は即時候補表示でも邪魔にならないよう常に表示する。
        // compact モードでは D-pad を一切表示しない。
        if (!compact && !inputManager.isConverting) {
            DpadDisplay(inputManager = inputManager)
        }
    }
}

/// 候補 / composing プレビュー専用のコンポーザブル。
///
/// GamepadVisualizer の候補表示部分を切り出して、IME compact モードの
/// 透過オーバーレイレイヤーからも再利用できるようにしたもの。外枠は持たず、
/// 個々の候補カードだけが surfaceContainerHigh 背景を持つので、親側の
/// 背景が透明でも自然に見える。
///
/// - [showHints] false で「LS↓: 変換 / ...」ヒント行を非表示にする（compact 用）。
/// - 何も表示すべき状態が無ければ縦幅 0 で畳む（オーバーレイが消える）。
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CandidateOverlay(
    inputManager: GamepadInputManager,
    showHints: Boolean = true,
) {
    val hasPinyin = inputManager.pinyinCandidates.isNotEmpty()
    val hasJapanese = inputManager.hiraganaBuffer.isNotEmpty() || inputManager.isConverting
    if (!hasPinyin && !hasJapanese) return

    Column(modifier = Modifier.fillMaxWidth()) {
        // 中国語候補表示（該当時のみ）— 日本語モードと同じレイアウト（FlowRow + ページ表示）
        if (hasPinyin) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (inputManager.pinyinBuffer.isNotEmpty()) {
                    val displayBuffer = if (inputManager.zhuyinDisplayBuffer.isNotEmpty()) {
                        inputManager.zhuyinDisplayBuffer
                    } else {
                        inputManager.pinyinBuffer
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "拼音: ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = displayBuffer,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    inputManager.visiblePinyinCandidates.forEachIndexed { i, candidate ->
                        val isSelected = i == inputManager.pinyinSelectedIndexInWindow
                        Text(
                            text = "${i + 1}.${candidate.word}",
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = if (isSelected) Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                            else Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "${inputManager.pinyinSelectedIndex + 1} / ${inputManager.pinyinCandidates.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (hasJapanese) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (inputManager.isConverting && inputManager.bunsetsuReadings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "変換中: ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Row {
                            inputManager.bunsetsuReadings.forEachIndexed { i, r ->
                                val isFocused = i == inputManager.focusedBunsetsuIndex
                                if (i > 0) Text("|", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp)
                                Text(
                                    text = r,
                                    color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp,
                                    modifier = if (isFocused) Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(3.dp)
                                    ) else Modifier,
                                )
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (inputManager.isConverting) "変換中: " else "未変換: ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = inputManager.hiraganaBuffer,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                        )
                    }
                }
                if (inputManager.japaneseCandidates.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        inputManager.visibleJapaneseCandidates.forEachIndexed { i, cand ->
                            val isSelected = i == inputManager.japaneseSelectedIndexInWindow
                            Text(
                                text = "${i + 1}.${cand.surface}",
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                modifier = if (isSelected) Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                else Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "${inputManager.selectedJapaneseCandidateIndex + 1} / ${inputManager.japaneseCandidates.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (showHints && !inputManager.isConverting) {
                    Text(
                        text = "LS↓: 変換 / LSクリック: 確定 / RSクリック: 取消",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// D-pad のどの方向が activeRow に対応するか
private fun dpadDirectionForRow(row: Int): Int = when (row) {
    1, 6 -> 1  // left
    2, 7 -> 2  // up
    3, 8 -> 3  // right
    4, 9 -> 4  // down
    else -> 0  // neutral (center)
}

@Composable
fun DpadDisplay(inputManager: GamepadInputManager) {
    val mode = inputManager.currentMode
    val activeRow = inputManager.activeRow
    val isLB = inputManager.activeLayer == GamepadInputManager.ActiveLayer.LB
    val dir = dpadDirectionForRow(activeRow)
    val englishShift = inputManager.englishCapsLock || inputManager.englishSmartCaps || inputManager.englishShiftNext
    val isRT = inputManager.btnRT

    // 現在アクティブな D-pad 方向のフェイスボタン文字（RB ラベルや韓国語母音の動的表示に使用）
    val activeRowFaceChars: Array<String> = getFaceChars(mode, activeRow, englishShift, isRT)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 本体: 左端ショルダー | D-pad | 右端ショルダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左端: LT / LB
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShoulderChip(
                    label = ltLabel(mode, inputManager),
                    pressed = inputManager.btnLT,
                )
                ShoulderChip(
                    label = lbLabel(mode, isLB, inputManager),
                    pressed = inputManager.btnLB,
                )
            }

            // 中央: D-pad クラスタ + (韓国語・Devanagari のみ) フェイスボタンを横並び
            when (mode) {
                com.gime.android.engine.GamepadInputMode.KOREAN -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DpadCluster(mode = mode, isLB = isLB, dir = dir, englishShift = englishShift)
                        KoreanFaceButtons(inputManager, vowels = activeRowFaceChars)
                    }
                }
                com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DevaDpadCluster(inputManager = inputManager, dir = dir)
                        DevaFaceButtons(inputManager = inputManager)
                    }
                }
                else -> {
                    DpadCluster(mode = mode, isLB = isLB, dir = dir, englishShift = englishShift)
                }
            }

            // 右端: RT / RB
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShoulderChip(
                    label = rtLabel(mode, inputManager),
                    pressed = inputManager.btnRT,
                )
                ShoulderChip(
                    label = rbLabel(mode, activeRowFaceChars, inputManager),
                    pressed = inputManager.btnRB,
                )
            }
        }

        // 右スティック方向のヒント（モード別）
        Spacer(modifier = Modifier.height(6.dp))
        RightStickHint(mode, inputManager)
    }
}

/// D-pad 5 セルを「フェイスボタン配置」で並べる（全モード統一）
@Composable
private fun DpadCluster(
    mode: com.gime.android.engine.GamepadInputMode,
    isLB: Boolean,
    dir: Int,
    englishShift: Boolean,
) {
    val offset = if (isLB) 5 else 0
    // 韓国語は単一子音のみ表示なので小さめ（フェイスボタンと同程度）
    val cellSize = if (mode == com.gime.android.engine.GamepadInputMode.KOREAN) 34.dp else 58.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ClusterCell(mode, 2 + offset, isActive = dir == 2, englishShift = englishShift, size = cellSize)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ClusterCell(mode, 1 + offset, isActive = dir == 1, englishShift = englishShift, size = cellSize)
            ClusterCell(mode, 0 + offset, isActive = dir == 0, englishShift = englishShift, size = cellSize)
            ClusterCell(mode, 3 + offset, isActive = dir == 3, englishShift = englishShift, size = cellSize)
        }
        ClusterCell(mode, 4 + offset, isActive = dir == 4, englishShift = englishShift, size = cellSize)
    }
}

/// 1 つの D-pad セル。内部に最大 5 文字を十字配置。Korean は単一中央表示。
@Composable
private fun ClusterCell(
    mode: com.gime.android.engine.GamepadInputMode,
    row: Int,
    isActive: Boolean,
    englishShift: Boolean,
    size: androidx.compose.ui.unit.Dp = 58.dp,
) {
    val chars = getCellChars(mode, row, englishShift)
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    // Korean は中央 1 文字のみなので余白表示は省略。他モードは十字配置
    val isKoreanSmall = mode == com.gime.android.engine.GamepadInputMode.KOREAN
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (isKoreanSmall) {
            // 中央 1 文字のみ
            if (chars.isNotEmpty() && chars[0].isNotEmpty()) {
                Text(chars[0], color = fg, fontSize = 14.sp)
            }
            return@Box
        }
        // 上 = index 2 (Y)
        if (chars.size > 2 && chars[2].isNotEmpty()) {
            Text(chars[2], color = fg, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
        }
        // 下 = index 4 (A)
        if (chars.size > 4 && chars[4].isNotEmpty()) {
            Text(chars[4], color = fg, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
        }
        // 左 = index 1 (X)
        if (chars.size > 1 && chars[1].isNotEmpty()) {
            Text(chars[1], color = fg, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
        }
        // 右 = index 3 (B)
        if (chars.size > 3 && chars[3].isNotEmpty()) {
            Text(chars[3], color = fg, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        }
        // 中央 = index 0 (RB またはニュートラル)
        if (chars.isNotEmpty() && chars[0].isNotEmpty()) {
            Text(chars[0], color = fg, fontSize = 13.sp)
        }
    }
}

/// D-pad セル内に表示する 5 文字（モード・行・英語 Shift を反映）。
/// index 0=中央(RB相当), 1=左(X), 2=上(Y), 3=右(B), 4=下(A)。
/// 韓国語は中央に単一子音のみ。
private fun getCellChars(
    mode: com.gime.android.engine.GamepadInputMode,
    row: Int,
    englishShift: Boolean,
): Array<String> {
    return when (mode) {
        com.gime.android.engine.GamepadInputMode.JAPANESE ->
            com.gime.android.engine.KANA_TABLE.getOrNull(row) ?: emptyArray()
        com.gime.android.engine.GamepadInputMode.ENGLISH -> {
            val r = com.gime.android.engine.ENGLISH_TABLE.getOrNull(row) ?: return emptyArray()
            if (englishShift) r.map { it.uppercase() }.toTypedArray() else r
        }
        com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED ->
            com.gime.android.engine.ENGLISH_TABLE.getOrNull(row) ?: emptyArray()
        com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL ->
            com.gime.android.engine.ZHUYIN_TABLE.getOrNull(row) ?: emptyArray()
        com.gime.android.engine.GamepadInputMode.KOREAN -> {
            val labels = if (row < 5) com.gime.android.engine.KOREAN_DPAD_LABELS_BASE
            else com.gime.android.engine.KOREAN_DPAD_LABELS_LB
            val idx = row % 5
            val single = when (idx) {
                0 -> labels.center; 1 -> labels.left; 2 -> labels.up
                3 -> labels.right; 4 -> labels.down; else -> ""
            }
            arrayOf(single, "", "", "", "")
        }
        com.gime.android.engine.GamepadInputMode.DEVANAGARI ->
            // DevaDpadCluster が直接描画するので、getCellChars は呼ばれない想定。
            // fallback として空配列。
            emptyArray()
    }
}

/// 現在アクティブな D-pad 方向でフェイスボタン (X/Y/A/B/RB) を押すと出る文字。
/// RB ラベルの動的表示や、韓国語の母音フェイスボタン表示に使う。
/// 韓国語は activeRow と無関係に vowels を返す（RT で shifted）。
private fun getFaceChars(
    mode: com.gime.android.engine.GamepadInputMode,
    activeRow: Int,
    englishShift: Boolean,
    rtPressed: Boolean,
): Array<String> {
    return when (mode) {
        com.gime.android.engine.GamepadInputMode.KOREAN ->
            if (rtPressed) com.gime.android.engine.KOREAN_VOWEL_CHARS_SHIFTED
            else com.gime.android.engine.KOREAN_VOWEL_CHARS_BASE
        com.gime.android.engine.GamepadInputMode.DEVANAGARI ->
            // Devanagari は DevaFaceButtons で独自描画。fallback（RB ラベル用）に
            // [center, X=e, Y=a, B=i, A=u] を返す。center は nukta（rbLabel が上書き）。
            arrayOf("", "ए", "अ", "इ", "उ")
        else -> getCellChars(mode, activeRow, englishShift)
    }
}

/// Devanagari 用 D-pad クラスタ。現在の varga (LS 方向) に応じて
/// 5 セル (中央=鼻音, 四方=stop) を表示。非 varga モード中は semivowel / sibilant を表示。
@Composable
private fun DevaDpadCluster(
    inputManager: GamepadInputManager,
    dir: Int,
) {
    val ltPressed = inputManager.btnLT
    val chars: Array<String> = if (inputManager.devaNonVargaActive) {
        com.gime.android.engine.devaNonVargaDisplayChars(ltPressed)
    } else {
        val varga = com.gime.android.engine.resolveDevaVarga(inputManager.devaLsDir)
        com.gime.android.engine.devaVargaDisplayChars(varga)
    }
    val cellSize = 34.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DevaClusterCell(chars.getOrElse(2) { "" }, isActive = dir == 2, size = cellSize)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DevaClusterCell(chars.getOrElse(1) { "" }, isActive = dir == 1, size = cellSize)
            DevaClusterCell(chars.getOrElse(0) { "" }, isActive = dir == 0, size = cellSize)
            DevaClusterCell(chars.getOrElse(3) { "" }, isActive = dir == 3, size = cellSize)
        }
        DevaClusterCell(chars.getOrElse(4) { "" }, isActive = dir == 4, size = cellSize)
    }
}

@Composable
private fun DevaClusterCell(
    char: String,
    isActive: Boolean,
    size: androidx.compose.ui.unit.Dp = 34.dp,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (char.isNotEmpty()) Text(char, color = fg, fontSize = 16.sp)
    }
}

/// Devanagari 用フェイスボタン（varnamala 時計回り: ↑a →i ↓u ←e）
/// LT 同時押しで A → ऋ にシフト（他の 3 つは変化なし）
@Composable
private fun DevaFaceButtons(inputManager: GamepadInputManager) {
    val lt = inputManager.btnLT
    val downChar = if (lt) "ऋ" else "उ"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaceButton("अ", inputManager.btnY)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FaceButton("ए", inputManager.btnX)
            FaceButton("", pressed = false, isCenter = true)
            FaceButton("इ", inputManager.btnB)
        }
        FaceButton(downChar, inputManager.btnA)
    }
}

/// 韓国語用: 母音を X/Y/A/B に配置。RT 押下で濃音/ㅣ 付き（shifted）へ動的切替。
@Composable
private fun KoreanFaceButtons(
    inputManager: GamepadInputManager,
    vowels: Array<String>,
) {
    val chars = if (vowels.size >= 5) vowels else com.gime.android.engine.KOREAN_VOWEL_CHARS_BASE
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaceButton(chars[2], inputManager.btnY)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FaceButton(chars[1], inputManager.btnX)
            FaceButton(chars[0], pressed = false, isCenter = true)
            FaceButton(chars[3], inputManager.btnB)
        }
        FaceButton(chars[4], inputManager.btnA)
    }
}

// iOS 版 GamepadVisualizerView.swift と同じラベル規則

/// LT ラベル: 英語は Shift 状態を 4 段階で表示、韓国語は자모モードを表示
private fun ltLabel(
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String = when (mode) {
    com.gime.android.engine.GamepadInputMode.JAPANESE -> "拗音"
    com.gime.android.engine.GamepadInputMode.ENGLISH -> when {
        m.englishCapsLock -> "CAPS"
        m.englishSmartCaps -> "Caps"
        m.englishShiftNext -> "Shift"
        else -> "shift"
    }
    com.gime.android.engine.GamepadInputMode.KOREAN -> when {
        m.koreanJamoLock -> "LOCK"      // 持続モード（長押しで toggle）
        m.koreanSmartJamo -> "자모"     // 一時モード（空白/句読点で解除）
        else -> "ㅇ"                     // 通常: 単押しで ㅇ받침
    }
    com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED,
    com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> ""
    com.gime.android.engine.GamepadInputMode.DEVANAGARI ->
        // varga モード: LT + A = ऋ / LT + RB = nukta
        // 非 varga モード: D-pad を semivowel/sibilant 間で切替
        if (m.devaNonVargaActive) "शष" else "ऋ़"
}

/// LB ラベル: 押下中は ●、そうでなければ別レイヤーの手がかりを表示
/// Devanagari は現 varga の鼻音文字を動的表示（LS latched 状態に追随）
private fun lbLabel(
    mode: com.gime.android.engine.GamepadInputMode,
    isLB: Boolean,
    m: GamepadInputManager,
): String {
    if (isLB) return "●"
    return when (mode) {
        com.gime.android.engine.GamepadInputMode.JAPANESE -> "は〜"
        com.gime.android.engine.GamepadInputMode.ENGLISH,
        com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED -> "pqrs〜"
        com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> "ㄗㄘㄙ〜"
        com.gime.android.engine.GamepadInputMode.KOREAN -> "ㅁ〜"
        com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
            val varga = com.gime.android.engine.resolveDevaVarga(m.devaLsDir)
            com.gime.android.engine.DEVA_VARGA_CONSONANTS[varga.index][4].toString()
        }
    }
}

/// RT ラベル: Devanagari は LT 併用時に visarga ः へ動的切替
private fun rtLabel(
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String = when (mode) {
    com.gime.android.engine.GamepadInputMode.JAPANESE -> "ん"
    com.gime.android.engine.GamepadInputMode.ENGLISH,
    com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED,
    com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> "0"
    com.gime.android.engine.GamepadInputMode.KOREAN -> "ㅑㅕ"
    com.gime.android.engine.GamepadInputMode.DEVANAGARI ->
        if (m.btnLT) "ः" else "्⇆"  // halant / LS+RT でカーソル / LT併用で visarga
}

/// RB ラベル: Devanagari は LT 無しで ओ / LT 併用で nukta ़。他モードは activeRow 流用
private fun rbLabel(
    mode: com.gime.android.engine.GamepadInputMode,
    activeRowFaceChars: Array<String>,
    m: GamepadInputManager,
): String = when (mode) {
    com.gime.android.engine.GamepadInputMode.DEVANAGARI ->
        if (m.btnLT) "़" else "ओ"
    else -> activeRowFaceChars.getOrElse(0) { "RB" }
}

/// 右スティック方向のヒント（iOS 版 GamepadVisualizerView.swift と同じ表記）
@Composable
private fun RightStickHint(
    mode: com.gime.android.engine.GamepadInputMode,
    inputManager: GamepadInputManager,
) {
    val upLabel: String
    val downLabel: String
    val rightLabel: String
    when (mode) {
        com.gime.android.engine.GamepadInputMode.JAPANESE -> {
            upLabel = "濁点"; downLabel = "、。␣"; rightLabel = "ー"
        }
        com.gime.android.engine.GamepadInputMode.ENGLISH -> {
            upLabel = "'"; downLabel = "␣.,"; rightLabel = "/"
        }
        com.gime.android.engine.GamepadInputMode.KOREAN -> {
            if (inputManager.koreanJamoLock || inputManager.koreanSmartJamo) {
                // 자모 모드: ↑=平激濃サイクル, →=連打（直前 jamo の繰り返し）
                upLabel = "ㄱㅋㄲ"; downLabel = "␣."; rightLabel = "연타"
            } else {
                upLabel = "ㅋㅌ"; downLabel = "␣."; rightLabel = "ㅘㅝ"
            }
        }
        com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED,
        com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> {
            upLabel = ""; downLabel = "，。␣"; rightLabel = "、"
        }
        com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
            upLabel = "ंँ"; downLabel = "␣।"; rightLabel = "ा"
        }
    }
    val leftLabel = "⌫"
    val hints = listOfNotNull(
        ("↑" to upLabel).takeIf { upLabel.isNotEmpty() },
        "↓" to downLabel,
        "←" to leftLabel,
        ("→" to rightLabel).takeIf { rightLabel.isNotEmpty() },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            text = "R:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        hints.forEach { (arrow, label) ->
            if (label.isEmpty()) return@forEach
            Text(
                text = "$arrow $label",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FaceButton(label: String, pressed: Boolean, isCenter: Boolean = false) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                when {
                    pressed -> MaterialTheme.colorScheme.primary
                    isCenter -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ShoulderChip(label: String, pressed: Boolean) {
    // 空ラベルのときは視覚的ノイズを避けるため極薄で表示
    val bg = when {
        pressed -> MaterialTheme.colorScheme.primary
        label.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .height(22.dp)
            .background(bg, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
        )
    }
}

/// 日本語モード用: D-pad 1 セル内にかな 5 つをフェイスボタン配置で表示
///   中央: KANA_TABLE[row][0] (A=あ段)
///   上:   KANA_TABLE[row][2] (Y=う段)
///   左:   KANA_TABLE[row][1] (X=い段)
///   右:   KANA_TABLE[row][3] (B=え段)
///   下:   KANA_TABLE[row][4] (A=お段)
/// （実装は ClusterCell に統合済み）

@Composable
fun DpadLabel(text: String, isActive: Boolean) {
    Text(
        text = text,
        color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(2.dp),
    )
}

@Composable
fun Badge(
    text: String,
    color: Color,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = contentColor, fontSize = 12.sp)
    }
}

/// VRChat OSC 有効時のバッジ。iPad 版と同じく紫系カプセル + 紙飛行機絵文字。
/// タップで VRChat 設定画面を開く。
@Composable
private fun VrChatActiveBadge(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                color = androidx.compose.ui.graphics.Color(0xFF8E24AA), // purple 600
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "\u2708\uFE0F",  // ✈️ paper-plane (emoji variation)
            color = Color.White,
            fontSize = 11.sp,
        )
        Text(
            text = "VRChat OSC",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

/// chatbox の下書き文字数カウンター。`N/144` 形式で表示し、超過時は赤く反転。
/// 144 文字を超えた分は VrChatOscOutput が黙ってトリムするため、その警告を
/// ユーザーに見せるためのもの。
@Composable
private fun ChatboxLengthCounter(length: Int) {
    val max = com.gime.android.osc.VrChatOscOutput.MAX_CHATBOX_LEN
    val over = length >= max
    val bg = if (over) MaterialTheme.colorScheme.error
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (over) MaterialTheme.colorScheme.onError
             else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = "$length/$max",
        color = fg,
        fontSize = 11.sp,
        fontWeight = if (over) androidx.compose.ui.text.font.FontWeight.SemiBold
                     else androidx.compose.ui.text.font.FontWeight.Normal,
        modifier = Modifier
            .background(bg, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
