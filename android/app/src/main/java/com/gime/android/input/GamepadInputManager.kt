package com.gime.android.input

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gime.android.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// ゲームパッド入力を管理し、GamepadResolver → テキスト操作パイプラインを駆動する
/// iOS GIME の GamepadInputManager.swift から移植（Android InputDevice ベース）
class GamepadInputManager {

    // MARK: - Public State（Compose で observe 可能）

    var activeRow: Int by mutableStateOf(0)
        private set
    var activeLayer: ActiveLayer by mutableStateOf(ActiveLayer.BASE)
        private set
    var currentMode: GamepadInputMode by mutableStateOf(GamepadInputMode.JAPANESE)
        private set
    var operationMode: GamepadOperationMode by mutableStateOf(GamepadOperationMode.NORMAL)
        private set
    var isConnected: Boolean by mutableStateOf(false)
        private set
    var gamepadName: String? by mutableStateOf(null)
        private set

    // 英語モード状態
    var englishShiftNext: Boolean by mutableStateOf(false)
        private set
    var englishCapsLock: Boolean by mutableStateOf(false)
        private set
    var englishSmartCaps: Boolean by mutableStateOf(false)
        private set

    // 韓国語モード状態（자모 모드: 子音・母音単体入力）
    /// 持続モード (LT 長押しでトグル)
    var koreanJamoLock: Boolean by mutableStateOf(false)
        private set
    /// 一時モード (LT 2連続短押しで ON、空白/句読点/削除等で自動 OFF)
    var koreanSmartJamo: Boolean by mutableStateOf(false)
        private set

    // Devanagari モード状態
    /// 非 varga サブレイヤー中か（L3 = LS クリックでトグル）。
    /// ON の間、D-pad は semivowel (य र ल व) / LT 押下時は sibilant (श ष स ह) を返す。
    var devaNonVargaActive: Boolean by mutableStateOf(false)
        private set
    /// 現在の LS 方向（ビジュアライザ用）。
    var devaLsDir: DevaLsDirection by mutableStateOf(DevaLsDirection.NEUTRAL)
        private set

    // 中国語モード状態
    var pinyinBuffer: String by mutableStateOf("")
        private set
    var zhuyinDisplayBuffer: String by mutableStateOf("")
        private set
    var pinyinCandidates: List<PinyinCandidate> by mutableStateOf(emptyList())
        private set
    var pinyinSelectedIndex: Int by mutableStateOf(0)
        private set

    // 日本語変換状態（Phase A2.4: 文節編集対応）
    /// 変換前のひらがなバッファ。直近の日本語かな入力の累積。
    var hiraganaBuffer: String by mutableStateOf("")
        private set
    /// 変換中か（候補選択中か）
    var isConverting: Boolean by mutableStateOf(false)
        private set
    /// 各文節のひらがな読み
    var bunsetsuReadings: List<String> by mutableStateOf(emptyList())
        private set
    /// 各文節の候補リスト
    var bunsetsuCandidates: List<List<JapaneseConverter.Candidate>> by mutableStateOf(emptyList())
        private set
    /// 各文節で選択中のインデックス
    var bunsetsuSelectedIndices: List<Int> by mutableStateOf(emptyList())
        private set
    /// フォーカス中の文節 index
    var focusedBunsetsuIndex: Int by mutableStateOf(0)
        private set
    /// 現在テキストフィールドに表示されている surface の文字数（置換用）
    private var currentCandidateLength: Int = 0
    /// 候補表示ウィンドウの先頭（フォーカス中の文節の候補リストに適用）
    var japaneseWindowStart: Int by mutableStateOf(0)
        private set
    private val japaneseWindowSize: Int = 9

    /// フォーカス中の文節の候補リスト（UI 表示用）
    val japaneseCandidates: List<JapaneseConverter.Candidate>
        get() = bunsetsuCandidates.getOrNull(focusedBunsetsuIndex) ?: emptyList()

    /// フォーカス中の文節で選択中のインデックス
    val selectedJapaneseCandidateIndex: Int
        get() = bunsetsuSelectedIndices.getOrNull(focusedBunsetsuIndex) ?: 0

    /// 表示用にウィンドウ内候補を返す（フォーカス中文節のもの）
    val visibleJapaneseCandidates: List<JapaneseConverter.Candidate>
        get() {
            val cands = japaneseCandidates
            if (cands.isEmpty()) return emptyList()
            val end = minOf(japaneseWindowStart + japaneseWindowSize, cands.size)
            return cands.subList(japaneseWindowStart, end)
        }

    /// ウィンドウ内での選択 index
    val japaneseSelectedIndexInWindow: Int
        get() = selectedJapaneseCandidateIndex - japaneseWindowStart

    // ビジュアライザ用のボタン押下状態（UI が Compose で observe する）
    var btnLB: Boolean by mutableStateOf(false); private set
    var btnRB: Boolean by mutableStateOf(false); private set
    var btnLT: Boolean by mutableStateOf(false); private set
    var btnRT: Boolean by mutableStateOf(false); private set
    var btnLS: Boolean by mutableStateOf(false); private set
    var btnRS: Boolean by mutableStateOf(false); private set
    var btnX: Boolean by mutableStateOf(false); private set
    var btnY: Boolean by mutableStateOf(false); private set
    var btnA: Boolean by mutableStateOf(false); private set
    var btnB: Boolean by mutableStateOf(false); private set

    enum class ActiveLayer { BASE, LB }

    enum class StickDirection { NEUTRAL, UP, DOWN, LEFT, RIGHT }

    /// Start ボタンでサイクルする言語。UI から書き換え可能（Compose observable）。
    /// 永続化は `GimeModeSettings` 側で行い、そのストアから pushed する想定。
    var enabledModes: List<GamepadInputMode> by mutableStateOf(GamepadInputMode.entries.toList())
        private set

    /// 有効モードリストを差し替える。現在モードが無効化された場合、
    /// 先頭のモードに自動遷移する（UI 上から「現在のモードを OFF」した時の救済）。
    fun updateEnabledModes(newList: List<GamepadInputMode>) {
        if (newList.isEmpty()) return
        enabledModes = newList
        if (currentMode !in newList) {
            currentMode = newList.first()
            // 切替時のリセット（Start ボタン切替と同じハウスキーピング）
            eagerChar = null
            koreanComposer.commit()
            patchimRollbackActive = false
            allReleasedSinceSyllable = true
            pinyinBuffer = ""
            zhuyinDisplayBuffer = ""
            pinyinCandidates = emptyList()
            pinyinSelectedIndex = 0
            pinyinWindowStart = 0
            englishSmartCaps = false
            englishShiftNext = false
            englishCapsLock = false
            koreanJamoLock = false
            koreanSmartJamo = false
            resetJamoState()
            devanagariComposer.commit()
            devaNonVargaActive = false
            prevDevaDpadDir = DevaDpadDir.NONE
            prevDevaFace = null
            rStickDownTapCount = 0
            rStickDownLastTime = 0
        }
    }

    // MARK: - コールバック

    var onDirectInsert: ((text: String, replaceCount: Int) -> Unit)? = null
    var onDeleteBackward: (() -> Unit)? = null
    var onCursorMove: ((offset: Int) -> Unit)? = null
    var onCursorMoveVertical: ((direction: Int) -> Unit)? = null
    var onConfirmOrNewline: (() -> Unit)? = null
    var onConvert: (() -> Unit)? = null
    var onGetLastCharacter: (() -> Char?)? = null

    /// IME 向け: 現在の composing region を確定する合図。
    /// 下線を外してテキストはそのまま残すセマンティクス。
    /// Activity モード（ローカル TextField）では何もしなくて良い。
    var onFinalizeComposing: (() -> Unit)? = null

    // MARK: - Dependencies

    var pinyinEngine: PinyinEngine? = null
    /// 日本語かな漢字変換エンジン。null の場合は変換無効（生かなのみ）。
    var japaneseConverter: JapaneseConverter? = null
    /// 非同期変換用のスコープ。MainActivity から注入する。
    var coroutineScope: CoroutineScope? = null
    private val koreanComposer = KoreanComposer()
    private val devanagariComposer = DevanagariComposer()

    // MARK: - Private State

    private val stickThreshold: Float = 0.5f
    // トリガー（LT/RT）はヒステリシスを持たせる。
    // アナログ値が閾値付近で揺れたときに press/release が連続発火するのを防ぐ
    private val triggerPressThreshold: Float = 0.5f
    private val triggerReleaseThreshold: Float = 0.15f
    // LT+RT=っ 出力後「ん」発火を抑止する時間（ms）。RT のジッター・バウンド対策
    private val nBlockAfterTsu: Long = 300L
    private val chordWindow: Long = 300L  // ms
    private val doubleTapWindow: Long = 400L
    private val rStickTapWindow: Long = 700L  // アナログスティック往復分を考慮して広め
    private val longPressThreshold: Long = 500L

    // 日本語 eager output
    private var eagerChar: String? = null
    private var eagerCharLen: Int = 0
    private var eagerTime: Long = 0

    private var prevRow: Int = 0
    private var prevVowel: VowelButton? = null
    private var prevConsonantCount: Int = 0

    // トリガー状態
    private var rtDuringLT = false
    private var rtUsed = false
    // 「っ」出力後に「ん」発火を抑止する期限（uptimeMillis）
    private var nBlockUntil: Long = 0
    private var prevLT = false
    private var prevRT = false

    // 右スティック
    private var rStickDownLastTime: Long = 0
    private var rStickDownTapCount: Int = 0
    private var prevRStickUp = false
    private var prevRStickDown = false
    private var prevRStickLeft = false
    private var prevRStickRight = false

    // 左スティック
    private var prevLStickUp = false
    private var prevLStickDown = false
    private var prevLStickLeft = false
    private var prevLStickRight = false

    // ボタン前回状態
    private var prevRB = false
    private var prevLB = false
    private var prevDpadUp = false
    private var prevDpadDown = false
    private var prevDpadLeft = false
    private var prevDpadRight = false
    private var prevBack = false
    private var prevStart = false
    private var prevLS = false
    private var prevRS = false
    private var startBackComboFired = false

    // 英語モード内部
    private var englishLTHolding = false
    private var lastLTReleaseTime: Long = 0
    private var ltPressTime: Long = 0

    // 韓国語モード内部
    private var allReleasedSinceSyllable = true
    private var patchimRollbackCoda: Int? = null
    private var patchimRollbackActive = false

