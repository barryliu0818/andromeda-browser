package com.liquid.browser.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* 本地資料（Room）：書籤、歷史紀錄、閱讀列表、網站圖示、影片播放進度。所有查詢皆為 suspend／Flow，不在主執行緒做 I/O。 */

/** 書籤或資料夾（isFolder）；parentId 為 null 表示在根目錄；position 為同層排序。 */
@Immutable
@Entity(tableName = "bookmarks", indices = [Index("parentId"), Index("url")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val title: String,
    val url: String? = null,
    val isFolder: Boolean = false,
    val position: Int = 0,
)

@Immutable
@Entity(tableName = "history", indices = [Index("url"), Index("visitedAt"), Index("host")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val host: String,
    val title: String,
    val visitedAt: Long,
)

@Immutable
@Entity(tableName = "reading_list")
data class ReadingItem(
    @PrimaryKey val url: String,
    val title: String,
    /** 文章縮圖（og:image）網址 */
    val thumbnail: String?,
    val addedAt: Long,
    val read: Boolean = false,
)

/** 網站的高解析度圖示（apple-touch-icon／最大的 link rel=icon），依網域儲存。 */
@Immutable
@Entity(tableName = "site_icons")
data class SiteIcon(@PrimaryKey val host: String, val iconUrl: String)

/** 每部影片的播放進度。 */
@Entity(tableName = "playback")
data class PlaybackPosition(
    @PrimaryKey val mediaUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Immutable
data class FrequentSite(val host: String, val url: String, val title: String, val visits: Int)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE (parentId IS :parentId) ORDER BY isFolder DESC, position, id")
    fun children(parentId: Long?): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE isFolder = 0 AND (title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%') LIMIT :limit")
    suspend fun search(q: String, limit: Int = 4): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE isFolder = 1 ORDER BY title")
    fun folders(): Flow<List<Bookmark>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM bookmarks WHERE (parentId IS :parentId)")
    suspend fun nextPosition(parentId: Long?): Int

    @Insert
    suspend fun insert(b: Bookmark): Long

    @Update
    suspend fun update(items: List<Bookmark>)

    @Query("DELETE FROM bookmarks WHERE url = :url AND isFolder = 0")
    suspend fun deleteUrl(url: String)

    /** 刪除項目；若為資料夾，連同底下一層一起刪（巢狀更深的由遞迴呼叫處理）。 */
    @Query("DELETE FROM bookmarks WHERE id = :id OR parentId = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(e: HistoryEntry)

    @Query("SELECT * FROM history WHERE (:q = '' OR title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%') ORDER BY visitedAt DESC LIMIT :limit")
    fun search(q: String, limit: Int = 500): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%' GROUP BY url ORDER BY MAX(visitedAt) DESC LIMIT :limit")
    suspend fun match(q: String, limit: Int = 4): List<HistoryEntry>

    @Query(
        "SELECT host, url, title, COUNT(*) AS visits FROM history WHERE visitedAt > :since " +
            "GROUP BY host ORDER BY visits DESC LIMIT :limit"
    )
    fun frequent(since: Long, limit: Int = 8): Flow<List<FrequentSite>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE visitedAt >= :since")
    suspend fun deleteSince(since: Long)
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_list ORDER BY addedAt DESC")
    fun all(): Flow<List<ReadingItem>>

    @Upsert
    suspend fun upsert(item: ReadingItem)

    @Query("DELETE FROM reading_list WHERE url = :url")
    suspend fun delete(url: String)

    @Query("UPDATE reading_list SET read = :read WHERE url = :url")
    suspend fun setRead(url: String, read: Boolean)
}

@Dao
interface IconDao {
    @Query("SELECT * FROM site_icons")
    fun all(): Flow<List<SiteIcon>>

    @Upsert
    suspend fun upsert(icon: SiteIcon)
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback WHERE mediaUrl = :url")
    suspend fun get(url: String): PlaybackPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(p: PlaybackPosition)
}

@Database(
    entities = [Bookmark::class, HistoryEntry::class, ReadingItem::class, SiteIcon::class, PlaybackPosition::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun history(): HistoryDao
    abstract fun reading(): ReadingDao
    abstract fun icons(): IconDao
    abstract fun playback(): PlaybackDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "liquid.db")
                .build().also { instance = it }
        }
    }
}
