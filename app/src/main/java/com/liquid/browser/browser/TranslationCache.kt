package com.liquid.browser.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 翻譯快取（獨立的 SQLite 檔，不動書籤資料庫）：同一段原文翻過一次就存起來，
 * 重新整理或再次造訪按「翻譯」時直接套用，只有新文字才花 API 額度。
 * 以「目標語言＋原文」為鍵，不分引擎（換引擎也能沿用）；超過上限時刪掉最久沒用到的。
 */
class TranslationCache(context: Context) : SQLiteOpenHelper(context, "translations.db", null, 1) {
    // WAL：查詢與寫入不互相等待，寫入也比預設的回滾日誌快
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tr (k TEXT PRIMARY KEY, v TEXT NOT NULL, used INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX tr_used ON tr(used)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun key(target: String, text: String) = "$target\u0001$text"

    /** 查出已翻過的段落（同時更新使用時間）；回傳 原文→譯文 */
    @Synchronized
    fun get(target: String, texts: Collection<String>): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val db = writableDatabase
        val now = System.currentTimeMillis()
        // 使用時間只在超過一天沒更新時才寫（以前每查一批就寫一次；翻過的頁面重新整理會連續查很多批），
        // 整批在一個交易裡做完
        db.beginTransaction()
        try {
            // SQLite 參數上限 999：分批查
            texts.distinct().chunked(500).forEach { part ->
                val args = part.map { key(target, it) }.toTypedArray()
                db.rawQuery("SELECT k, v FROM tr WHERE k IN (${args.joinToString(",") { "?" }})", args).use { c ->
                    while (c.moveToNext()) out[c.getString(0).substringAfter('\u0001')] = c.getString(1)
                }
                db.execSQL("UPDATE tr SET used = $now WHERE used < ${now - 86_400_000L} AND k IN (${args.joinToString(",") { "?" }})", args)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return out
    }

    @Synchronized
    fun put(target: String, pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            pairs.forEach { (src, tr) ->
                db.insertWithOnConflict("tr", null, ContentValues().apply { put("k", key(target, src)); put("v", tr); put("used", now) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            // 上限 5 萬段（約數 MB）：刪掉最久沒用到的
            db.execSQL("DELETE FROM tr WHERE k IN (SELECT k FROM tr ORDER BY used DESC LIMIT -1 OFFSET 50000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun clear() { writableDatabase.execSQL("DELETE FROM tr") }
}
