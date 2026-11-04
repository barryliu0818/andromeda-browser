package com.liquid.browser.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.Account
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.core.Palette
import com.liquid.browser.core.t
import com.liquid.browser.ui.GroupFooter
import com.liquid.browser.ui.GroupHeader
import com.liquid.browser.ui.GroupRow
import com.liquid.browser.ui.IconTile
import com.liquid.browser.ui.PageTitle
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.Tone
import com.liquid.browser.ui.groupItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Mode { Login, Register, Reset }

/**
 * 用戶中心：登入（郵箱＋密碼）、註冊（郵箱＋密碼兩次＋郵箱驗證碼）、忘記密碼（郵箱驗證碼＋新密碼兩次）；已登入時顯示帳號與登出。
 * 從頁面選單、設定頁，或按了要登入才能用的功能（AI 翻譯、安裝擴充）時打開。
 */
@Composable
fun UserCenter(vm: BrowserViewModel, tone: Tone) {
    val accent = accent
    // 鍵盤彈出時面板縮到鍵盤上方（imePadding），按鈕、錯誤訊息不會被鍵盤蓋住
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
        item { PageTitle(t("用戶中心"), tone, Modifier.padding(start = 4.dp)) }
        // 頂部像 Safari／iOS 的 Apple 帳號頁：大頭像＋名稱＋一句說明
        item {
            val email = Account.email
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), Alignment.Center) {
                    com.liquid.browser.ui.Sym("person", accent, 44.dp)
                }
                Text(email ?: t("Orea 帳號"), Modifier.padding(top = 10.dp), color = tone.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (email != null) t("已登入・可以使用 AI 翻譯與擴充功能") else t("登入後才能使用 AI 翻譯與安裝擴充功能"),
                    Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp), color = Palette.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        }
        item {
            val email = Account.email
            when {
                email != null -> {
                    // 郵箱已經顯示在上面的頭像下：這裡只放登出（iOS 帳號頁底部的「登出」）
                    Box(Modifier.padding(top = 16.dp)) {
                        GroupRow(0, 1, tone, t("登出"), destructive = true, onClick = { Account.signOut(); vm.showToast(t("已登出")) })
                    }
                }
                !Account.configured -> GroupRow(0, 1, tone, t("帳號服務尚未設定"), subtitle = t("這個版本沒有編入帳號服務，暫時無法登入"), leading = { IconTile("account_circle", Palette.Gray) })
                else -> Box(Modifier.padding(top = 12.dp)) { Forms(vm, tone, accent) }
            }
        }
    }
}

