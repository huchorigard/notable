package com.ethran.notable.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Entity
data class NoteSummary(
    @PrimaryKey val noteId: String,
    val summaryText: String,
    val timestamp: Long
)

@Dao
interface NoteSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(summary: NoteSummary)

    @Update
    fun update(summary: NoteSummary)

    @Query("SELECT * FROM NoteSummary WHERE noteId = :noteId")
    fun getSummary(noteId: String): NoteSummary?

    @Query("DELETE FROM NoteSummary WHERE noteId = :noteId")
    fun deleteSummary(noteId: String)
} 