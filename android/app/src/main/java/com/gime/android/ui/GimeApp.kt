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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
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
        CandidateOverlay(
            inputManager = inputManager,
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
/// - 何も表示すべき状態が無ければ縦幅 0 で畳む（オーバーレイが消える）。
/// - LS / RS の方向別役割はビジュアライザ本体（DpadDisplay）のスティック内部に
///   描画されるので、ここではテキストヒントを出さない。
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CandidateOverlay(
    inputManager: GamepadInputManager,
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
                // LS/RS のヒントはビジュアライザのスティック内部に直接描画する
                // ようになったので、ここのテキスト行は廃止。
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

/// 統一レイアウトのビジュアライザ本体。
///
/// レイアウト（全モード共通、言語切替で揺れない）:
/// ```
/// [LT][LB]                                  [RB][RT]   ← 上段: ショルダー＋トリガー
/// [LS◯]  [D-pad 5cell]   [Face 4dir]   [RS◯]          ← 中段: スティック + クラスタ
/// R: ↑... ↓... ←... →...                              ← 下段: RS 方向ヒント（参照用）
/// ```
///
/// - D-pad: 中央セルあり 5 セル。中央セルは LS が neutral のとき face buttons が
///   出すデフォルト出力を表示するので、ユーザーはどの方向に倒すと何が出るかを
///   一覧できる。
/// - フェイスボタン: 中央なし 4 方向（X=左 / Y=上 / B=右 / A=下）。
/// - LS / RS: 単一円 + 方向ドット。LS の中央ラベルはスティックの「役割」を表示する
///   （Devanagari の varga 代表子音など）。
/// - Devanagari の LS latch は中立に戻っても primary 色のドットが残る。
/// - ボタン形状: D-pad / ショルダー = 角丸矩形 / フェイスボタン・スティック = 円。
@Composable
fun DpadDisplay(
    inputManager: GamepadInputManager,
) {
    val mode = inputManager.currentMode
    val activeRow = inputManager.activeRow
    val isLB = inputManager.activeLayer == GamepadInputManager.ActiveLayer.LB
    val dir = dpadDirectionForRow(activeRow)
    val englishShift = inputManager.englishCapsLock || inputManager.englishSmartCaps || inputManager.englishShiftNext
    val isRT = inputManager.btnRT

    // 現在アクティブな D-pad 方向のフェイスボタン文字（RB ラベル等の動的表示に使用）
    val activeRowFaceChars: Array<String> = getFaceChars(mode, activeRow, englishShift, isRT, inputManager)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 上段: トリガー & ショルダー
        // 左 [LT][LB]、右 [RB][RT] と外→内の順で実機の物理配置に合わせる。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ShoulderChip(label = ltLabel(mode, inputManager), pressed = inputManager.btnLT)
                ShoulderChip(label = lbLabel(mode, isLB, inputManager), pressed = inputManager.btnLB)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ShoulderChip(label = rbLabel(mode, activeRowFaceChars, inputManager), pressed = inputManager.btnRB)
                ShoulderChip(label = rtLabel(mode, inputManager), pressed = inputManager.btnRT)
            }
        }

        // 中段: LS | D-pad | Face buttons | RS
        // LS / RS は内部 3×3 グリッドに方向別ラベルを直接表示するので、
        // 下段に分離していたテキストヒントは廃止。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StickIndicator(role = StickRole.LEFT, mode = mode, inputManager = inputManager)
            DpadCluster3x3(mode = mode, isLB = isLB, dir = dir, englishShift = englishShift, inputManager = inputManager)
            FaceButtons3x3(faceChars = activeRowFaceChars, inputManager = inputManager)
            StickIndicator(role = StickRole.RIGHT, mode = mode, inputManager = inputManager)
        }
    }
}

/// D-pad 5 セル（中央あり）。中央セルは LS が neutral のとき face buttons が出す
/// row 0 のチャー、または当該モードの「デフォルト出力」を表示する。
/// LS が他方向のときも 5 セル全体は変わらず、活性セルだけ primaryContainer で
/// ハイライトされる仕組みなので、ユーザーは「どの方向に倒すと何が出るか」を
/// 一覧できる。
@Composable
private fun DpadCluster3x3(
    mode: com.gime.android.engine.GamepadInputMode,
    isLB: Boolean,
    dir: Int,
    englishShift: Boolean,
    inputManager: GamepadInputManager,
) {
    // スティックの 3x3 グリッドと並べたとき IME の利用幅 (~336dp) に収まるよう
    // 40dp に縮める。文字サイズは 10sp で統一しているので可読性は維持できる。
    val cellSize = 40.dp
    val offset = if (isLB) 5 else 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ClusterCell(mode, 2 + offset, dir == 2, englishShift, inputManager, cellSize)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ClusterCell(mode, 1 + offset, dir == 1, englishShift, inputManager, cellSize)
            ClusterCell(mode, 0 + offset, dir == 0, englishShift, inputManager, cellSize)
            ClusterCell(mode, 3 + offset, dir == 3, englishShift, inputManager, cellSize)
        }
        ClusterCell(mode, 4 + offset, dir == 4, englishShift, inputManager, cellSize)
    }
}

