package com.gime.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserWord
import kotlinx.coroutines.launch

/// ユーザー辞書エディタ + 学習履歴リセット画面。
///
/// シンプルに一覧表示 + 追加フォーム + 個別削除 + 全削除 + 学習リセット。
/// CSV エクスポート等は将来必要になったら追加。
@Composable
fun DictionaryScreen(
    userDict: UserDictionaryRepository,
    learnRepo: LearnRepository,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<UserWord>>(emptyList()) }
    var reading by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf("") }
    var posIndex by remember { mutableStateOf(0) }
    var showPosMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // 初回ロード
    LaunchedEffect(Unit) {
        words = userDict.all()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        // ヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "辞書", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) {
                Text("閉じる")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 追加フォーム
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text("ユーザー単語を追加", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            SimpleField(label = "読み（ひらがな）", value = reading, onChange = { reading = it })
            Spacer(modifier = Modifier.height(6.dp))
            SimpleField(label = "単語（surface）", value = surface, onChange = { surface = it })
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("品詞: ", fontSize = 13.sp)
                Box {
                    TextButton(onClick = { showPosMenu = true }) {
                        Text(POS_LABELS.getOrElse(posIndex) { "名詞" })
                    }
                    DropdownMenu(
                        expanded = showPosMenu,
                        onDismissRequest = { showPosMenu = false },
                    ) {
                        POS_LABELS.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    posIndex = i
                                    showPosMenu = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                enabled = reading.isNotBlank() && surface.isNotBlank(),
                onClick = {
                    val r = reading.trim()
                    val s = surface.trim()
                    val pi = posIndex
                    scope.launch {
                        userDict.upsert(
                            reading = r,
                            word = s,
                            posIndex = pi,
                            // posScore は低いほど優先される。ユーザー登録語はシステム語と
                            // 同等〜やや優遇する 2000 付近を既定にする。
                            posScore = 2000,
                        )
                        words = userDict.all()
                        reading = ""
                        surface = ""
                        status = "追加しました: $r → $s"
                    }
                },
            ) {
                Text("追加")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 一覧
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("登録済み (${words.size})", fontSize = 14.sp)
            TextButton(
                onClick = {
                    scope.launch {
                        userDict.deleteAll()
                        words = emptyList()
                        status = "ユーザー辞書を全削除しました"
                    }
                },
                enabled = words.isNotEmpty(),
            ) {
                Text("すべて削除", color = MaterialTheme.colorScheme.error)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        ) {
            items(words, key = { it.id }) { w ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${w.reading} → ${w.word}", fontSize = 15.sp)
                        Text(
                            POS_LABELS.getOrElse(w.posIndex) { "名詞" },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            userDict.delete(w)
                            words = userDict.all()
                            status = "削除: ${w.reading}"
                        }
                    }) { Text("削除", color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider()
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 学習履歴リセット
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("学習履歴", fontSize = 14.sp)
                Text(
                    "変換確定で記録された読み→単語の優遇をリセットします",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                scope.launch {
                    learnRepo.deleteAll()
                    status = "学習履歴を削除しました"
                }
            }) {
                Text("リセット", color = MaterialTheme.colorScheme.error)
            }
        }

        status?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun SimpleField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(8.dp),
        )
    }
}

/// PosMapper (com.kazumaproject.markdownhelperkeyboard.user_dictionary) の
/// index 定義と 1:1 で対応。表示順も揃える。
private val POS_LABELS = listOf(
    "名詞", "動詞", "形容詞", "副詞", "助動詞", "助詞",
    "感動詞", "接続詞", "接頭詞", "記号", "連体詞", "その他",
)