    // 韓国語 LT 長押し・2連続タップ検出（英語モードと対称）
    private var koreanLTHolding = false
    private var koreanLTPressTime: Long = 0
    private var lastKoreanLTReleaseTime: Long = 0
    // LT 押下時点で ㅇ받침 条件を満たしていたか。LT 保持中に他ボタンが絡んだら破棄
    private var koreanLTShortTapEligible = false

    // 자모 모드内部: 直前 emit した jamo（右スティック → の連打用 + ↑ のサイクル用）
    private var lastJamoText: String? = null
    // 直前 emit が子音ならその onset index、母音なら null
    private var lastJamoOnsetIndex: Int? = null
    // 子音 chord の eager output + chord window refine 用
    private var jamoEagerText: String? = null
    private var jamoEagerLen: Int = 0
    private var jamoEagerTime: Long = 0
    // LB 単独押下中フラグ。LB を「修飾子」として扱うため、LB press edge では
    // 即時 emit せず、このフラグを立てて D-pad 方向入力を待つ。
    // - D-pad 方向エッジが来たら: フラグ解除、D-pad 側で row(LB 込み) を emit
    // - LB release edge で立ったままなら: そこで ㅁ (row 5) を emit
    private var jamoLbOnlyPending: Boolean = false

    // 中国語スライディングウィンドウ
    private var pinyinWindowStart: Int = 0
    private val pinyinWindowSize: Int = 9

    // Devanagari 内部状態
    /// D-pad 前回方向（devanagari の stop 変化検知用）
    private var prevDevaDpadDir: DevaDpadDir = DevaDpadDir.NONE
    /// 前回のフェイスボタン（母音エッジ検出）
    private var prevDevaFace: VowelButton? = null
    /// LS 押し込み (lsClick) の前回状態は既存の prevLS を使用

    val visiblePinyinCandidates: List<PinyinCandidate>
        get() {
            if (pinyinCandidates.isEmpty()) return emptyList()
            val end = minOf(pinyinWindowStart + pinyinWindowSize, pinyinCandidates.size)
            return pinyinCandidates.subList(pinyinWindowStart, end)
        }

    val pinyinSelectedIndexInWindow: Int
        get() = pinyinSelectedIndex - pinyinWindowStart

    // MARK: - 現在のスナップショット（イベント間で状態を保持）
    private var currentSnapshot = GamepadSnapshot()

    // 前回処理時の「決定に影響する」状態。MotionEvent のノイズで再処理しないために比較に使う
    private var lastRelevantBits: Int = 0

    // MARK: - Public API

    /// コントローラー接続時に呼ぶ
    fun onGamepadConnected(name: String) {
        gamepadName = name
        isConnected = true
    }

    /// コントローラー切断時に呼ぶ
    fun onGamepadDisconnected() {
        gamepadName = null
        isConnected = false
        lastRelevantBits = 0
    }

    /// 決定に影響するボタン・方向・トリガーのビットを1つの Int にパック
    private fun computeRelevantBits(gp: GamepadSnapshot): Int {
        val rsX = gp.rightStickX
        val rsY = gp.rightStickY
        val lsX = gp.leftStickX
        val lsY = gp.leftStickY
        val rsAbsX = kotlin.math.abs(rsX)
        val rsAbsY = kotlin.math.abs(rsY)
        val lsAbsX = kotlin.math.abs(lsX)
        val lsAbsY = kotlin.math.abs(lsY)
        val rsDom = maxOf(rsAbsX, rsAbsY) > stickThreshold
        val lsDom = maxOf(lsAbsX, lsAbsY) > stickThreshold
        var bits = 0
        if (gp.dpadUp) bits = bits or (1 shl 0)
        if (gp.dpadDown) bits = bits or (1 shl 1)
        if (gp.dpadLeft) bits = bits or (1 shl 2)
        if (gp.dpadRight) bits = bits or (1 shl 3)
        if (gp.buttonA) bits = bits or (1 shl 4)
        if (gp.buttonB) bits = bits or (1 shl 5)
        if (gp.buttonX) bits = bits or (1 shl 6)
        if (gp.buttonY) bits = bits or (1 shl 7)
        if (gp.lb) bits = bits or (1 shl 8)
        if (gp.rb) bits = bits or (1 shl 9)
        // LT/RT はヒステリシスの release 閾値を使って bits 化する。
        // 0.15〜0.5 の帯で値が変わる遷移も検知して handleSnapshot に渡すため。
        // （0.5 単一閾値だと handleSnapshot 側の release edge 0.15 を取りこぼす）
        if (gp.ltValue > triggerReleaseThreshold) bits = bits or (1 shl 10)
        if (gp.rtValue > triggerReleaseThreshold) bits = bits or (1 shl 11)
        if (gp.start) bits = bits or (1 shl 12)
        if (gp.back) bits = bits or (1 shl 13)
        if (gp.lsClick) bits = bits or (1 shl 14)
        if (gp.rsClick) bits = bits or (1 shl 15)
        if (rsDom && rsAbsY >= rsAbsX && rsY < 0) bits = bits or (1 shl 16)  // rStickUp
        if (rsDom && rsAbsY >= rsAbsX && rsY > 0) bits = bits or (1 shl 17)  // rStickDown
        if (rsDom && rsAbsX > rsAbsY && rsX < 0) bits = bits or (1 shl 18)   // rStickLeft
        if (rsDom && rsAbsX > rsAbsY && rsX > 0) bits = bits or (1 shl 19)   // rStickRight
        if (lsDom && lsAbsY >= lsAbsX && lsY < 0) bits = bits or (1 shl 20)  // lStickUp
        if (lsDom && lsAbsY >= lsAbsX && lsY > 0) bits = bits or (1 shl 21)  // lStickDown
        if (lsDom && lsAbsX > lsAbsY && lsX < 0) bits = bits or (1 shl 22)   // lStickLeft
        if (lsDom && lsAbsX > lsAbsY && lsX > 0) bits = bits or (1 shl 23)   // lStickRight
        return bits
    }

    /// スナップショットを更新して処理する（KeyEvent / MotionEvent から呼ばれる）
    fun updateSnapshot(snapshot: GamepadSnapshot) {
        val newBits = computeRelevantBits(snapshot)
        // 決定に影響しない変化（アナログ軸の軽微なノイズ等）は早期リターン。
        // ただし LT 長押し判定が進行中のときは時間経過を拾うため処理を続行。
        // 英語・韓国語いずれかの LT-hold が走っていれば閾値判定のために通す。
        val longPressPending = (englishLTHolding || koreanLTHolding) &&
            snapshot.ltValue > triggerReleaseThreshold
        if (newBits == lastRelevantBits && !longPressPending) {
            currentSnapshot = snapshot
            updateVisualState(snapshot)
            return
        }
        lastRelevantBits = newBits
        currentSnapshot = snapshot
        updateVisualState(snapshot)
        handleSnapshot(snapshot)
    }

    /// ビジュアライザ用のボタン押下状態を最新化する
    private fun updateVisualState(gp: GamepadSnapshot) {
        btnLB = gp.lb
        btnRB = gp.rb
        btnLT = gp.ltValue > triggerPressThreshold
        btnRT = gp.rtValue > triggerPressThreshold
        btnLS = gp.lsClick
        btnRS = gp.rsClick
        btnX = gp.buttonX
        btnY = gp.buttonY
        btnA = gp.buttonA
        btnB = gp.buttonB
    }

    // MARK: - メイン入力処理