/// 1 つの D-pad セル。Japanese/English/Chinese は内部 5 文字を十字配置で表示し、
/// Korean / Devanagari は単一文字を中央に表示する（chars 配列の長さで判定）。
@Composable
private fun ClusterCell(
    mode: com.gime.android.engine.GamepadInputMode,
    row: Int,
    isActive: Boolean,
    englishShift: Boolean,
    inputManager: GamepadInputManager,
    size: androidx.compose.ui.unit.Dp,
) {
    val chars = getCellChars(mode, row, englishShift, inputManager)
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    val isSingle = mode == com.gime.android.engine.GamepadInputMode.KOREAN ||
                   mode == com.gime.android.engine.GamepadInputMode.DEVANAGARI
    // 上下左右の文字が中央の文字と縦方向に重ならないよう、Text の line metrics を
    // タイトに（lineHeight = fontSize、includeFontPadding = false）して、
    // align(TopCenter) / align(BottomCenter) でセル端ぎりぎりに張り付ける。
    val tightStyle = TextStyle(
        fontSize = 10.sp,
        color = fg,
        lineHeight = 10.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (isSingle) {
            if (chars.isNotEmpty() && chars[0].isNotEmpty()) {
                Text(chars[0], color = fg, fontSize = 18.sp)
            }
            return@Box
        }
        // 5 文字を十字配置（中央=row[0]、上=row[2]、下=row[4]、左=row[1]、右=row[3]）。
        // 上下は align(TopCenter/BottomCenter) でセル端に張り付け、
        // padding=0 で物理的に中央セルから離す。
        if (chars.size > 2 && chars[2].isNotEmpty()) {
            Text(chars[2], style = tightStyle, modifier = Modifier.align(Alignment.TopCenter))
        }
        if (chars.size > 4 && chars[4].isNotEmpty()) {
            Text(chars[4], style = tightStyle, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (chars.size > 1 && chars[1].isNotEmpty()) {
            Text(chars[1], style = tightStyle,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp))
        }
        if (chars.size > 3 && chars[3].isNotEmpty()) {
            Text(chars[3], style = tightStyle,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
        }
        if (chars.isNotEmpty() && chars[0].isNotEmpty()) {
            Text(chars[0], style = tightStyle)
        }
    }
}

/// 1 セル内に表示する文字列を返す。
/// - Japanese / English / Chinese: 5 文字を十字配置（[center, left, up, right, down]）
/// - Korean / Devanagari: 単一文字（[char]）
/// - 該当行が無いときは空配列
private fun getCellChars(
    mode: com.gime.android.engine.GamepadInputMode,
    row: Int,
    englishShift: Boolean,
    inputManager: GamepadInputManager,
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
            val single = when (row % 5) {
                0 -> labels.center; 1 -> labels.left; 2 -> labels.up
                3 -> labels.right; 4 -> labels.down; else -> ""
            }
            arrayOf(single)
        }
        com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
            // Devanagari は activeRow を使わず、devaLsDir と devaNonVargaActive で
            // 4 方向の 1 文字を直接決める。row パラメータをそのまま方向 index として使う。
            val all: Array<String> = if (inputManager.devaNonVargaActive) {
                com.gime.android.engine.devaNonVargaDisplayChars(inputManager.btnLT)
            } else {
                com.gime.android.engine.devaVargaDisplayChars(
                    com.gime.android.engine.resolveDevaVarga(inputManager.devaLsDir)
                )
            }
            // all は [center, left, up, right, down] 順。row index 1..4 が 4 方向に対応。
            val idx = row.coerceIn(0, 4)
            arrayOf(all.getOrElse(idx) { "" })
        }
    }
}

