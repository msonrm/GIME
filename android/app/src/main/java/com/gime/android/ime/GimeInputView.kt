package com.gime.android.ime

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.gime.android.osc.VrChatOscSettings
import com.gime.android.ui.GamepadVisualizer
import com.gime.android.ui.GimeTheme

/**
 * GIME IME の InputView。
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

    init {
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
                // システム IME 風に、上端だけ角丸の Surface で包む。
                // Surface が colorScheme.surface を採用するので Material You の
                // ダイナミックカラーとダーク/ライトモードが自動反映される。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        // VRChat OSC 有効時のバッジはビジュアライザ内部で描画される。
                        // IME コンテキストでは設定画面を開けないのでタップ動作は no-op。
                        val oscEnabled = androidx.compose.runtime.remember {
                            VrChatOscSettings(context).enabled
                        }
                        GamepadVisualizer(
                            inputManager = service.inputManager,
                            vrChatEnabled = oscEnabled,
                            chatboxLength = service.draftLengthState.intValue,
                        )
                    }
                }
            }
        }
    }

    private fun setupTextFallback() {
        val tv = TextView(context).apply {
            text = "GIME IME (fallback)\nCompose 初期化に失敗"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#202020"))
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
