package com.liquid.browser.browser

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import com.liquid.browser.core.t

/**
 * 填入密碼、查看已存密碼前的身分驗證：系統的指紋（或臉部）驗證框，可改用螢幕鎖（PIN／圖形／密碼）。
 * 用系統內建的 BiometricPrompt（Android 9+），不需要額外的套件；手機沒設螢幕鎖時無法驗證，一律不放行。
 */
object Auth {
    fun request(activity: Activity, title: String, onFail: (String) -> Unit = {}, onOk: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return onFail(t("需要 Android 9 以上才能用指紋驗證"))
        val b = BiometricPrompt.Builder(activity).setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
        runCatching {
            b.build().authenticate(
                CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        // 使用者自己取消不算錯誤，不跳訊息
                        if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) onFail(msg.toString())
                    }
                },
            )
        }.onFailure { onFail(t("這支手機沒有設定螢幕鎖，無法驗證")) }
    }
}