    private fun handleSnapshot(gp: GamepadSnapshot) {
        val now = SystemClock.uptimeMillis()

        val consonant = ConsonantState(
            dpadUp = gp.dpadUp,
            dpadDown = gp.dpadDown,
            dpadLeft = gp.dpadLeft,
            dpadRight = gp.dpadRight,
            lb = gp.lb,
        )
        val vowel = resolveVowel(gp)
        val vowelNow = vowel != null
        // ヒステリシス: 押下判定は 0.5 超え、離し判定は 0.25 未満
        val ltNow = if (prevLT) gp.ltValue > triggerReleaseThreshold
                    else gp.ltValue > triggerPressThreshold
        val rtNow = if (prevRT) gp.rtValue > triggerReleaseThreshold
                    else gp.rtValue > triggerPressThreshold
        val lbNow = gp.lb

        val row = resolveConsonantRow(consonant)

        // 右スティック方向
        val rsX = gp.rightStickX
        val rsY = gp.rightStickY
        val rsAbsX = kotlin.math.abs(rsX)
        val rsAbsY = kotlin.math.abs(rsY)
        val rsDominant = maxOf(rsAbsX, rsAbsY) > stickThreshold
        val rStickRight = rsDominant && rsAbsX > rsAbsY && rsX > 0
        val rStickLeft = rsDominant && rsAbsX > rsAbsY && rsX < 0
        // Android Y軸は逆（上が負）
        val rStickUp = rsDominant && rsAbsY >= rsAbsX && rsY < 0
        val rStickDown = rsDominant && rsAbsY >= rsAbsX && rsY > 0

        // 左スティック方向
        val lsX = gp.leftStickX
        val lsY = gp.leftStickY
        val lsAbsX = kotlin.math.abs(lsX)
        val lsAbsY = kotlin.math.abs(lsY)
        val lsDominant = maxOf(lsAbsX, lsAbsY) > stickThreshold
        val lStickRight = lsDominant && lsAbsX > lsAbsY && lsX > 0
        val lStickLeft = lsDominant && lsAbsX > lsAbsY && lsX < 0
        val lStickUp = lsDominant && lsAbsY >= lsAbsX && lsY < 0
        val lStickDown = lsDominant && lsAbsY >= lsAbsX && lsY > 0

        // --- UI 状態更新（D-pad 表示用の最小限のみ）---
        if (activeRow != row) activeRow = row
        val newLayer = if (lbNow) ActiveLayer.LB else ActiveLayer.BASE
        if (activeLayer != newLayer) activeLayer = newLayer

        // --- Start / Back ボタン ---
        val startEdge = gp.start && !prevStart
        val backEdge = gp.back && !prevBack

        // Start+Back 同時押し
        if (gp.start && gp.back && !startBackComboFired) {
            startBackComboFired = true
        }
        if (!gp.start && !gp.back) {
            startBackComboFired = false
        }

        // Start: モード切替
        if (startEdge && !gp.back) {
            // 日本語変換中はモード切替時に確定
            if (currentMode == GamepadInputMode.JAPANESE) {
                resetComposingState()
            }
            currentMode = currentMode.next(enabledModes)
            // モード切替時にリセット
            eagerChar = null
            koreanComposer.commit()
            patchimRollbackActive = false
            allReleasedSinceSyllable = true
            pinyinBuffer = ""
            zhuyinDisplayBuffer = ""
            pinyinCandidates = emptyList()
            pinyinSelectedIndex = 0
            pinyinWindowStart = 0
            // 英語モードの状態もリセット
            englishSmartCaps = false
            englishShiftNext = false
            englishCapsLock = false
            // 韓国語 자모 モードもリセット
            koreanJamoLock = false
            koreanSmartJamo = false
            resetJamoState()
            // Devanagari もリセット
            devanagariComposer.commit()
            devaNonVargaActive = false
            prevDevaDpadDir = DevaDpadDir.NONE
            prevDevaFace = null
            // rStickDown 多段タップもリセット
            rStickDownTapCount = 0
            rStickDownLastTime = 0
        }

        // Back: 操作モード切替
        if (backEdge && !gp.start) {
            operationMode = when (operationMode) {
                GamepadOperationMode.NORMAL -> GamepadOperationMode.TEXT_OPERATION
                GamepadOperationMode.TEXT_OPERATION -> GamepadOperationMode.NORMAL
            }
        }

        // --- 右スティック ← : 削除（中国語バッファ中はバッファ削除、韓国語は合成状態を確定）---
        if (rStickLeft && !prevRStickLeft) {
            if ((currentMode == GamepadInputMode.CHINESE_SIMPLIFIED ||
                        currentMode == GamepadInputMode.CHINESE_TRADITIONAL) &&
                pinyinBuffer.isNotEmpty()
            ) {
                pinyinBuffer = pinyinBuffer.dropLast(1)
                if (currentMode == GamepadInputMode.CHINESE_TRADITIONAL) {
                    zhuyinDisplayBuffer = zhuyinDisplayBuffer.dropLast(1)
                }
                if (pinyinBuffer.isEmpty()) {
                    pinyinCandidates = emptyList()
                    pinyinSelectedIndex = 0
                    pinyinWindowStart = 0
                } else {
                    lookupPinyinCandidates()
                }
            } else {
                // 日本語変換中は変換をキャンセル、バッファ末尾を削る
                if (currentMode == GamepadInputMode.JAPANESE) {
                    if (isConverting) {
                        cancelConversion()
                    } else if (hiraganaBuffer.isNotEmpty()) {
                        hiraganaBuffer = hiraganaBuffer.dropLast(1)
                        onDeleteBackward?.invoke()
                    } else {
                        onDeleteBackward?.invoke()
                    }
                } else if (currentMode == GamepadInputMode.KOREAN) {
                    // 韓国語: 合成中の字を削除する場合、合成状態もクリアしないと
                    // 残ったバッファで次の入力が誤って合成される。
                    // 자모 모드中の削除は Smart Jamo を解除しつつ連打基準もリセット。
                    commitKoreanComposer()
                    releaseKoreanSmartJamo()
                    resetJamoState()
                    onDeleteBackward?.invoke()
                } else if (currentMode == GamepadInputMode.DEVANAGARI) {
                    // Devanagari: composer の buffer を 1 文字 backspace し
                    // 表示も 1 文字削除。composer backspace が state を再計算する。
                    val out = devanagariComposer.backspace()
                    if (out != null) {
                        onDirectInsert?.invoke(out.text, out.replaceCount)
                    } else {
                        onDeleteBackward?.invoke()
                    }
                } else {
                    onDeleteBackward?.invoke()
                }
            }
        }

        // --- 右スティック ↓ : 言語別の句読点サイクル（中国語候補中は次候補）---
        if (rStickDown && !prevRStickDown) {
            val chineseCandidateMode = (currentMode == GamepadInputMode.CHINESE_SIMPLIFIED ||
                    currentMode == GamepadInputMode.CHINESE_TRADITIONAL) &&
                    pinyinCandidates.isNotEmpty()
            if (chineseCandidateMode) {
                if (pinyinSelectedIndex < pinyinCandidates.size - 1) {
                    pinyinSelectedIndex++
                    updatePinyinWindow()
                }
            } else {
                // 言語別の前処理（iOS と同一）
                when (currentMode) {
                    GamepadInputMode.KOREAN -> {
                        // 合成中の音節を確定して状態リセット
                        koreanComposer.commit()
                    }
                    GamepadInputMode.ENGLISH -> {
                        // smart caps を解除（句読点後の自動大文字化を無効化）
                        englishSmartCaps = false
                    }
                    GamepadInputMode.CHINESE_SIMPLIFIED,
                    GamepadInputMode.CHINESE_TRADITIONAL -> {
                        // 候補が無い状態でバッファだけ残っている場合はクリア
                        if (pinyinBuffer.isNotEmpty()) {
                            pinyinBuffer = ""
                            zhuyinDisplayBuffer = ""
                            pinyinSelectedIndex = 0
                            pinyinWindowStart = 0
                        }
                    }
                    GamepadInputMode.DEVANAGARI -> {
                        // cluster を確定して next input を新 cluster として扱う
                        devanagariComposer.commit()
                    }
                    else -> {}
                }

                val elapsed = now - rStickDownLastTime
                if (elapsed < rStickTapWindow) {
                    rStickDownTapCount++
                } else {
                    rStickDownTapCount = 1
                }
                rStickDownLastTime = now

                // 言語別のサイクル（iOS と同一）
                when (currentMode) {
                    GamepadInputMode.JAPANESE -> {
                        // 句読点サイクル前に composing を自動確定（句読点は文節境界のため）
                        if (rStickDownTapCount == 1 && (isConverting || hiraganaBuffer.isNotEmpty())) {
                            onFinalizeComposing?.invoke()
                            resetComposingState()
                        }
                        when (rStickDownTapCount) {
                            // 、 → 。 → スペース
                            1 -> onDirectInsert?.invoke("、", 0)
                            2 -> onDirectInsert?.invoke("。", 1)
                            else -> {
                                onDirectInsert?.invoke(" ", 1)
                                rStickDownTapCount = 0
                                rStickDownLastTime = 0
                            }
                        }
                    }
                    GamepadInputMode.ENGLISH -> when (rStickDownTapCount) {
                        // space → . → ,
                        1 -> onDirectInsert?.invoke(" ", 0)
                        2 -> onDirectInsert?.invoke(".", 1)
                        else -> {
                            onDirectInsert?.invoke(",", 1)
                            rStickDownTapCount = 0
                            rStickDownLastTime = 0
                        }
                    }
                    GamepadInputMode.KOREAN -> {
                        // 句読点やスペースの前に合成中の音節を確定。
                        // 자모 모드の Smart Jamo はここで自動解除（連打の流れを断つ）
                        commitKoreanComposer()
                        releaseKoreanSmartJamo()
                        resetJamoState()
                        when (rStickDownTapCount) {
                            // space → .
                            1 -> onDirectInsert?.invoke(" ", 0)
                            else -> {
                                onDirectInsert?.invoke(".", 1)
                                rStickDownTapCount = 0
                                rStickDownLastTime = 0
                            }
                        }
                    }
                    GamepadInputMode.CHINESE_SIMPLIFIED,
                    GamepadInputMode.CHINESE_TRADITIONAL -> when (rStickDownTapCount) {
                        // ， → 。 → スペース
                        1 -> onDirectInsert?.invoke("，", 0)
                        2 -> onDirectInsert?.invoke("。", 1)
                        else -> {
                            onDirectInsert?.invoke(" ", 1)
                            rStickDownTapCount = 0
                            rStickDownLastTime = 0
                        }
                    }
                    GamepadInputMode.DEVANAGARI -> when (rStickDownTapCount) {
                        // Devanagari: ␣ → । (danda) → ॥ (double danda)
                        1 -> onDirectInsert?.invoke(" ", 0)
                        2 -> onDirectInsert?.invoke("।", 1)
                        else -> {
                            onDirectInsert?.invoke("॥", 1)
                            rStickDownTapCount = 0
                            rStickDownLastTime = 0
                        }
                    }
                }
            }
        }

        // --- 左スティック: 日本語変換/中国語候補選択/カーソル移動 ---
        val inJapanese = currentMode == GamepadInputMode.JAPANESE
        val inChinese = currentMode == GamepadInputMode.CHINESE_SIMPLIFIED ||
                currentMode == GamepadInputMode.CHINESE_TRADITIONAL
        when {
            inJapanese && isConverting -> {
                // 変換中: 上下で候補サイクル、左右でフォーカス文節切替
                if (lStickDown && !prevLStickDown) cycleCandidate(+1)
                if (lStickUp && !prevLStickUp) cycleCandidate(-1)
                if (lStickLeft && !prevLStickLeft) focusPrevBunsetsu()
                if (lStickRight && !prevLStickRight) focusNextBunsetsu()
            }
            inJapanese && hiraganaBuffer.isNotEmpty() -> {
                // 未変換バッファあり: 下で変換開始、上は no-op、左右はバッファ確定してからカーソル移動
                if (lStickDown && !prevLStickDown) startConversion()
                if ((lStickLeft && !prevLStickLeft) || (lStickRight && !prevLStickRight)) {
                    resetComposingState()
                    if (lStickLeft && !prevLStickLeft) onCursorMove?.invoke(-1)
                    if (lStickRight && !prevLStickRight) onCursorMove?.invoke(1)
                }
            }
            inChinese && pinyinCandidates.isNotEmpty() -> {
                // 中国語候補選択: 上下で候補サイクル（ページ追従）
                if (lStickDown && !prevLStickDown) cyclePinyinCandidate(+1)
                if (lStickUp && !prevLStickUp) cyclePinyinCandidate(-1)
                // 左右は未使用（将来: ページ単位ジャンプに使える）
            }
            else -> {
                // カーソル移動の前に韓国語の合成状態を確定 + Smart Jamo 解除
                val anyLStickEdge = (lStickLeft && !prevLStickLeft) ||
                    (lStickRight && !prevLStickRight) ||
                    (lStickUp && !prevLStickUp) ||
                    (lStickDown && !prevLStickDown)
                if (anyLStickEdge) {
                    if (currentMode == GamepadInputMode.KOREAN) {
                        commitKoreanComposer()
                        releaseKoreanSmartJamo()
                        resetJamoState()
                    }
                }
                // Devanagari モードでは LS 方向は varga 選択に使うので、カーソル移動には使わない。
                // LS 方向入力時はカーソル移動をスキップ（何もしない）。
                if (currentMode != GamepadInputMode.DEVANAGARI) {
                    if (lStickLeft && !prevLStickLeft) onCursorMove?.invoke(-1)
                    if (lStickRight && !prevLStickRight) onCursorMove?.invoke(1)
                    if (lStickUp && !prevLStickUp) onCursorMoveVertical?.invoke(-1)
                    if (lStickDown && !prevLStickDown) onCursorMoveVertical?.invoke(1)
                }
            }
        }

        // --- モード別入力処理 ---
        when (currentMode) {
            GamepadInputMode.JAPANESE -> handleJapaneseInput(gp, row, vowel, ltNow, rtNow, rStickUp, rStickRight, now)
            GamepadInputMode.ENGLISH -> handleEnglishInput(gp, row, vowel, ltNow, rtNow, rStickUp, rStickRight, now)
            GamepadInputMode.KOREAN -> handleKoreanInput(gp, row, vowel, ltNow, rtNow, rStickUp, rStickRight, now)
            GamepadInputMode.CHINESE_SIMPLIFIED -> handleChineseInput(gp, row, vowel, rStickUp, rStickRight, now)
            GamepadInputMode.CHINESE_TRADITIONAL -> handleZhuyinInput(gp, row, vowel, rStickUp, rStickRight, now)
            GamepadInputMode.DEVANAGARI -> handleDevanagariInput(
                gp, ltNow, rtNow,
                rStickUp, rStickDown, rStickLeft, rStickRight,
                lStickUp, lStickDown, lStickLeft, lStickRight,
                now,
            )
        }

        // --- LS クリック: 確定/改行（日本語変換中は確定、中国語候補あれば候補確定）---
        if (prevLS && !gp.lsClick) {
            val isChinese = currentMode == GamepadInputMode.CHINESE_SIMPLIFIED ||
                    currentMode == GamepadInputMode.CHINESE_TRADITIONAL
            when {
                currentMode == GamepadInputMode.JAPANESE && isConverting -> {
                    commitConversion()
                }
                currentMode == GamepadInputMode.JAPANESE && hiraganaBuffer.isNotEmpty() -> {
                    // 変換未発動で LS が押された = ひらがなをそのまま確定
                    resetComposingState()
                }
                isChinese && pinyinCandidates.isNotEmpty() -> {
                    val candidate = pinyinCandidates.getOrNull(pinyinSelectedIndex)
                    if (candidate != null) {
                        onDirectInsert?.invoke(candidate.word, 0)
                        pinyinBuffer = ""
                        zhuyinDisplayBuffer = ""
                        pinyinCandidates = emptyList()
                        pinyinSelectedIndex = 0
                        pinyinWindowStart = 0
                    }
                }
                currentMode == GamepadInputMode.DEVANAGARI -> {
                    // LS click = 非 varga サブレイヤーをトグル。
                    // 注意: composer は commit しない。cluster 途中で子音クラスを
                    // 切替える必要がある（例: स्त्य = sibilant → varga → semivowel）
                    // ので、halant 自動挿入を効かせるには state 保持が必須。
                    devaNonVargaActive = !devaNonVargaActive
                }
                else -> {
                    // 改行の前に韓国語の合成状態を確定 + Smart Jamo 解除
                    if (currentMode == GamepadInputMode.KOREAN) {
                        commitKoreanComposer()
                        releaseKoreanSmartJamo()
                        resetJamoState()
                    }
                    onConfirmOrNewline?.invoke()
                }
            }
        }

        // --- RS クリック: composing 全削除（日本語）/ 中国語バッファクリア ---
        if (prevRS && !gp.rsClick) {
            val isChinese = currentMode == GamepadInputMode.CHINESE_SIMPLIFIED ||
                    currentMode == GamepadInputMode.CHINESE_TRADITIONAL
            when {
                currentMode == GamepadInputMode.JAPANESE && isConverting -> {
                    // 変換中: 表示中の surface を全削除 + composing リセット
                    onDirectInsert?.invoke("", currentCandidateLength)
                    resetComposingState()
                }
                currentMode == GamepadInputMode.JAPANESE && hiraganaBuffer.isNotEmpty() -> {
                    // 未変換: テキストフィールド上の原かなも全削除
                    onDirectInsert?.invoke("", hiraganaBuffer.length)
                    resetComposingState()
                }
                isChinese && pinyinBuffer.isNotEmpty() -> {
                    pinyinBuffer = ""
                    zhuyinDisplayBuffer = ""
                    pinyinCandidates = emptyList()
                    pinyinSelectedIndex = 0
                    pinyinWindowStart = 0
                }
            }
        }

        // --- 前回状態を保存 ---
        prevRow = row
        prevVowel = vowel
        prevLT = ltNow
        prevRT = rtNow
        prevRB = gp.rb
        prevLB = gp.lb
        prevDpadUp = gp.dpadUp
        prevDpadDown = gp.dpadDown
        prevDpadLeft = gp.dpadLeft
        prevDpadRight = gp.dpadRight
        prevRStickUp = rStickUp
        prevRStickDown = rStickDown
        prevRStickLeft = rStickLeft
        prevRStickRight = rStickRight
        prevLStickUp = lStickUp
        prevLStickDown = lStickDown
        prevLStickLeft = lStickLeft
        prevLStickRight = lStickRight
        prevBack = gp.back
        prevStart = gp.start
        prevLS = gp.lsClick
        prevRS = gp.rsClick
    }