/// 現在アクティブな D-pad 方向でフェイスボタン (X/Y/A/B/RB) を押すと出る文字。
/// RB ラベルの動的表示や、韓国語/Devanagari の母音フェイスボタン表示に使う。
private fun getFaceChars(
    mode: com.gime.android.engine.GamepadInputMode,
    activeRow: Int,
    englishShift: Boolean,
    rtPressed: Boolean,
    inputManager: GamepadInputManager,
): Array<String> {
    return when (mode) {
        com.gime.android.engine.GamepadInputMode.KOREAN ->
            if (rtPressed) com.gime.android.engine.KOREAN_VOWEL_CHARS_SHIFTED
            else com.gime.android.engine.KOREAN_VOWEL_CHARS_BASE
        com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
            // [center=RB ラベル相当, X=ए, Y=अ, B=इ, A=उ/ऋ]
            // varnamala 時計回り。LT 押下で A 方向 (down) のみ ऋ にシフト。
            val downChar = if (inputManager.btnLT) "ऋ" else "उ"
            arrayOf("", "ए", "अ", "इ", downChar)
        }
        else -> getCellChars(mode, activeRow, englishShift, inputManager)
    }
}

/// フェイスボタン 4 方向（中央なし）。Y=上、X=左、B=右、A=下。
/// 全モード共通で同じレイアウト・サイズを使う。chars が 5 要素未満の場合は空表示。
@Composable
private fun FaceButtons3x3(
    faceChars: Array<String>,
    inputManager: GamepadInputManager,
) {
    val chars = if (faceChars.size >= 5) faceChars else arrayOf("", "", "", "", "")
    val cellSize = 32.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaceButton(chars[2], inputManager.btnY, size = cellSize)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FaceButton(chars[1], inputManager.btnX, size = cellSize)
            Spacer(modifier = Modifier.size(cellSize))
            FaceButton(chars[3], inputManager.btnB, size = cellSize)
        }
        FaceButton(chars[4], inputManager.btnA, size = cellSize)
    }
}

/// LS / RS の役割識別。center label と方向ドット計算に使う。
private enum class StickRole { LEFT, RIGHT }

/// iOS 版 `rightStickGrid` 風のスティックビジュアライザ。直径 60dp の円の内部に
/// 3×3 グリッドを敷き、上下左右にモード別の役割ラベルを直接表示する。
/// 中央セルはクリック時の動作（確定・取消）を表示し、Devanagari LS のみ
/// 現在 latch している varga の代表子音 (क/च/ट/त/प) を出す。
///
/// 仕様:
///   - 物理的に倒している方向セルは primaryContainer で活性表示。
///   - LS クリック中（btnLS=true）は中央セルが primary で活性表示。
///   - Devanagari の LS latch は物理ドットが neutral に戻っても活性表示が残る。
@Composable
private fun StickIndicator(
    role: StickRole,
    mode: com.gime.android.engine.GamepadInputMode,
    inputManager: GamepadInputManager,
) {
    val outerSize = 60.dp
    val cellSize = 18.dp
    val pressed = if (role == StickRole.LEFT) inputManager.btnLS else inputManager.btnRS
    val dir = if (role == StickRole.LEFT) inputManager.lStickDir else inputManager.rStickDir
    // Devanagari の LS latch を「保持表示」として方向セル活性で示す。物理 dir が
    // neutral でも latchDir は残るので、ユーザーは LS から指を離した後も varga
    // 選択状態を視認できる。
    val latchDir: GamepadInputManager.StickDirection = if (
        role == StickRole.LEFT && mode == com.gime.android.engine.GamepadInputMode.DEVANAGARI
    ) {
        when (inputManager.devaLsDir) {
            com.gime.android.engine.DevaLsDirection.UP -> GamepadInputManager.StickDirection.UP
            com.gime.android.engine.DevaLsDirection.DOWN -> GamepadInputManager.StickDirection.DOWN
            com.gime.android.engine.DevaLsDirection.LEFT -> GamepadInputManager.StickDirection.LEFT
            com.gime.android.engine.DevaLsDirection.RIGHT -> GamepadInputManager.StickDirection.RIGHT
            com.gime.android.engine.DevaLsDirection.NEUTRAL -> GamepadInputManager.StickDirection.NEUTRAL
        }
    } else GamepadInputManager.StickDirection.NEUTRAL

    fun cellLabel(d: GamepadInputManager.StickDirection): String =
        stickDirectionLabel(role, d, mode, inputManager)
    val centerLabel = stickCenterLabel(role, mode, inputManager)

    Box(
        modifier = Modifier
            .size(outerSize)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 上行: ↑ のみ
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Spacer(modifier = Modifier.size(cellSize))
                StickCell(
                    label = cellLabel(GamepadInputManager.StickDirection.UP),
                    isActive = dir == GamepadInputManager.StickDirection.UP ||
                               latchDir == GamepadInputManager.StickDirection.UP,
                    size = cellSize,
                )
                Spacer(modifier = Modifier.size(cellSize))
            }
            // 中行: ← center →
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                StickCell(
                    label = cellLabel(GamepadInputManager.StickDirection.LEFT),
                    isActive = dir == GamepadInputManager.StickDirection.LEFT ||
                               latchDir == GamepadInputManager.StickDirection.LEFT,
                    size = cellSize,
                )
                StickCell(
                    label = centerLabel,
                    isActive = pressed,
                    size = cellSize,
                )
                StickCell(
                    label = cellLabel(GamepadInputManager.StickDirection.RIGHT),
                    isActive = dir == GamepadInputManager.StickDirection.RIGHT ||
                               latchDir == GamepadInputManager.StickDirection.RIGHT,
                    size = cellSize,
                )
            }
            // 下行: ↓ のみ
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Spacer(modifier = Modifier.size(cellSize))
                StickCell(
                    label = cellLabel(GamepadInputManager.StickDirection.DOWN),
                    isActive = dir == GamepadInputManager.StickDirection.DOWN ||
                               latchDir == GamepadInputManager.StickDirection.DOWN,
                    size = cellSize,
                )
                Spacer(modifier = Modifier.size(cellSize))
            }
        }
    }
}

