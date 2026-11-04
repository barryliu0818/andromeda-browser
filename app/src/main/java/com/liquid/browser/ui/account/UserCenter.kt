package com.liquid.browser.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
        item { PageTitle(t("用戶中心"), tone, Modifier.padding(start = 4.dp)) }
        item {
            val email = Account.email
            when {
                email != null -> {
                    GroupHeader(t("帳號"), tone)
                    GroupRow(
                        0, 1, tone, email, subtitle = t("已登入・可以使用 AI 翻譯與擴充功能"),
                        leading = { IconTile("account_circle", accent) },
                        trailing = {
                            Text(
                                t("登出"),
                                Modifier.clip(CircleShape).clickable { Account.signOut(); vm.showToast(t("已登出")) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = Color(0xFFFF3B30), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            )
                        },
                    )
                }
                !Account.configured -> GroupRow(0, 1, tone, t("帳號服務尚未設定"), subtitle = t("這個版本沒有編入帳號服務，暫時無法登入"), leading = { IconTile("account_circle", Palette.Gray) })
                else -> {
                    Box(Modifier.padding(top = 12.dp)) { Forms(vm, tone, accent) }
                    GroupFooter(t("登入後才能使用 AI 翻譯與安裝擴充功能"))
                }
            }
        }
    }
}

@Composable
private fun Forms(vm: BrowserViewModel, tone: Tone, accent: Color) {
    val scope = rememberCoroutineScope()
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
        if (p != null) error = t(p)
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
            else -> newPasswordProblem()?.let { error = t(it) } ?: run { Account.finishReset(sentTo!!, code.trim(), password); vm.showToast(t("密碼已重設，已登入")) }
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
        // 寄出驗證碼後郵箱鎖定；改郵箱就要重寄
        Field(address, { address = it; if (sentTo != null && it.trim() != sentTo) sentTo = null }, t("郵箱"), tone, accent, KeyboardType.Email, Modifier.padding(top = 10.dp))
        val showPassword = mode == Mode.Login || mode == Mode.Register || sentTo != null
        if (showPassword) Field(password, { password = it }, if (mode == Mode.Reset) t("新密碼") else t("密碼"), tone, accent, KeyboardType.Password, gap)
        if (mode != Mode.Login && showPassword) Field(again, { again = it }, t("再輸入一次密碼"), tone, accent, KeyboardType.Password, gap)
        if (mode != Mode.Login && showPassword) Text(t("至少 8 個字元，要有英文字母和數字"), Modifier.padding(top = 4.dp), color = Palette.Gray, fontSize = 12.sp)
        if (sentTo != null) Field(code, { v -> code = v.filter(Char::isDigit).take(10) }, t("郵件裡的驗證碼"), tone, accent, KeyboardType.NumberPassword, gap)
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
private fun Field(value: String, onChange: (String) -> Unit, hint: String, tone: Tone, accent: Color, type: KeyboardType, modifier: Modifier = Modifier) {
    val secret = type == KeyboardType.Password
    androidx.compose.foundation.text.BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.content.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 12.dp),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = tone.content, fontSize = 15.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = type, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = Palette.Gray, fontSize = 15.sp)
            inner()
        },
    )
}