    // MARK: - 母音解決

    private fun resolveVowel(gp: GamepadSnapshot): VowelButton? {
        // RB = あ段、フェイスボタン = い/う/え/お段
        if (gp.rb) return VowelButton.A
        if (gp.buttonX) return VowelButton.I
        if (gp.buttonY) return VowelButton.U
        if (gp.buttonB) return VowelButton.E
        if (gp.buttonA) return VowelButton.O
        return null
    }


    // MARK: - 日本語入力

    private fun handleJapaneseInput(
        gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
        ltNow: Boolean, rtNow: Boolean, rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        val vowelNow = vowel != null

        // 子音ボタンのカウント
        val consonantCount = listOf(gp.dpadUp, gp.dpadDown, gp.dpadLeft, gp.dpadRight, gp.lb)
            .count { it }

        // --- chord: 子音+母音同時押し → かな入力 ---
        //
        // 前提: D-pad 同士（←↑→↓）とフェイスボタン同士（X/Y/B/A）は
        // それぞれ物理的に排他（同時押ししない）。
        // この前提のもと、within-family の切り替え（D-pad 方向変更 or
        // 母音変更）は「前を離して次を押した」= 別々の文字として追記。
        // chord window による置換が発動するのは、異ファミリー要素が
        // 後から追加された場合（例: 母音先行 → D-pad 追加）のみ。
        if (vowelNow) {
            val v = vowel!!.index
            val kana = KANA_TABLE[row][v]
            val rowChanged = row != prevRow
            val vowelChanged = vowel != prevVowel
            val consonantReleased = rowChanged && consonantCount < prevConsonantCount
            // 子音数が同じで row だけ変わった = D-pad 方向内のスワップ
            val dpadDirSwap = rowChanged && consonantCount > 0 &&
                    consonantCount == prevConsonantCount

            when {
                prevVowel == null -> {
                    // 母音の最初の押下: 新規 eager 出力
                    emitKana(kana, 0)
                    eagerChar = kana
                    eagerCharLen = kana.length
                    eagerTime = now
                }
                vowelChanged -> {
                    // 母音が変わった = 前の母音ボタンを離して次を押した（前提）
                    // → 別文字として追記
                    emitKana(kana, 0)
                    eagerChar = kana
                    eagerCharLen = kana.length
                    eagerTime = now
                }
                consonantReleased -> {
                    // 子音リリース: 現在の文字を維持（何もしない）
                }
                dpadDirSwap -> {
                    // D-pad 方向内スワップ（例: ↓→↑）= 前を離して次を押した
                    // → 別文字として追記
                    emitKana(kana, 0)
                    eagerChar = kana
                    eagerCharLen = kana.length
                    eagerTime = now
                }
                rowChanged -> {
                    // 子音が追加された（異ファミリー要素の合成）= chord refinement
                    // window 内なら置換、超過なら新規
                    val elapsed = now - eagerTime
                    if (elapsed < chordWindow) {
                        emitKana(kana, eagerCharLen)
                    } else {
                        emitKana(kana, 0)
                    }
                    eagerChar = kana
                    eagerCharLen = kana.length
                    eagerTime = now
                }
                // else: row も vowel も変わらない → 何もしない
            }
        }

        // --- 全リリース → eager 確定 ---
        if (!vowelNow && prevVowel != null) {
            eagerChar = null
        }

        // --- LT/RT 状態追跡 ---
        if (ltNow && !prevLT) { rtDuringLT = false }
        if (ltNow && rtNow) { rtDuringLT = true; rtUsed = true }

        // --- LT リリース: 拗音後置シフト / LT+RT=っ ---
        if (!ltNow && prevLT) {
            if (rtDuringLT) {
                // LT+RT → っ（「っ」は LT+RT 専用）
                emitKana("っ", 0)
                // この直後の RT のジッター/バウンドで「ん」が誤発火しないよう、
                // 一定期間「ん」出力を抑止する
                nBlockUntil = now + nBlockAfterTsu
            } else {
                // LT 単独 → 拗音/小書き変換。マッピングがなければ何もしない
                val lastChar = onGetLastCharacter?.invoke()
                val mapped = lastChar?.let { YOUON_POSTSHIFT_MAP[it] }
                if (mapped != null) {
                    emitKana(mapped, 1)
                }
                // マッピングなし or 文字なし → 何もしない（「っ」を出したい場合は LT+RT を使う）
            }
            rtDuringLT = false
        }

        // --- RT リリース: 「ん」（LT中でなく、未消費で、抑止期間外のみ）---
        if (rtNow && !prevRT) { rtUsed = false }
        if (!rtNow && prevRT) {
            if (!rtUsed && !ltNow && now >= nBlockUntil) {
                emitKana("ん", 0)
            }
            rtUsed = false
        }

        // --- 右スティック→: 長音「ー」 ---
        if (rStickRight && !prevRStickRight) {
            emitKana("ー", 0)
        }

        // --- 右スティック↑: 濁点/半濁点トグル（清音→濁音→半濁音→清音）---
        if (rStickUp && !prevRStickUp) {
            val lastChar = onGetLastCharacter?.invoke()
            if (lastChar != null) {
                val dakuten = DAKUTEN_MAP[lastChar]
                if (dakuten != null) {
                    // 清音→濁音
                    emitKana(dakuten.toString(), 1)
                } else {
                    val fromDakuten = DAKUTEN_REVERSE[lastChar]
                    if (fromDakuten != null) {
                        // 濁音→半濁音（可能なら）or 清音に戻す
                        val handakuten = HANDAKUTEN_MAP[fromDakuten]
                        if (handakuten != null) {
                            emitKana(handakuten.toString(), 1)
                        } else {
                            emitKana(fromDakuten.toString(), 1)
                        }
                    } else {
                        val fromHandakuten = HANDAKUTEN_REVERSE[lastChar]
                        if (fromHandakuten != null) {
                            // 半濁音→清音
                            emitKana(fromHandakuten.toString(), 1)
                        }
                    }
                }
            }
        }

        prevConsonantCount = consonantCount
    }