/// スティック内の 1 セル。透明背景でラベルだけ出すと見栄えが寂しいので、活性時は
/// primaryContainer / primary（クリック中）でハイライトする。
@Composable
private fun StickCell(
    label: String,
    isActive: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else Color.Transparent
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = fg, fontSize = 9.sp, maxLines = 1)
        }
    }
}

/// スティックの方向セルに表示するラベル。1 文字に短縮することでセル幅 18dp に収める。
/// 「、。␣」などの複数文字ヒントは代表 1 字 （、 等）に絞る。
private fun stickDirectionLabel(
    role: StickRole,
    dir: GamepadInputManager.StickDirection,
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String {
    if (dir == GamepadInputManager.StickDirection.NEUTRAL) return ""
    return when (role) {
        StickRole.LEFT -> lsDirectionLabel(dir, mode, m)
        StickRole.RIGHT -> rsDirectionLabel(dir, mode, m)
    }
}

/// LS 方向別の役割ラベル。
///
/// 状態によって意味が切り替わるモードがある:
///   - 日本語 / 変換中: ↑↓ = 候補サイクル "候"、←→ = 文節フォーカス "文"
///   - 日本語 / 未変換: ↓ = 変換 "変"、←→ = カーソル "←/→"
///   - 中国語 / 候補表示中: ↑↓ = 候補サイクル "候"
///   - Devanagari: 4 方向 = varga 選択（各方向の代表子音 क/च/ट/त）
///   - その他 / アイドル: 空欄（D-pad のハイライトとドット位置で意図は伝わる）
private fun lsDirectionLabel(
    dir: GamepadInputManager.StickDirection,
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String = when (mode) {
    com.gime.android.engine.GamepadInputMode.JAPANESE -> when {
        m.isConverting -> when (dir) {
            GamepadInputManager.StickDirection.UP,
            GamepadInputManager.StickDirection.DOWN -> "候"
            GamepadInputManager.StickDirection.LEFT,
            GamepadInputManager.StickDirection.RIGHT -> "文"
            GamepadInputManager.StickDirection.NEUTRAL -> ""
        }
        m.hiraganaBuffer.isNotEmpty() -> when (dir) {
            GamepadInputManager.StickDirection.DOWN -> "変"
            GamepadInputManager.StickDirection.LEFT -> "←"
            GamepadInputManager.StickDirection.RIGHT -> "→"
            else -> ""
        }
        else -> ""
    }
    com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED,
    com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> when {
        m.pinyinCandidates.isNotEmpty() && (
            dir == GamepadInputManager.StickDirection.UP ||
            dir == GamepadInputManager.StickDirection.DOWN
        ) -> "候"
        else -> ""
    }
    com.gime.android.engine.GamepadInputMode.DEVANAGARI -> when (dir) {
        GamepadInputManager.StickDirection.UP -> "क"
        GamepadInputManager.StickDirection.RIGHT -> "च"
        GamepadInputManager.StickDirection.DOWN -> "ट"
        GamepadInputManager.StickDirection.LEFT -> "त"
        GamepadInputManager.StickDirection.NEUTRAL -> ""
    }
    else -> ""
}

/// RS 方向別の役割ラベル。代表 1 字に短縮。
private fun rsDirectionLabel(
    dir: GamepadInputManager.StickDirection,
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String = when (mode) {
    com.gime.android.engine.GamepadInputMode.JAPANESE -> when (dir) {
        GamepadInputManager.StickDirection.UP -> "゛"
        GamepadInputManager.StickDirection.DOWN -> "、"
        GamepadInputManager.StickDirection.LEFT -> "⌫"
        GamepadInputManager.StickDirection.RIGHT -> "ー"
        GamepadInputManager.StickDirection.NEUTRAL -> ""
    }
    com.gime.android.engine.GamepadInputMode.ENGLISH -> when (dir) {
        GamepadInputManager.StickDirection.UP -> "'"
        GamepadInputManager.StickDirection.DOWN -> "␣"
        GamepadInputManager.StickDirection.LEFT -> "⌫"
        GamepadInputManager.StickDirection.RIGHT -> "/"
        GamepadInputManager.StickDirection.NEUTRAL -> ""
    }
    com.gime.android.engine.GamepadInputMode.KOREAN -> when {
        // 자모 모드では ↑ 평격경 cycle / → 直前 jamo 連打
        m.koreanJamoLock || m.koreanSmartJamo -> when (dir) {
            GamepadInputManager.StickDirection.UP -> "ㅋ"  // 平→격→경 cycle 代表
            GamepadInputManager.StickDirection.DOWN -> "␣"
            GamepadInputManager.StickDirection.LEFT -> "⌫"
            GamepadInputManager.StickDirection.RIGHT -> "↻"  // 連打
            GamepadInputManager.StickDirection.NEUTRAL -> ""
        }
        else -> when (dir) {
            GamepadInputManager.StickDirection.UP -> "ㅋ"
            GamepadInputManager.StickDirection.DOWN -> "␣"
            GamepadInputManager.StickDirection.LEFT -> "⌫"
            GamepadInputManager.StickDirection.RIGHT -> "ㅘ"
            GamepadInputManager.StickDirection.NEUTRAL -> ""
        }
    }
    com.gime.android.engine.GamepadInputMode.CHINESE_SIMPLIFIED,
    com.gime.android.engine.GamepadInputMode.CHINESE_TRADITIONAL -> when (dir) {
        GamepadInputManager.StickDirection.DOWN -> "，"
        GamepadInputManager.StickDirection.LEFT -> "⌫"
        GamepadInputManager.StickDirection.RIGHT -> "、"
        else -> ""
    }
    com.gime.android.engine.GamepadInputMode.DEVANAGARI -> when (dir) {
        GamepadInputManager.StickDirection.UP -> "ं"
        GamepadInputManager.StickDirection.DOWN -> "␣"
        GamepadInputManager.StickDirection.LEFT -> "⌫"
        GamepadInputManager.StickDirection.RIGHT -> "ा"
        GamepadInputManager.StickDirection.NEUTRAL -> ""
    }
}

/// スティック中央セルのラベル（クリック時の動作 + Devanagari の varga）。
///   - LS クリック: 「確定」（日本語）/ Devanagari の varga 代表子音 / 他は空
///   - RS クリック: 「取消」相当（共通で「✕」）
private fun stickCenterLabel(
    role: StickRole,
    mode: com.gime.android.engine.GamepadInputMode,
    m: GamepadInputManager,
): String = when (role) {
    StickRole.LEFT -> when (mode) {
        com.gime.android.engine.GamepadInputMode.DEVANAGARI -> {
            if (m.devaNonVargaActive) "✻"
            else {
                val v = com.gime.android.engine.resolveDevaVarga(m.devaLsDir)
                com.gime.android.engine.DEVA_VARGA_CONSONANTS[v.index][0].toString()
            }
        }
        com.gime.android.engine.GamepadInputMode.JAPANESE -> "決"  // 確定
        else -> "✓"
    }
    StickRole.RIGHT -> "✕"  // 取消
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

/// フェイスボタン。iOS 版に合わせて円形（CircleShape）。
@Composable
private fun FaceButton(
    label: String,
    pressed: Boolean,
    size: androidx.compose.ui.unit.Dp = 30.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                if (pressed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                androidx.compose.foundation.shape.CircleShape,
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

/// ショルダー / トリガー。iOS 版に合わせて角丸 8dp の矩形。
@Composable
private fun ShoulderChip(label: String, pressed: Boolean) {
    val bg = when {
        pressed -> MaterialTheme.colorScheme.primary
        label.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .height(22.dp)
            .background(bg, RoundedCornerShape(8.dp))
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
