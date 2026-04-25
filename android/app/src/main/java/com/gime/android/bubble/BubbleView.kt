package com.gime.android.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gime.android.input.GamepadInputManager
import com.gime.android.osc.VrChatOscOutput
import com.gime.android.osc.VrChatOscSettings
import com.gime.android.ui.GamepadVisualizer
import com.gime.android.ui.GimeTheme
import kotlin.math.roundToInt

/**
 * バブル表示（フローティングオーバーレイ）の View。
 *
 * - ComposeView でビジュアライザのみを表示
 * - タイトルバーのドラッグで位置移動
 * - ゲームパッドの KeyEvent / MotionEvent はウィンドウがフォーカスを持つときに届く
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val service: BubbleService,
    private val layoutParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
) : FrameLayout(context) {

    /// いま入力を受け付けているか。外タップで false、内タップで true に遷移。
    private var isActive: Boolean = true

    init {
        // バブルをタップしたら入力を受ける（focusable）、外をタップしたら手放す
        // （not focusable）。setFocused() でフラグと alpha を一緒に切り替える。
        // 初期状態は active（ユーザーが「バブル」ボタンを明示的に押したので、
        // そのまま使えるようにする）。
        setupCompose()
        // 初期フラグは BubbleService 側で focusable 側に設定されている。
        alpha = ALPHA_FOCUSED
    }

    /// 外部（BubbleService）からも active 状態を切り替えられるよう公開する。
    /// 送信後の自動フォーカス解放で使う。
    fun setActive(active: Boolean) = setActiveInternal(active)

    private fun setActiveInternal(active: Boolean) {
        if (active == isActive) return
        isActive = active
        // フラグを切替: active なら focusable、inactive なら NOT_FOCUSABLE。
        // 常に FLAG_NOT_TOUCH_MODAL + FLAG_WATCH_OUTSIDE_TOUCH は維持し、
        // 外タップは検出できるようにする。
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.flags = if (active) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(this, layoutParams)
        } catch (_: Throwable) {}
        if (!active) {
            // フォーカスを失う側に倒すなら、押下中のキーが ACTION_UP を
            // 取り逃した状態のまま固着しないように snapshot を空に揃える。
            // 通常はこのあと onWindowFocusChanged(false) が同じ処理を再度
            // 呼ぶが、updateViewLayout 経由のフォーカス変化はタイミングが
            // 環境依存なので明示的にも呼んでおく。
            service.resetGamepadState()
        }
        animate()
            .alpha(if (active) ALPHA_FOCUSED else ALPHA_UNFOCUSED)
            .setDuration(120L)
            .start()
    }

    private fun setupCompose() {
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
        }
        addView(
            compose,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )

        compose.setContent {
            GimeTheme {
                // compact モードの状態は SharedPreferences と同期する。
                // トグル変更時に即時永続化 & 再コンポーズさせる。
                val bubbleSettings = remember { BubbleSettings(context) }
                var compact by remember { mutableStateOf(bubbleSettings.compactMode) }
                // バブルの横幅。
                // 当初はモード別に動的変更しようとしたが、WindowManager 側で
                // WRAP_CONTENT をモード切替時に再測定してくれず幅が変わらなかった
                // ため、最も幅を要する韓国語 / Devanagari（D-pad 隣にフェイスボタン
                // 列を並べる構成）に合わせた固定幅にしている。
                // - compact: D-pad 自体を隠すので狭めで OK
                // - 展開時: 韓国語 / Devanagari の右肩 (RT/RB) チップが見切れない
                //   下限が 380dp。多少余裕を持たせて 380dp で固定。
                val bubbleWidth = if (compact) 260.dp else 380.dp
                Surface(
                    modifier = Modifier
                        .width(bubbleWidth)
                        .clip(RoundedCornerShape(16.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val oscEnabled = remember {
                            VrChatOscSettings(context).enabled
                        }
                        TitleBar(
                            inputManager = service.inputManager,
                            compact = compact,
                            // OSC 有効時だけ chatbox 文字数を表示。0 のときは隠す。
                            chatboxLength = if (oscEnabled) service.draftPreview.value.length else 0,
                            onToggleCompact = {
                                compact = !compact
                                bubbleSettings.compactMode = compact
                                // WindowManager の width は WRAP_CONTENT が
                                // 動的に追随しないため、明示的に updateViewLayout
                                // で px を指定して広げ直す。
                                service.updateBubbleWidth(compact)
                            },
                            onClose = { service.stopBubble() },
                            onDrag = { dx, dy -> applyDrag(dx, dy) },
                        )
                        if (oscEnabled) {
                            DraftPreview(service.draftPreview.value)
                        }
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            GamepadVisualizer(
                                inputManager = service.inputManager,
                                vrChatEnabled = oscEnabled,
                                compact = compact,
                                // バブルは VRChat 専用運用なので OSC バッジは冗長
                                showVrChatBadge = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        layoutParams.x += dx.roundToInt()
        layoutParams.y += dy.roundToInt()
        try {
            windowManager.updateViewLayout(this, layoutParams)
        } catch (_: Throwable) {
            // view が detach 済みの場合など
        }
    }

    // ゲームパッドイベントはフォーカスがこの View（またはその子孫）にある場合のみ
    // 届く。root レベルで dispatch を横取りすることで ComposeView に吸われる前に
    // 処理する。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (service.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (service.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /// フォーカスを失った瞬間に「押下中」だったボタンの ACTION_UP は別ウィンドウへ
    /// 流れてしまい、こちらの snapshot に押下フラグが残る。次に復帰した後で
    /// 同じボタンを押しても "前回と同じ bit パターン" と判定されて何も起きない
    /// （"バブルをタップしてもボタンが通らない" 症状）。フォーカス喪失時に
    /// snapshot を全リリースに揃えてエッジを正常終了させる。
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            service.resetGamepadState()
        }
    }

    /// タッチイベントで active/inactive を切り替える。
    /// 内側タップ(DOWN) → active、外側タップ(ACTION_OUTSIDE) → inactive。
    /// ACTION_OUTSIDE は FLAG_WATCH_OUTSIDE_TOUCH が付いているときに届く
    /// 単発イベント（座標は外側タップ時の DOWN 位置）。
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                setActiveInternal(false)
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                if (!isActive) setActiveInternal(true)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        private const val ALPHA_FOCUSED = 1.0f
        private const val ALPHA_UNFOCUSED = 0.45f
    }
}

/// OSC に送る前の下書きテキストを表示するプレビュー行。
/// 韓国語 / 英語など直接入力モードで「いま何を入力したか」を見せるために使う。
@Composable
private fun DraftPreview(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u2708\uFE0F",  // ✈
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.width(6.dp),
        )
        if (text.isEmpty()) {
            Text(
                text = "LS×2 で送信",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TitleBar(
    inputManager: GamepadInputManager,
    compact: Boolean,
    chatboxLength: Int,
    onToggleCompact: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(32.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
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
            // 言語モードインジケータ（KOREAN→韓 など short label）
            ModeChip(inputManager.currentMode.label)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (chatboxLength > 0) {
                ChatboxLengthChip(length = chatboxLength)
            }
            TitleIconButton(
                label = if (compact) "\u25B8" else "\u25BE",  // ▸ / ▾
                contentDescription = if (compact) "展開" else "折りたたむ",
                onClick = onToggleCompact,
            )
            TitleIconButton(
                label = "\u00D7",  // ×
                contentDescription = "閉じる",
                onClick = onClose,
            )
        }
    }
}

/// タイトルバー右側の chatbox 文字数表示。144 超過時は赤反転。
@Composable
private fun ChatboxLengthChip(length: Int) {
    val max = VrChatOscOutput.MAX_CHATBOX_LEN
    val over = length >= max
    val bg = if (over) MaterialTheme.colorScheme.error
             else MaterialTheme.colorScheme.surfaceContainerLow
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

@Composable
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

@Composable
private fun TitleIconButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val desc = contentDescription  // 将来の Accessibility 向けに保持
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
