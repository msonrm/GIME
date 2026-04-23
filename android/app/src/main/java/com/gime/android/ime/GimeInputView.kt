package com.gime.android.ime

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gime.android.osc.VrChatOscSettings
import com.gime.android.settings.ImeUiSettings
import com.gime.android.ui.CandidateOverlay
import com.gime.android.ui.DpadDisplay
import com.gime.android.ui.GimeTheme

/**
 * GIME IME の InputView。
 *
 * 構造（Column、上から下へ）:
 *   1. CandidateOverlay (透過 bg) … composing 中のみ縦幅を持つ。
 *      この領域は `onComputeInsets` で contentTopInsets の上に配置され、
 *      アプリの入力欄を押し上げないフローティング表示になる。
 *   2. Compact バー (固定高、不透明) … ▾/▸ トグルとモードチップ等を載せる。
 *      compact バーの top y を Service に通知し、contentTopInsets として使う。
 *   3. 展開時のみ: フルビジュアライザ (不透明、D-pad 等)。展開時は contentTopInsets
 *      を compact バー top のままにすることで、候補オーバーレイは引き続き浮く。
 *
 * Material You 対応: Android 12+ なら壁紙由来のダイナミックカラーを適用。
 * システムのダーク/ライトモードにも自動追随する。
 */
class GimeInputView(
    context: Context,
    private val service: GimeInputMethodService,
) : FrameLayout(context) {

    companion object {
        private const val TAG = "GimeInputView"
    }

    /// compact バーの top y（この View 内ローカル座標）。
    /// Compose の onGloballyPositioned で更新し、Service がこの View の
    /// getLocationInWindow と合成して Insets/touchableRegion の計算に使う。
    @Volatile
    var compactBarTopInViewPx: Int = 0
        private set

    /// 候補オーバーレイの現時点の描画高さ（px, この View ローカル）。
    /// composing が無ければ 0。Service が touchableRegion 計算で使う。
    @Volatile
    var overlayHeightPx: Int = 0
        private set

    init {
        // 背景は透明。Compose 側の Surface が不透明領域（compact バー + 展開時の
        // ビジュアライザ）だけを塗り、オーバーレイ領域は背景を透過させる。
        setBackgroundColor(AndroidColor.TRANSPARENT)
        try {
            setupComposeContent()
        } catch (t: Throwable) {
            Log.e(TAG, "Compose setup failed, falling back to TextView", t)
            setupTextFallback()
        }
    }

    private fun setupComposeContent() {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
        }

        addView(
            composeView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )

        composeView.setContent {
            GimeTheme {
                val imeSettings = remember { ImeUiSettings(context) }
                var compact by remember { mutableStateOf(imeSettings.compactMode) }
                val oscEnabled = remember { VrChatOscSettings(context).enabled }

                Column(modifier = Modifier.fillMaxWidth()) {
                    // 1. 候補 / composing オーバーレイ（透過レイヤー）
                    //    描画高さを overlayHeightPx に書き戻し、Service に伝える。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .onGloballyPositioned { coords ->
                                overlayHeightPx = coords.size.height
                                service.onLayoutUpdated()
                            },
                    ) {
                        CandidateOverlay(
                            inputManager = service.inputManager,
                            // compact モードでは画面省スペース優先、ヒント非表示
                            showHints = !compact,
                        )
                    }

                    // 2. Compact バー（常時表示、不透明）
                    //    ここが IME の「見かけの上端」。ここより下は contentTopInsets で
                    //    アプリ側に IME 占有領域として報告する。
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                            .onGloballyPositioned { coords ->
                                // Compose root（＝ ComposeView）座標系での top y を保存。
                                // ComposeView はこの FrameLayout の (0,0) に addView されるので、
                                // そのまま View 座標系の y として使える。
                                compactBarTopInViewPx = coords.positionInRoot().y.toInt()
                                service.onLayoutUpdated()
                            },
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 3.dp,
                    ) {
                        CompactTitleBar(
                            modeLabel = service.inputManager.currentMode.label,
                            isConnected = service.inputManager.isConnected,
                            compact = compact,
                            vrChatEnabled = oscEnabled,
                            chatboxLength = service.draftLengthState.intValue,
                            onToggleCompact = {
                                compact = !compact
                                imeSettings.compactMode = compact
                                service.onLayoutUpdated()
                            },
                        )
                    }

                    // 3. 展開時のみ、フルビジュアライザ（D-pad, モード情報など）
                    if (!compact) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 3.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                // 候補/composing は既にオーバーレイレイヤーで描画済みなので
                                // ここでは展開部分の中核である DpadDisplay だけを直接呼び出す。
                                // （GamepadVisualizer 全体を呼ぶと候補行が二重描画されてしまう）
                                DpadDisplay(
                                    inputManager = service.inputManager,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupTextFallback() {
        val tv = TextView(context).apply {
            text = "GIME IME (fallback)\nCompose 初期化に失敗"
            setTextColor(AndroidColor.WHITE)
            setBackgroundColor(AndroidColor.parseColor("#202020"))
            setPadding(32, 32, 32, 32)
        }
        addView(
            tv,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
    }

    /// 互換 API（サービスからの呼び出しを維持）。Compose の observable
    /// state による自動再描画に任せるため no-op。
    fun updateStatus() {
        // no-op
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (service.handleMotionEvent(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

/// IME 内 compact タイトルバー。
/// バブルの TitleBar と同じ作法（▾/▸ で compact 切替、× は出さない＝IME は
/// キーボードスイッチャで切替えるのが筋）。VRChat OSC バッジ + chatbox 文字数も
/// 右寄せで揃える。
@androidx.compose.runtime.Composable
private fun CompactTitleBar(
    modeLabel: String,
    isConnected: Boolean,
    compact: Boolean,
    vrChatEnabled: Boolean,
    chatboxLength: Int,
    onToggleCompact: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "GIME",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModeChip(modeLabel)
            if (!isConnected) {
                Text(
                    text = "未接続",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (vrChatEnabled) {
                VrChatChip()
                if (chatboxLength > 0) {
                    ChatboxLengthChip(length = chatboxLength)
                }
            }
            TitleIconButton(
                label = if (compact) "▾" else "▴", // ▾ expand / ▴ collapse
                onClick = onToggleCompact,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ModeChip(label: String) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
        )
    }
}

@androidx.compose.runtime.Composable
private fun VrChatChip() {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xFF8E24AA)) // purple 600
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✈️",  // ✈
            color = Color.White,
            fontSize = 11.sp,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ChatboxLengthChip(length: Int) {
    val max = com.gime.android.osc.VrChatOscOutput.MAX_CHATBOX_LEN
    val over = length >= max
    val bg = if (over) MaterialTheme.colorScheme.error
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (over) MaterialTheme.colorScheme.onError
             else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = "$length/$max",
        fontSize = 10.sp,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@androidx.compose.runtime.Composable
private fun TitleIconButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

