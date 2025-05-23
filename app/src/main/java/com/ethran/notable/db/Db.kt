package com.ethran.notable.db

import android.content.Context
import android.os.Environment
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date
import androidx.lifecycle.LiveData


class Converters {
    @TypeConverter
    fun fromListPoint(value: List<StrokePoint>) = Json.encodeToString(value)

    @TypeConverter
    fun toListPoint(value: String) = Json.decodeFromString<List<StrokePoint>>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

@RenameColumn.Entries(
    RenameColumn(
        tableName = "Page",
        fromColumnName = "nativeTemplate",
        toColumnName = "background"
    )
)
class AutoMigration30to31 : AutoMigrationSpec

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "page_tag_cross_ref",
    primaryKeys = ["pageId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Page::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("pageId"),
        Index("tagId")
    ]
)
data class PageTagCrossRef(
    val pageId: String,
    val tagId: String
)

@Dao
interface TagDao {
    @Query("SELECT * FROM tags")
    fun getAllTags(): List<Tag>

    @Query("SELECT * FROM tags WHERE id = :tagId")
    fun getTagById(tagId: String): Tag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTag(tag: Tag)

    @Delete
    fun deleteTag(tag: Tag)

    @Query("SELECT t.* FROM tags t INNER JOIN page_tag_cross_ref pt ON t.id = pt.tagId WHERE pt.pageId = :pageId")
    fun getTagsForPage(pageId: String): List<Tag>

    @Query("SELECT t.* FROM tags t INNER JOIN page_tag_cross_ref pt ON t.id = pt.tagId WHERE pt.pageId = :pageId")
    fun getTagsForPageLive(pageId: String): LiveData<List<Tag>>

    @Transaction
    fun setTagsForPage(pageId: String, tags: List<Tag>) {
        // Remove existing tags for the page
        deleteTagsForPage(pageId)
        
        // For each tag, find existing one with same name or create new
        tags.forEach { newTag ->
            val existingTag = getAllTags().find { it.name == newTag.name }
            val tagToUse = existingTag ?: newTag
            
            // Insert tag if it's new
            if (existingTag == null) {
                insertTag(tagToUse)
            }
            
            // Create cross reference
            insertPageTagCrossRef(PageTagCrossRef(pageId = pageId, tagId = tagToUse.id))
        }
    }

    @Query("DELETE FROM page_tag_cross_ref WHERE pageId = :pageId")
    fun deleteTagsForPage(pageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPageTagCrossRef(crossRef: PageTagCrossRef)
}

@Database(
    entities = [
        Folder::class, 
        Notebook::class, 
        Page::class, 
        Stroke::class, 
        Image::class, 
        Kv::class, 
        RecognizedText::class, 
        RecognizedTextChunk::class, 
        PageSummary::class,
        Tag::class,
        PageTagCrossRef::class
    ],
    version = 36,
    autoMigrations = [
        AutoMigration(19, 20),
        AutoMigration(20, 21),
        AutoMigration(21, 22),
        AutoMigration(23, 24),
        AutoMigration(24, 25),
        AutoMigration(25, 26),
        AutoMigration(26, 27),
        AutoMigration(27, 28),
        AutoMigration(28, 29),
        AutoMigration(29, 30),
        AutoMigration(30,  31, spec = AutoMigration30to31::class)
    ], exportSchema = true
)
@TypeConverters(Converters::class, StrokeIdListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun kvDao(): KvDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun ImageDao(): ImageDao
    abstract fun recognizedTextDao(): RecognizedTextDao
    abstract fun pageSummaryDao(): PageSummaryDao
    abstract fun tagDao(): TagDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create tags table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """)

                // Create page_tag_cross_ref table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS page_tag_cross_ref (
                        pageId TEXT NOT NULL,
                        tagId TEXT NOT NULL,
                        PRIMARY KEY(pageId, tagId),
                        FOREIGN KEY(pageId) REFERENCES Page(id) ON DELETE CASCADE,
                        FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE
                    )
                """)

                // Create indices for better query performance
                database.execSQL("CREATE INDEX IF NOT EXISTS index_page_tag_cross_ref_pageId ON page_tag_cross_ref(pageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_page_tag_cross_ref_tagId ON page_tag_cross_ref(tagId)")

                // Insert default tags
                val defaultTags = listOf("Work", "Personal", "Ideas", "To-Do", "Important", "Learning", "Meeting")
                defaultTags.forEach { tagName ->
                    val tagId = java.util.UUID.randomUUID().toString()
                    database.execSQL(
                        "INSERT INTO tags (id, name) VALUES (?, ?)",
                        arrayOf(tagId, tagName)
                    )
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(this) {
                    val documentsDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val dbDir = File(documentsDir, "notabledb")
                    if (!dbDir.exists()) {
                        dbDir.mkdirs()
                    }
                    val dbFile = File(dbDir, "app_database")

                    // Migration for PageSummary table
                    val MIGRATION_32_33 = object : Migration(32, 33) {
                        override fun migrate(database: SupportSQLiteDatabase) {
                            database.execSQL("""
                                CREATE TABLE IF NOT EXISTS PageSummary (
                                    pageId TEXT NOT NULL PRIMARY KEY,
                                    summaryText TEXT NOT NULL,
                                    timestamp INTEGER NOT NULL
                                )
                            """)
                        }
                    }

                    val callback = object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Insert default tags for new installations
                            val defaultTags = listOf("Work", "Personal", "Ideas", "To-Do", "Important", "Learning", "Meeting")
                            defaultTags.forEach { tagName ->
                                val tagId = java.util.UUID.randomUUID().toString()
                                db.execSQL(
                                    "INSERT INTO tags (id, name) VALUES (?, ?)",
                                    arrayOf(tagId, tagName)
                                )
                            }
                        }
                    }

                    INSTANCE =
                        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                            .allowMainThreadQueries() // Avoid in production
                            .addCallback(callback)
                            .addMigrations(
                                MIGRATION_16_17, 
                                MIGRATION_17_18, 
                                MIGRATION_22_23, 
                                MIGRATION_31_32, 
                                MIGRATION_32_33,
                                MIGRATION_35_36
                            )
                            .build()
                }
            }
            return INSTANCE!!
        }
    }
}