@Composable
private fun Forms(vm: BrowserViewModel, tone: Tone, accent: Color) {
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var mode by remember { mutableStateOf(Mode.Login) }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // 驗證碼寄到哪個郵箱（null＝還沒寄）；寄出後 60 秒才能重寄
    var sentTo by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wait > 0) { while (wait > 0) { delay(1000); wait-- } }
    fun switch(m: Mode) { mode = m; sentTo = null; code = ""; password = ""; again = ""; error = null }
    fun run(block: suspend () -> Unit) {
        // 送出時收起鍵盤：結果與錯誤訊息才看得到
        focusManager.clearFocus()
        busy = true; error = null
        scope.launch {
            runCatching { block() }.onFailure { error = t(it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }
    val mail = address.trim()
    val validMail = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(mail)
    // 註冊、重設密碼的新密碼：規則＋兩次一樣
    fun newPasswordProblem(): String? = Account.passwordProblem(password) ?: if (password != again) "兩次輸入的密碼不一樣" else null
    val sendCode = {
        val p = if (mode == Mode.Register) newPasswordProblem() else null
        if (p != null) { focusManager.clearFocus(); error = t(p) }
        else run {
            if (mode == Mode.Register) Account.startSignUp(mail, password) else Account.startReset(mail)
            sentTo = mail; code = ""; wait = 60
            vm.showToast(t("驗證碼已寄到 %1\$s", mail))
        }
    }
    val submit: () -> Unit = {
        when {
            mode == Mode.Login -> run { Account.signIn(mail, password); vm.showToast(t("登入成功")) }
            sentTo == null -> sendCode()
            mode == Mode.Register -> run { Account.finishSignUp(sentTo!!, code.trim()); vm.showToast(t("註冊成功")) }
            else -> newPasswordProblem()?.let { focusManager.clearFocus(); error = t(it) } ?: run { Account.finishReset(sentTo!!, code.trim(), password); vm.showToast(t("密碼已重設，已登入")) }
        }
    }

    Column(Modifier.fillMaxWidth().groupItem(0, 1, tone).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (mode == Mode.Reset) Text(t("忘記密碼"), color = tone.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.content.copy(alpha = 0.06f)).padding(3.dp)) {
            listOf(Mode.Login to t("登入"), Mode.Register to t("註冊")).forEach { (m, label) ->
                Text(
                    label,
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (mode == m) tone.content.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { if (mode != m) switch(m) }
                        .semantics { selected = mode == m }
                        .padding(vertical = 8.dp),
                    color = tone.content, fontSize = 15.sp, fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val gap = Modifier.padding(top = 8.dp)
        // 欄位出現／消失（切換模式、寄出驗證碼後多出驗證碼欄）用展開＋淡入，不是瞬間跳出來
        val show = androidx.compose.animation.expandVertically(com.liquid.browser.core.Motion.standard()) + androidx.compose.animation.fadeIn(com.liquid.browser.core.Motion.timed())
        val hide = androidx.compose.animation.shrinkVertically(com.liquid.browser.core.Motion.standard()) + androidx.compose.animation.fadeOut(com.liquid.browser.core.Motion.timed(com.liquid.browser.core.Motion.ShortMs))
        val showPassword = mode == Mode.Login || mode == Mode.Register || sentTo != null
        val needAgain = mode != Mode.Login && showPassword
        // 鍵盤的「下一個」跳到下一欄，最後一欄按「完成」直接送出
        val lastIsCode = sentTo != null
        // 驗證碼欄：忘記密碼時放在新密碼前面（先收信拿到驗證碼，再設新密碼）；註冊時放最後
        val codeField = @Composable { last: Boolean ->
            androidx.compose.animation.AnimatedVisibility(sentTo != null, enter = show, exit = hide) {
                Field(code, { v -> code = v.filter(Char::isDigit).take(10) }, t("郵件裡的驗證碼"), tone, accent, KeyboardType.NumberPassword, gap, last = last) { submit() }
            }
        }
        // 寄出驗證碼後郵箱鎖定；改郵箱就要重寄
        Field(address, { address = it; if (sentTo != null && it.trim() != sentTo) sentTo = null }, t("郵箱"), tone, accent, KeyboardType.Email, Modifier.padding(top = 10.dp), last = !showPassword && !lastIsCode) { submit() }
        if (mode == Mode.Reset) codeField(false)
        androidx.compose.animation.AnimatedVisibility(showPassword, enter = show, exit = hide) {
            Field(password, { password = it }, if (mode == Mode.Reset) t("新密碼") else t("登入密碼"), tone, accent, KeyboardType.Password, gap, last = mode == Mode.Login) { submit() }
        }
        androidx.compose.animation.AnimatedVisibility(needAgain, enter = show, exit = hide) {
            Column {
                Field(again, { again = it }, t("再輸入一次密碼"), tone, accent, KeyboardType.Password, gap, last = mode == Mode.Reset || !lastIsCode) { submit() }
                // 密碼規則即時打勾（不用等按下按鈕才知道哪裡不合格）
                val rules = listOf(
                    t("至少 8 個字元") to (password.length >= 8),
                    t("包含英文字母") to password.any { it in 'a'..'z' || it in 'A'..'Z' },
                    t("包含數字") to password.any(Char::isDigit),
                    t("兩次輸入一樣") to (password.isNotEmpty() && password == again),
                )
                Column(Modifier.padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rules.forEach { (label, ok) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.liquid.browser.ui.Sym(if (ok) "check_circle" else "radio_button_unchecked", if (ok) Color(0xFF34C759) else Palette.Gray, 16.dp)
                            Text(label, Modifier.padding(start = 6.dp), color = if (ok) tone.content else Palette.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (mode != Mode.Reset) codeField(true)
        error?.let { Text(it, Modifier.padding(top = 8.dp), color = Color(0xFFFF3B30), fontSize = 13.sp) }

        val label = when {
            mode == Mode.Login -> t("登入")
            sentTo == null -> t("傳送驗證碼")
            mode == Mode.Register -> t("完成註冊")
            else -> t("重設密碼")
        }
        val ready = when {
            !validMail -> false
            mode == Mode.Login -> password.isNotEmpty()
            sentTo == null -> wait == 0 && (mode == Mode.Reset || (password.isNotEmpty() && again.isNotEmpty()))
            else -> code.trim().length >= 6 && (mode == Mode.Register || (password.isNotEmpty() && again.isNotEmpty()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (ready && !busy) accent else accent.copy(alpha = 0.35f))
                    .clickable(enabled = ready && !busy) { submit() }.padding(vertical = 12.dp),
                Alignment.Center,
            ) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (sentTo != null) Text(
                if (wait > 0) t("重新傳送（%1\$s）", wait) else t("重新傳送"),
                Modifier.clip(CircleShape).clickable(enabled = wait == 0 && !busy) { sendCode() }.padding(horizontal = 8.dp, vertical = 10.dp),
                color = if (wait == 0) accent else Palette.Gray, fontSize = 14.sp,
            )
        }
        Text(
            if (mode == Mode.Reset) t("返回登入") else if (mode == Mode.Login) t("忘記密碼？") else "",
            Modifier.padding(top = 10.dp).clip(CircleShape).clickable(enabled = mode != Mode.Register) { switch(if (mode == Mode.Reset) Mode.Login else Mode.Reset) }.padding(vertical = 6.dp, horizontal = 4.dp),
            color = accent, fontSize = 14.sp,
        )
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, hint: String, tone: Tone, accent: Color, type: KeyboardType,
    modifier: Modifier = Modifier, last: Boolean = false, onDone: () -> Unit = {},
) {
    val secret = type == KeyboardType.Password
    // 密碼欄右邊的眼睛：按一下顯示／隱藏
    var visible by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.content.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value, onChange,
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = tone.content, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
            visualTransformation = if (secret && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = type,
                imeAction = if (last) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Next,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                onDone = { focus.clearFocus(); onDone() },
            ),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = Palette.Gray, fontSize = 15.sp)
                inner()
            },
        )
        if (secret) com.liquid.browser.ui.SymButton(
            if (visible) "visibility_off" else "visibility", Palette.Gray, size = 20.dp,
            label = if (visible) t("隱藏密碼") else t("顯示密碼"),
        ) { visible = !visible }
    }
}
