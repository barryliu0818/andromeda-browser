//! Orea 廣告攔截：adblock-rust（Brave，MPL-2.0）的 JNI 介面，對應 Kotlin 的 com.liquid.browser.browser.AdEngine。
//!
//! 引擎放在全域 RwLock：WebView 的網路執行緒同時查詢（讀），換清單時整個替換（寫）。
//! 清單第一次解析較慢（約 1 秒），之後用 serialize 的二進位快取載入（約數十毫秒）。

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

/// 用清單文字（多份清單直接接在一起）建立引擎
#[no_mangle]
pub extern "system" fn Java_com_liquid_browser_browser_AdEngine_nativeBuild(mut env: JNIEnv, _: JClass, text: JString) -> jboolean {
    let text = string(&mut env, &text);
    let mut set = FilterSet::new(false);
    set.add_filter_list(text, ParseOptions::default());
    *ENGINE.write().unwrap() = Some(Engine::new_with_filter_set(set));
    JNI_TRUE
}

/// 目前引擎的二進位快取（下次啟動用 nativeLoad 快速載入）；沒有引擎回傳 null
#[no_mangle]
pub extern "system" fn Java_com_liquid_browser_browser_AdEngine_nativeSerialize(env: JNIEnv, _: JClass) -> jbyteArray {
    match ENGINE.read().unwrap().as_ref() {
        Some(e) => env.byte_array_from_slice(&e.serialize()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// 從 nativeSerialize 的快取載入；格式不符（例如引擎版本變了）回傳 false，呼叫端改用清單重建
#[no_mangle]
pub extern "system" fn Java_com_liquid_browser_browser_AdEngine_nativeLoad(env: JNIEnv, _: JClass, data: JByteArray) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&data) else { return JNI_FALSE };
    let mut engine = Engine::default();
    if engine.deserialize(&bytes).is_err() { return JNI_FALSE }
    *ENGINE.write().unwrap() = Some(engine);
    JNI_TRUE
}

/// 0 = 放行、1 = 阻擋。type 用 adblock 的資源類型名稱（script、image、stylesheet、xmlhttprequest、sub_frame、media、font、other）
#[no_mangle]
pub extern "system" fn Java_com_liquid_browser_browser_AdEngine_nativeCheck(
    mut env: JNIEnv, _: JClass, url: JString, source: JString, kind: JString,
) -> jint {
    let (url, source, kind) = (string(&mut env, &url), string(&mut env, &source), string(&mut env, &kind));
    let guard = ENGINE.read().unwrap();
    let (Some(engine), Ok(req)) = (guard.as_ref(), Request::new(&url, &source, &kind, "GET")) else { return 0 };
    engine.check_network_request(&req).should_block() as jint
}

/// 網站專屬的外觀規則（JSON：hide_selectors、procedural_actions、exceptions、injected_script、generichide）
#[no_mangle]
pub extern "system" fn Java_com_liquid_browser_browser_AdEngine_nativeCosmetic(mut env: JNIEnv, _: JClass, url: JString) -> jstring {
    let url = string(&mut env, &url);
    let json = ENGINE.read().unwrap().as_ref()
        .map(|e| serde_json::to_string(&e.url_cosmetic_resources(&url)).unwrap_or_default())
        .unwrap_or_default();
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
