package com.ethran.notable.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.TypeConverters
import java.util.Date
import java.util.UUID

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Page::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("pageId"),
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RecognizedText(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val noteId: String?,
    @ColumnInfo(index = true) val pageId: String,
    val chunkIndex: Int = 0, // for chunked pages
    val recognizedText: String,
    val updatedAt: Date = Date()
)

@Entity
@TypeConverters(StrokeIdListConverter::class)
data class RecognizedTextChunk(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val pageId: String,
    val recognizedText: String,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val timestamp: Long,
    val strokeIds: List<String>
)

@Dao
interface RecognizedTextDao {
    @Insert
    fun insert(recognizedText: RecognizedText): Long

    @Update
    fun update(recognizedText: RecognizedText)

    @Query("SELECT * FROM RecognizedText WHERE noteId = :noteId AND pageId = :pageId ORDER BY chunkIndex ASC")
    fun getByNoteAndPage(noteId: String, pageId: String): List<RecognizedText>

    @Query("DELETE FROM RecognizedText WHERE noteId = :noteId AND pageId = :pageId")
    fun deleteByNoteAndPage(noteId: String, pageId: String)

    // --- Chunked recognition methods ---
    @Insert
    fun insertChunk(chunk: RecognizedTextChunk): Long

    @Update
    fun updateChunk(chunk: RecognizedTextChunk)

    @Query("SELECT * FROM RecognizedTextChunk WHERE pageId = :pageId ORDER BY minY ASC, minX ASC")
    fun getChunksForPage(pageId: String): List<RecognizedTextChunk>

    @Query("DELETE FROM RecognizedTextChunk WHERE pageId = :pageId")
    fun deleteChunksByPage(pageId: String)
} 