    // MARK: - 日本語変換（composing / candidate selection）

    /// 日本語かなをテキストフィールドに出力しつつ、composing バッファに反映する。
    /// 変換中の場合は先に候補を確定してから新しいバッファを開始する。
    ///
    /// IME 向けの注意: `hiraganaBuffer` を onDirectInsert より「前」に更新するので、
    /// callback 受け手は `inputManager.hiraganaBuffer` を読めば最新状態を取れる
    /// （これに依存して IME 側が composing region の全文を組み立てる）。
    private fun emitKana(kana: String, replaceCount: Int) {
        if (isConverting) {
            // 変換中に新たなかなが来た = 現候補を確定してから新しいバッファを開始
            onFinalizeComposing?.invoke()
            resetComposingState()
            hiraganaBuffer = kana
            onDirectInsert?.invoke(kana, 0)
            return
        }
        // バッファ更新を「先に」実施してから callback を呼ぶ
        if (replaceCount > 0) {
            if (replaceCount <= hiraganaBuffer.length) {
                hiraganaBuffer = hiraganaBuffer.dropLast(replaceCount) + kana
            } else {
                // バッファ不整合。リセットして新規扱い
                hiraganaBuffer = kana
            }
        } else {
            hiraganaBuffer += kana
        }
        onDirectInsert?.invoke(kana, replaceCount)
    }

    /// composing バッファとは無関係にテキストを挿入する（確定済みテキストを割り込ませる場合用）。
    /// 呼び出し側でバッファの整合性を考慮すること。
    private fun emitCommitted(text: String) {
        // 変換中なら先に確定
        if (isConverting) resetComposingState()
        hiraganaBuffer = ""
        onDirectInsert?.invoke(text, 0)
    }

    /// 現在のひらがなバッファをかな漢字変換（文節分割）し、候補選択モードに入る。
    private fun startConversion() {
        val converter = japaneseConverter ?: return
        val scope = coroutineScope ?: return
        if (hiraganaBuffer.isEmpty() || !converter.isReady) return
        val bufferSnapshot = hiraganaBuffer
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                converter.convertBunsetsu(bufferSnapshot)
            }
            if (hiraganaBuffer != bufferSnapshot) return@launch
            if (result == null || result.size == 0) return@launch

            bunsetsuReadings = result.readings
            bunsetsuCandidates = result.candidates
            bunsetsuSelectedIndices = List(result.size) { 0 }
            focusedBunsetsuIndex = 0
            japaneseWindowStart = 0

