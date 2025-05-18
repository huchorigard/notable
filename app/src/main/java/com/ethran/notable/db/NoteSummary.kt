package com.ethran.notable.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.lifecycle.LiveData

@Entity
data class PageSummary(
    @PrimaryKey val pageId: String,
    val summaryText: String,
    val timestamp: Long
)

@Dao
interface PageSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(summary: PageSummary)

    @Update
    fun update(summary: PageSummary)

    @Query("SELECT * FROM PageSummary WHERE pageId = :pageId")
    fun getSummary(pageId: String): PageSummary?

    @Query("SELECT * FROM PageSummary WHERE pageId = :pageId")
    fun getSummaryLive(pageId: String): LiveData<PageSummary?>

    @Query("DELETE FROM PageSummary WHERE pageId = :pageId")
    fun deleteSummary(pageId: String)
} 