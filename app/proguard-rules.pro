# 閃退紀錄（CrashLog）保留檔名與行號
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebViewUpgrade（換網頁核心）：大量反射與動態代理，整個保留
-keep class com.norman.webviewup.** { *; }