            // 全文を組み立てて置換
            val fullSurface = result.candidates.joinToString("") { it.first().surface }
            onDirectInsert?.invoke(fullSurface, bufferSnapshot.length)
            currentCandidateLength = fullSurface.length
            isConverting = true
        }
    }

    /// フォーカス中の文節の候補をサイクル。delta=+1 で次、-1 で前。
    private fun cycleCandidate(delta: Int) {
        if (!isConverting) return
        val focused = focusedBunsetsuIndex
        val candidates = bunsetsuCandidates.getOrNull(focused) ?: return
        if (candidates.isEmpty()) return
        val currentIdx = bunsetsuSelectedIndices.getOrNull(focused) ?: 0
        val newIdx = ((currentIdx + delta) % candidates.size + candidates.size) % candidates.size
        bunsetsuSelectedIndices = bunsetsuSelectedIndices.toMutableList().also { it[focused] = newIdx }
        updateTextFieldFromBunsetsu()
        // ウィンドウはページ単位でジャンプ（見通し優先）
        // 選択 index を含むページの先頭にウィンドウを合わせる
        japaneseWindowStart = (newIdx / japaneseWindowSize) * japaneseWindowSize
    }

    /// フォーカスを前の文節に移す
    private fun focusPrevBunsetsu() {
        if (!isConverting) return
        if (focusedBunsetsuIndex <= 0) return
        focusedBunsetsuIndex -= 1
        japaneseWindowStart = 0
    }

    /// フォーカスを次の文節に移す
    private fun focusNextBunsetsu() {
        if (!isConverting) return
        if (focusedBunsetsuIndex >= bunsetsuCandidates.size - 1) return
        focusedBunsetsuIndex += 1
        japaneseWindowStart = 0
    }

    /// 各文節の現在選択 surface を連結してテキストフィールドに反映
    private fun updateTextFieldFromBunsetsu() {
        val fullSurface = bunsetsuCandidates.mapIndexed { i, cands ->
            val idx = bunsetsuSelectedIndices.getOrNull(i) ?: 0
            cands.getOrNull(idx)?.surface ?: ""
        }.joinToString("")
        onDirectInsert?.invoke(fullSurface, currentCandidateLength)
        currentCandidateLength = fullSurface.length
    }

    /// 変換を確定する（候補をそのまま残し、composing 状態をクリア）
    private fun commitConversion() {
        onFinalizeComposing?.invoke()
        recordLearningFromCurrentBunsetsu()
        resetComposingState()
    }

    /// 現在の文節編集状態から確定された 読み→surface のペアを学習データに書き込む。
    /// ひらがなそのまま確定したものは記録しない（LearnRepository.record 側でガード）。
    /// DB 書き込みは Dispatchers.IO で fire-and-forget。UI は阻害しない。
    private fun recordLearningFromCurrentBunsetsu() {
        val converter = japaneseConverter ?: return
        val scope = coroutineScope ?: return
        val readings = bunsetsuReadings
        val selections = bunsetsuSelectedIndices
        val candidates = bunsetsuCandidates
        if (readings.isEmpty()) return

        // 書き込み対象のスナップショットを作ってから非同期で流す。
        // 1. 各文節単位の (reading, surface)
        // 2. 文節が 2 つ以上なら、連結した (fullReading, fullSurface) も追加記録
        val pairs = buildList {
            for (i in readings.indices) {
                val reading = readings.getOrNull(i) ?: continue
                val idx = selections.getOrNull(i) ?: 0
                val surface = candidates.getOrNull(i)?.getOrNull(idx)?.surface ?: continue
                add(reading to surface)
            }
            if (readings.size >= 2) {
                val fullReading = readings.joinToString("")
                val fullSurface = candidates.mapIndexed { i, cands ->
                    val idx = selections.getOrNull(i) ?: 0
                    cands.getOrNull(idx)?.surface ?: ""
                }.joinToString("")
                if (fullReading.isNotEmpty() && fullSurface.isNotEmpty()) {
                    add(fullReading to fullSurface)
                }
            }
        }
        if (pairs.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            for ((reading, surface) in pairs) {
                try {
                    converter.recordLearning(reading, surface)
                } catch (t: Throwable) {
                    android.util.Log.w("GamepadInputManager", "learning write failed: $reading -> $surface", t)
                }
            }
        }
    }

    /// 変換をキャンセルし、元のひらがなバッファに戻す
    private fun cancelConversion() {
        if (!isConverting) return
        // 候補をひらがなバッファに戻す
        onDirectInsert?.invoke(hiraganaBuffer, currentCandidateLength)
        // バッファは残す（再変換を可能にする）
        bunsetsuReadings = emptyList()
        bunsetsuCandidates = emptyList()
        bunsetsuSelectedIndices = emptyList()
        focusedBunsetsuIndex = 0
        currentCandidateLength = 0
        japaneseWindowStart = 0
        isConverting = false
    }

    /// composing 状態を完全リセット（バッファもクリア）
    private fun resetComposingState() {
        hiraganaBuffer = ""
        isConverting = false
        bunsetsuReadings = emptyList()
        bunsetsuCandidates = emptyList()
        bunsetsuSelectedIndices = emptyList()
        focusedBunsetsuIndex = 0
        currentCandidateLength = 0
        japaneseWindowStart = 0
    }

    // MARK: - 英語入力

    private fun handleEnglishInput(
        gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
        ltNow: Boolean, rtNow: Boolean,
        rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        // --- 右スティック↑: アポストロフィ「'」 ---
        if (rStickUp && !prevRStickUp) {
            onDirectInsert?.invoke("'", 0)
        }
        // --- 右スティック→: スラッシュ「/」 ---
        if (rStickRight && !prevRStickRight) {
            onDirectInsert?.invoke("/", 0)
            englishSmartCaps = false
        }

        val vowelNow = vowel != null

        // LT: 短押し=ShiftNext / ダブルタップ=Smart Caps / 長押し=Caps Lock トグル
        if (ltNow && !prevLT) {
            ltPressTime = now
            englishLTHolding = true
        }

        // 長押し閾値到達中にリアルタイムで Caps Lock 切替（iOS と同挙動）
        if (ltNow && englishLTHolding && (now - ltPressTime) >= longPressThreshold) {
            englishCapsLock = !englishCapsLock
            englishSmartCaps = false
            englishLTHolding = false  // 再発火防止
        }

        if (!ltNow && prevLT) {
            if (englishLTHolding) {
                // 長押し閾値未達 → 短押し判定
                if ((now - lastLTReleaseTime) < doubleTapWindow) {
                    // 短押し2度押し → Smart Caps
                    englishSmartCaps = true
                    englishShiftNext = false
                } else {
                    // 単なる短押し → 次の1文字だけ大文字
                    englishShiftNext = true
                }
            }
            englishLTHolding = false
            lastLTReleaseTime = now
        }

        // 母音ボタンで文字入力
        if (vowelNow && prevVowel == null) {
            val v = vowel!!.index
            var char = ENGLISH_TABLE[row][v]
            if (char.isNotEmpty()) {
                if (englishCapsLock || englishSmartCaps || englishShiftNext) {
                    char = char.uppercase()
                }
                onDirectInsert?.invoke(char, 0)
                englishShiftNext = false
                // Smart Caps は文字入力では解除しない（句読点/スペースで自動解除）
            }
        }

        // RT: 数字 0 (ショートカット)
        if (rtNow && !prevRT) {
            onDirectInsert?.invoke("0", 0)
        }
    }

    // MARK: - 韓国語入力

    /// 韓国語の合成状態を確定し、transient フラグをリセットする。
    /// 句読点/削除/改行/カーソル移動など、合成の流れを断ち切るアクションの前に呼ぶ。
    private fun commitKoreanComposer() {
        koreanComposer.commit()
        patchimRollbackActive = false
        patchimRollbackCoda = null
        allReleasedSinceSyllable = true
    }

    /// 자모 모드（子音・母音単体入力モード）中か
    private val isKoreanJamoMode: Boolean
        get() = koreanJamoLock || koreanSmartJamo

    /// 자모 모드用の内部状態をクリアする
    private fun resetJamoState() {
        lastJamoText = null
        lastJamoOnsetIndex = null
        jamoEagerText = null
        jamoLbOnlyPending = false
    }

    /// LT 長押し → Jamo Lock トグル。
    /// ON 時は composing を確定、Smart Jamo は明示的にクリア（Lock が優先）
    private fun toggleKoreanJamoLock() {
        if (koreanJamoLock) {
            koreanJamoLock = false
            koreanSmartJamo = false
            resetJamoState()
        } else {
            commitKoreanComposer()
            koreanJamoLock = true
            koreanSmartJamo = false
            resetJamoState()
        }
    }

    /// LT 2連続短押し → Smart Jamo トグル。
    /// Lock が ON の間は no-op（Lock を優先、exit は長押しに統一）
    private fun toggleKoreanSmartJamo() {
        if (koreanJamoLock) return
        if (koreanSmartJamo) {
            koreanSmartJamo = false
            resetJamoState()
        } else {
            commitKoreanComposer()
            koreanSmartJamo = true
            resetJamoState()
        }
    }

    /// 空白・句読点・削除・カーソル移動など、「jamo 連打の流れを断ち切る」
    /// アクションで Smart Jamo を解除する（Lock はそのまま維持）
    private fun releaseKoreanSmartJamo() {
        if (koreanSmartJamo) {
            koreanSmartJamo = false
            resetJamoState()
        }
    }

    private fun handleKoreanInput(
        gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
        ltNow: Boolean, rtNow: Boolean,
        rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        val vowelNow = vowel != null
        val consonantCount = listOf(gp.dpadUp, gp.dpadDown, gp.dpadLeft, gp.dpadRight, gp.lb).count { it }
        val prevConsonantCountLocal = listOf(prevDpadUp, prevDpadDown, prevDpadLeft, prevDpadRight, prevLB).count { it }
        val dpadActive = consonantCount > 0
        val prevDpadActive = prevConsonantCountLocal > 0

        // === LT 処理（자모 モード切替 + ㅇ받침）===
        // 英語モードと対称: 長押し=Jamo Lock トグル、2連続短押し=Smart Jamo トグル、
        // 単押し=ㅇ받침（条件満たす時のみ）。合成/자모 両モードで共通処理。
        handleKoreanLT(vowelNow, dpadActive, ltNow, now)

        // === 자모 モード中は合成をバイパスして子音/母音単体入力 ===
        if (isKoreanJamoMode) {
            handleKoreanJamoInput(row, vowel, rtNow, rStickUp, rStickRight, now)
            return
        }

        // === (1) 받침巻き戻し: 母音エッジで直前の単독받침を取り消す ===
        // 誤って patchim と解釈された子音を、母音到着で「新音節の onset」として
        // 再解釈する。겹받침（patchimRollbackCoda != null）は巻き戻さない。
        if (vowelNow && patchimRollbackActive) {
            if (patchimRollbackCoda == null) {
                koreanComposer.revertCoda(null)
                // coda なしの状態で音節を再描画
                val onset = koreanComposer.currentOnset
                val nucleus = koreanComposer.currentNucleus
                if (onset != null && nucleus != null) {
                    val code = 0xAC00 + (onset * 21 + nucleus) * 28
                    onDirectInsert?.invoke(code.toChar().toString(), 1)
                }
            }
            patchimRollbackActive = false
        }

        // === (2) 子音+母音 → 音節入力（allReleasedSinceSyllable は要求しない）===
        if (vowelNow) {
            val onsetIdx = KOREAN_ONSET_FOR_ROW[row]
            val v = vowel!!.index
            val nucleusIdx = if (rtNow) KOREAN_NUCLEUS_SHIFTED[v] else KOREAN_NUCLEUS_BASE[v]
            val rowChanged = row != prevRow
            val vowelChanged = vowel != prevVowel

            if (prevVowel == null) {
                // 母音の新規押下 → 新音節開始。eager output として記録
                val output = koreanComposer.inputSyllable(onsetIdx, nucleusIdx)
                onDirectInsert?.invoke(output.text, output.replaceCount)
                eagerChar = output.text
                eagerCharLen = output.text.length
                eagerTime = now
                allReleasedSinceSyllable = false
                if (rtNow) rtUsed = true
            } else if (rowChanged || vowelChanged) {
                val consonantReleased = rowChanged && consonantCount < prevConsonantCountLocal
                if (!consonantReleased) {
                    // 母音先行→子音後着 or 母音切替 → 新しい音節で eager を置換
                    val output = koreanComposer.inputSyllable(onsetIdx, nucleusIdx)
                    if (eagerChar != null && (now - eagerTime) < chordWindow) {
                        onDirectInsert?.invoke(output.text, eagerCharLen)
                    } else {
                        onDirectInsert?.invoke(output.text, output.replaceCount)
                    }
                    eagerChar = output.text
                    eagerCharLen = output.text.length
                    eagerTime = now
                    allReleasedSinceSyllable = false
                    if (rtNow) rtUsed = true
                }
            }
        }

        // 母音が全て離されたら eager をクリア（確定扱い）
        if (prevVowel != null && !vowelNow) {
            eagerChar = null
        }

        // === (3) 全リリース / 子音解体の検出 ===
        val allReleased = !dpadActive && !vowelNow && !ltNow
        if (allReleased) {
            allReleasedSinceSyllable = true
            patchimRollbackActive = false
        }
        val consonantReleasing = dpadActive && consonantCount < prevConsonantCountLocal
        // ボタンリリースで 받침 を確定（chord 解体中の誤巻き戻し防止）
        if (consonantReleasing && patchimRollbackActive) {
            patchimRollbackActive = false
        }

        // === (4) 받침入力（即時適用 + 巻き戻し方式）===
        if (!vowelNow && dpadActive && !ltNow && koreanComposer.isComposing) {
            if (!prevDpadActive && allReleasedSinceSyllable) {
                // (a) 全リリース後の最初のエッジ → 받침を即適用
                val prevCoda = koreanComposer.currentCoda
                val codaIdx = KOREAN_CODA_FOR_ROW[row]
                val result = koreanComposer.inputPatchim(codaIdx, row)
                if (result is PatchimResult.Added) {
                    onDirectInsert?.invoke(result.output.text, result.output.replaceCount)
                    patchimRollbackCoda = prevCoda
                    patchimRollbackActive = true
                    allReleasedSinceSyllable = false
                }
            } else if (row != prevRow && patchimRollbackActive) {
                // (b) row 変化 → 巻き戻して新しい row で再適用
                koreanComposer.revertCoda(patchimRollbackCoda)
                val codaIdx = KOREAN_CODA_FOR_ROW[row]
                val result = koreanComposer.inputPatchim(codaIdx, row)
                if (result is PatchimResult.Added) {
                    onDirectInsert?.invoke(result.output.text, result.output.replaceCount)
                }
            }
        }

        // === (5) LT: handleKoreanLT() で処理済み（ㅇ받침は release edge で発火）===

        // === (6) 右スティック↑: 子音サイクル ===
        if (rStickUp && !prevRStickUp && koreanComposer.isComposing) {
            val currentCoda = koreanComposer.currentCoda
            if (currentCoda != null) {
                val nextCoda = KOREAN_CODA_CYCLE[currentCoda]
                if (nextCoda != null) {
                    val output = koreanComposer.modifyCoda(nextCoda)
                    if (output != null) onDirectInsert?.invoke(output.text, output.replaceCount)
                }
            } else {
                val currentOnset = koreanComposer.currentOnset
                if (currentOnset != null) {
                    val nextOnset = KOREAN_ONSET_CYCLE[currentOnset]
                    if (nextOnset != null) {
                        val output = koreanComposer.modifyOnset(nextOnset)
                        if (output != null) onDirectInsert?.invoke(output.text, output.replaceCount)
                    }
                }
            }
        }

        // --- RT 単押しリリース: 複合母音（ㅣ付加）---
        if (!rtNow && prevRT && !rtUsed && koreanComposer.isComposing) {
            val nucleus = koreanComposer.currentNucleus
            if (nucleus != null) {
                val newNucleus = KOREAN_NUCLEUS_ADD_I[nucleus]
                if (newNucleus != null) {
                    val output = koreanComposer.modifyNucleus(newNucleus)
                    if (output != null) onDirectInsert?.invoke(output.text, output.replaceCount)
                }
            }
        }
        if (rtNow && !prevRT) rtUsed = false
        if (rtNow && vowelNow) rtUsed = true

        // --- 右スティック→: 複合母音（ㅏ/ㅓ付加）---
        if (rStickRight && !prevRStickRight && koreanComposer.isComposing) {
            val nucleus = koreanComposer.currentNucleus
            if (nucleus != null) {
                val newNucleus = KOREAN_NUCLEUS_ADD_AEO[nucleus]
                if (newNucleus != null) {
                    val output = koreanComposer.modifyNucleus(newNucleus)
                    if (output != null) onDirectInsert?.invoke(output.text, output.replaceCount)
                }
            }
        }
    }

    // MARK: - 韓国語 LT 処理（ㅇ받침 + 자모 モード切替）

    /// 韓国語モードの LT ボタン処理。
    /// - 長押し（≥ longPressThreshold）: Jamo Lock トグル。ㅇ받침は発火しない。
    /// - 2連続短押し（≤ doubleTapWindow）: Smart Jamo トグル。
    ///   ※ 1 回目の短押しでは ㅇ받침 が発火する副作用あり（ユーザー合意済み）。
    /// - 単独短押し: ㅇ받침（条件を満たす時のみ）。release edge で発火。
    private fun handleKoreanLT(
        vowelNow: Boolean,
        dpadActive: Boolean,
        ltNow: Boolean,
        now: Long,
    ) {
        // 押下エッジ: タイマー開始 + ㅇ받침適格性を capture
        if (ltNow && !prevLT) {
            koreanLTPressTime = now
            koreanLTHolding = true
            // 押下時点で ㅇ받침 の条件を満たしていたか（后の他ボタン絡みで破棄される可能性あり）
            koreanLTShortTapEligible = !vowelNow && !dpadActive &&
                allReleasedSinceSyllable &&
                !isKoreanJamoMode &&
                koreanComposer.isComposing &&
                koreanComposer.currentCoda == null
        }

        // 保持中に他ボタンが絡んだら短押し ㅇ받침 を破棄
        if (ltNow && koreanLTHolding && (vowelNow || dpadActive)) {
            koreanLTShortTapEligible = false
        }

        // 長押し閾値到達: Jamo Lock トグル。ㅇ받침 は発火させない（holding を consume）
        if (ltNow && koreanLTHolding && (now - koreanLTPressTime) >= longPressThreshold) {
            toggleKoreanJamoLock()
            koreanLTHolding = false
            koreanLTShortTapEligible = false
        }

        // リリースエッジ: 短押し（長押し未達）の処理
        if (!ltNow && prevLT) {
            if (koreanLTHolding) {
                val sinceLastRelease = now - lastKoreanLTReleaseTime
                if (sinceLastRelease < doubleTapWindow) {
                    // 2 回目の短押し → Smart Jamo トグル
                    // 1 回目の短押しで発火した ㅇ받침 / ㅇ jamo はテキストに残る（許容された副作用）
                    toggleKoreanSmartJamo()
                } else if (isKoreanJamoMode) {
                    // 자모 모드中: LT 単押し = ㅇ 互換 jamo 単体出力。
                    // Row 0 (ㅇ) は D-pad/LB のどれも押さない位置なので
                    // 通常の chord 経路で入力不可。LT にその役割を与える。
                    val jamoChar = koreanCompatJamoOnset(11).toString()  // ㅇ
                    onDirectInsert?.invoke(jamoChar, 0)
                    lastJamoText = jamoChar
                    lastJamoOnsetIndex = 11
                    jamoEagerText = null
                } else if (koreanLTShortTapEligible) {
                    // 音節合成中: ㅇ받침
                    val codaIdx = KOREAN_CODA_FOR_ROW[0]  // ㅇ
                    val result = koreanComposer.inputPatchim(codaIdx, 0)
                    if (result is PatchimResult.Added) {
                        patchimRollbackCoda = null
                        patchimRollbackActive = true
                        onDirectInsert?.invoke(result.output.text, result.output.replaceCount)
                        allReleasedSinceSyllable = false
                    }
                }
            }
            koreanLTHolding = false
            koreanLTShortTapEligible = false
            lastKoreanLTReleaseTime = now
        }
    }

    // MARK: - 韓国語 자모 모드（子音・母音単体入力モード）

    /// 자모 모드中の入力処理。
    /// - D-pad / LB / LB+D-pad → 子音単体（互換 Jamo）。eager + chord window で LB 後着補正。
    /// - フェイスボタン単独 → 母音単体（基本層 / RT シフト層）。
    /// - 右スティック → : 直前 emit した jamo を 1 字追加（연타）。
    /// - 右スティック ↑ : 直前 emit が子音ならそれを 평→격→경 サイクル。
    private fun handleKoreanJamoInput(
        row: Int, vowel: VowelButton?, rtNow: Boolean,
        rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        val vowelNow = vowel != null

        // D-pad 方向キーのみのアクティビティ（LB を含まない）。LB は修飾子扱いなので
        // 方向キー edge でのみ emit する。
        val snap = currentSnapshot
        val dpadDirActive = snap.dpadUp || snap.dpadDown || snap.dpadLeft || snap.dpadRight
        val prevDpadDirActive = prevDpadUp || prevDpadDown || prevDpadLeft || prevDpadRight
        val dpadDirChanged = (snap.dpadUp != prevDpadUp) ||
            (snap.dpadDown != prevDpadDown) ||
            (snap.dpadLeft != prevDpadLeft) ||
            (snap.dpadRight != prevDpadRight)
        val lbAdded = snap.lb && !prevLB
        val lbReleased = !snap.lb && prevLB

        // --- LB 修飾子処理（「LB 単独 = ㅁ」は LB release 時に遅延発火）---
        // LB press edge (まだ方向キー無し) → 単独ペンディング
        if (lbAdded && !dpadDirActive) {
            jamoLbOnlyPending = true
        }
        // 保持中に方向キーが入ったらペンディング解除（D-pad 側で emit に委ねる）
        if (currentSnapshot.lb && dpadDirActive) {
            jamoLbOnlyPending = false
        }
        // LB release edge: 方向キーを一度も押さずに離された → ㅁ を emit
        if (lbReleased && jamoLbOnlyPending) {
            val onsetIdx = KOREAN_ONSET_FOR_ROW[5]  // ㅁ
            val jamoChar = koreanCompatJamoOnset(onsetIdx).toString()
            onDirectInsert?.invoke(jamoChar, 0)
            lastJamoText = jamoChar
            lastJamoOnsetIndex = onsetIdx
            jamoEagerText = null
            jamoLbOnlyPending = false
        }
        if (lbReleased) {
            jamoLbOnlyPending = false
        }

        // --- 子音 D-pad 方向キー: 押下エッジ or swap/lbAdded で emit ---
        // LB 単独の emit は上で処理済み。ここでは方向キーが絡む場合のみ扱う。
        if (dpadDirActive) {
            val onsetIdx = KOREAN_ONSET_FOR_ROW[row]
            val jamoChar = koreanCompatJamoOnset(onsetIdx).toString()
            when {
                !prevDpadDirActive -> {
                    // 方向キーの新規押下（LB が先行していても row は LB 込みで解決済み）
                    onDirectInsert?.invoke(jamoChar, 0)
                    jamoEagerText = jamoChar
                    jamoEagerLen = jamoChar.length
                    jamoEagerTime = now
                    lastJamoText = jamoChar
                    lastJamoOnsetIndex = onsetIdx
                }
                dpadDirChanged || lbAdded -> {
                    // 方向キー swap、あるいは D-pad 保持中に LB 後着 → chord refine
                    // （LB 単独リリースや他の row 変化では refine しない: 直前の選択を尊重）
                    val elapsed = now - jamoEagerTime
                    if (elapsed < chordWindow && jamoEagerText != null) {
                        onDirectInsert?.invoke(jamoChar, jamoEagerLen)
                    } else {
                        onDirectInsert?.invoke(jamoChar, 0)
                    }
                    jamoEagerText = jamoChar
                    jamoEagerLen = jamoChar.length
                    jamoEagerTime = now
                    lastJamoText = jamoChar
                    lastJamoOnsetIndex = onsetIdx
                }
            }
        }
        // 方向キー全リリースで eager 確定
        if (!dpadDirActive && prevDpadDirActive) {
            jamoEagerText = null
        }

        // --- 母音: フェイスボタンのエッジで emit（単独 jamo なので chord 不要）---
        if (vowelNow && prevVowel == null) {
            val v = vowel!!.index
            val nucleusIdx = if (rtNow) KOREAN_NUCLEUS_SHIFTED[v] else KOREAN_NUCLEUS_BASE[v]
            val jamoChar = koreanCompatJamoNucleus(nucleusIdx).toString()
            onDirectInsert?.invoke(jamoChar, 0)
            lastJamoText = jamoChar
            lastJamoOnsetIndex = null
            // 母音が来たら子音 eager は終了
            jamoEagerText = null
        }

        // --- 右スティック → : 直前 jamo を 1 字追加（ㅋㅋㅋㅋ 連打用）---
        if (rStickRight && !prevRStickRight) {
            val last = lastJamoText
            if (last != null) {
                onDirectInsert?.invoke(last, 0)
                // lastJamoText / lastJamoOnsetIndex はそのまま（連打続行可能）
                // ↑ サイクルを直後に押した場合も直前字母を対象にする
                jamoEagerText = null  // 連打は chord refine の対象外
            }
        }

        // --- 右スティック ↑ : 直前子音を 평→격→경 サイクル ---
        if (rStickUp && !prevRStickUp) {
            val lastOnset = lastJamoOnsetIndex
            val lastLen = lastJamoText?.length ?: 0
            if (lastOnset != null && lastLen > 0) {
                val nextOnset = KOREAN_ONSET_CYCLE[lastOnset]
                if (nextOnset != null) {
                    val newChar = koreanCompatJamoOnset(nextOnset).toString()
                    onDirectInsert?.invoke(newChar, lastLen)
                    lastJamoText = newChar
                    lastJamoOnsetIndex = nextOnset
                    // まだ子音が押されたままなら eager も連動させる
                    if (jamoEagerText != null) {
                        jamoEagerText = newChar
                        jamoEagerLen = newChar.length
                    }
                }
            }
        }
    }

    // MARK: - Devanagari 入力

    /// Devanagari モードの入力処理。
    ///
    /// レイヤー設計（varnamala 時計回り）:
    /// - LS 方向 → varga 選択 (↑क →च ↓ट ←त 中立प)
    /// - D-pad 方向 → varga 内 stop (↑1st →2nd ↓3rd ←4th)
    /// - LB 単独 → 現 varga の鼻音
    /// - Face button → 母音（Y=a, B=i, A=u, X=e）
    ///   合成中なら matra、そうでなければ independent vowel（composer が自動判定）
    /// - RT → halant（明示的 schwa 終端）
    /// - RB → nukta（直前子音に付加）
    /// - RS ↑ → anusvara ↔ chandrabindu トグル
    /// - RS → → 長母音 post-shift（直前 matra / 独立母音を長形に）
    /// - L3 (LS クリック) → 非 varga サブレイヤーをトグル
    ///   ON の間、D-pad は LT OFF: semivowel (य र ल व) / LT ON: sibilant (श ष स ह)
    ///
    /// 各 edge で composer 経由で onDirectInsert を呼ぶ。chord 処理は composer 側の
    /// 「子音→matra は schwa 置換」「子音→子音は virama 自動挿入」で吸収される。
    private fun handleDevanagariInput(
        gp: GamepadSnapshot,
        ltNow: Boolean, rtNow: Boolean,
        rStickUp: Boolean, rStickDown: Boolean, rStickLeft: Boolean, rStickRight: Boolean,
        lStickUp: Boolean, lStickDown: Boolean, lStickLeft: Boolean, lStickRight: Boolean,
        now: Long,
    ) {
        // --- LS 方向 → varga ---
        val lsDir = when {
            lStickUp -> DevaLsDirection.UP
            lStickRight -> DevaLsDirection.RIGHT
            lStickDown -> DevaLsDirection.DOWN
            lStickLeft -> DevaLsDirection.LEFT
            else -> DevaLsDirection.NEUTRAL
        }
        devaLsDir = lsDir
        val varga = resolveDevaVarga(lsDir)

        // --- D-pad 方向（現在）---
        val dpadDir = when {
            gp.dpadUp -> DevaDpadDir.UP
            gp.dpadRight -> DevaDpadDir.RIGHT
            gp.dpadDown -> DevaDpadDir.DOWN
            gp.dpadLeft -> DevaDpadDir.LEFT
            else -> DevaDpadDir.NONE
        }
        // 「前回と違う非 NONE 方向」= 新規押下エッジ（方向 swap も含む）
        val dpadEdge = dpadDir != DevaDpadDir.NONE && dpadDir != prevDevaDpadDir

        // --- LB edge（鼻音）---
        val lbEdge = gp.lb && !prevLB

        // --- Face button（Devanagari 独自配置: RB は nukta に譲るので除外）---
        val face: VowelButton? = when {
            gp.buttonY -> VowelButton.U   // Y (上) = a
            gp.buttonB -> VowelButton.E   // B (右) = i
            gp.buttonA -> VowelButton.O   // A (下) = u
            gp.buttonX -> VowelButton.I   // X (左) = e
            else -> null
        }
        val faceEdge = face != null && face != prevDevaFace

        // === 子音 emission ===
        if (dpadEdge) {
            val consonant: Char? = if (devaNonVargaActive) {
                val nvIdx = resolveDevaNonVargaIndex(dpadDir)
                if (nvIdx != null) {
                    if (ltNow) DEVA_NONVARGA_SIBILANT[nvIdx] else DEVA_NONVARGA_SEMIVOWEL[nvIdx]
                } else null
            } else {
                val stopIdx = resolveDevaStopIndex(dpadDir)
                if (stopIdx != null) DEVA_VARGA_CONSONANTS[varga.index][stopIdx] else null
            }
            if (consonant != null) {
                val out = devanagariComposer.inputConsonant(consonant)
                onDirectInsert?.invoke(out.text, out.replaceCount)
            }
        }

        // === 鼻音（LB 単独）emission ===
        // 非 varga サブレイヤー中は LB を無視（鼻音は varga モードでのみ）
        if (lbEdge && !devaNonVargaActive) {
            val nasal = DEVA_VARGA_CONSONANTS[varga.index][4]
            val out = devanagariComposer.inputConsonant(nasal)
            onDirectInsert?.invoke(out.text, out.replaceCount)
        }

        // === 母音 emission ===
        // 合成中（子音直後）なら matra、そうでなければ independent vowel。
        // composer.inputMatra() が null を返したら independent を試す。
        if (faceEdge && face != null) {
            val matra = DEVA_FACE_VOWEL_MATRA[face]
            val indep = DEVA_FACE_VOWEL_INDEPENDENT[face]
            if (matra != null && indep != null) {
                val matraOut = devanagariComposer.inputMatra(matra)
                if (matraOut != null) {
                    onDirectInsert?.invoke(matraOut.text, matraOut.replaceCount)
                } else {
                    val out = devanagariComposer.inputIndependentVowel(indep)
                    onDirectInsert?.invoke(out.text, out.replaceCount)
                }
            }
        }

        // === RT edge: halant（明示的 schwa 終端）===
        if (rtNow && !prevRT) {
            val out = devanagariComposer.inputHalant()
            if (out != null) onDirectInsert?.invoke(out.text, out.replaceCount)
        }

        // === RB edge: nukta（直前子音に付加）===
        if (gp.rb && !prevRB) {
            val out = devanagariComposer.inputNukta()
            if (out != null) onDirectInsert?.invoke(out.text, out.replaceCount)
        }

        // === RS ↑: anusvara ↔ chandrabindu トグル ===
        if (rStickUp && !prevRStickUp) {
            val out = devanagariComposer.toggleAnusvara()
            if (out != null) onDirectInsert?.invoke(out.text, out.replaceCount)
        }

        // === RS →: 長母音 post-shift（直前 matra / 独立母音を長形に）===
        if (rStickRight && !prevRStickRight) {
            val out = devanagariComposer.applyLongShift()
            if (out != null) onDirectInsert?.invoke(out.text, out.replaceCount)
        }

        // --- 次フレーム用 state 保存 ---
        prevDevaDpadDir = dpadDir
        prevDevaFace = face
    }

    // MARK: - 中国語（簡体字）入力

    private fun handleChineseInput(
        gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
        rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        val vowelNow = vowel != null

        if (vowelNow && prevVowel == null) {
            val v = vowel!!.index
            val char = ENGLISH_TABLE[row][v]
            if (char.isNotEmpty() && !char.first().isDigit()) {
                pinyinBuffer += char
                lookupPinyinCandidates()
            }
        }

        // 右スティック↑: 候補選択（前）
        if (rStickUp && !prevRStickUp && pinyinCandidates.isNotEmpty()) {
            if (pinyinSelectedIndex > 0) {
                pinyinSelectedIndex--
                updatePinyinWindow()
            }
        }

        // 右スティック→: 先頭候補を確定 + 顿号「、」
        if (rStickRight && !prevRStickRight) {
            confirmTopPinyinCandidate()
            onDirectInsert?.invoke("、", 0)
        }

        // RB で確定
        if (gp.rb && !prevRB && pinyinCandidates.isNotEmpty()) {
            val candidate = pinyinCandidates.getOrNull(pinyinSelectedIndex)
            if (candidate != null) {
                onDirectInsert?.invoke(candidate.word, 0)
                pinyinBuffer = ""
                pinyinCandidates = emptyList()
                pinyinSelectedIndex = 0
                pinyinWindowStart = 0
            }
        }
    }

    // MARK: - 中国語（繁體字）注音入力

    private fun handleZhuyinInput(
        gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
        rStickUp: Boolean, rStickRight: Boolean, now: Long,
    ) {
        val vowelNow = vowel != null

        if (vowelNow && prevVowel == null) {
            val v = vowel!!.index
            val zhuyinChar = ZHUYIN_TABLE[row][v]
            if (zhuyinChar.isNotEmpty() && !zhuyinChar.first().isDigit()) {
                zhuyinDisplayBuffer += zhuyinChar
                val pinyinInitial = ZHUYIN_TO_PINYIN_INITIAL[zhuyinChar.first()]
                if (pinyinInitial != null) {
                    pinyinBuffer += pinyinInitial
                }
                lookupPinyinCandidates()
            }
        }

        // 右スティック↑: 候補選択（前）
        if (rStickUp && !prevRStickUp && pinyinCandidates.isNotEmpty()) {
            if (pinyinSelectedIndex > 0) {
                pinyinSelectedIndex--
                updatePinyinWindow()
            }
        }

        // 右スティック→: 先頭候補を確定 + 顿号「、」
        if (rStickRight && !prevRStickRight) {
            confirmTopPinyinCandidate()
            onDirectInsert?.invoke("、", 0)
        }

        // RB で確定
        if (gp.rb && !prevRB && pinyinCandidates.isNotEmpty()) {
            val candidate = pinyinCandidates.getOrNull(pinyinSelectedIndex)
            if (candidate != null) {
                onDirectInsert?.invoke(candidate.word, 0)
                pinyinBuffer = ""
                zhuyinDisplayBuffer = ""
                pinyinCandidates = emptyList()
                pinyinSelectedIndex = 0
                pinyinWindowStart = 0
            }
        }
    }

    // MARK: - Pinyin 候補検索

    /// 先頭候補を確定してバッファをクリア。候補が無ければバッファだけクリア
    private fun confirmTopPinyinCandidate() {
        val top = pinyinCandidates.firstOrNull()
        if (top != null) {
            onDirectInsert?.invoke(top.word, 0)
        }
        pinyinBuffer = ""
        zhuyinDisplayBuffer = ""
        pinyinCandidates = emptyList()
        pinyinSelectedIndex = 0
        pinyinWindowStart = 0
    }

    private fun lookupPinyinCandidates() {
        val engine = pinyinEngine ?: return
        engine.variant = when (currentMode) {
            GamepadInputMode.CHINESE_SIMPLIFIED -> ChineseVariant.SIMPLIFIED
            GamepadInputMode.CHINESE_TRADITIONAL -> ChineseVariant.TRADITIONAL
            else -> engine.variant
        }
        pinyinCandidates = engine.lookup(pinyinBuffer)
        pinyinSelectedIndex = 0
        pinyinWindowStart = 0
    }

    private fun updatePinyinWindow() {
        // 日本語同様ページ単位でウィンドウをジャンプ
        pinyinWindowStart = (pinyinSelectedIndex / pinyinWindowSize) * pinyinWindowSize
    }

    /// 中国語候補を左スティック ↑↓ でサイクル
    private fun cyclePinyinCandidate(delta: Int) {
        if (pinyinCandidates.isEmpty()) return
        val size = pinyinCandidates.size
        pinyinSelectedIndex = ((pinyinSelectedIndex + delta) % size + size) % size
        updatePinyinWindow()
    }

}
