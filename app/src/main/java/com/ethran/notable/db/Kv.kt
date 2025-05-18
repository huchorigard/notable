package com.ethran.notable.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.TAG
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import io.shipbook.shipbooksdk.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

const val USER_INFO_KEY = "user_info"

@Entity
data class Kv(
    @PrimaryKey
    val key: String,
    val value: String
)

// DAO
@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE `key`=:key")
    fun get(key: String): Kv?

    @Query("SELECT * FROM kv WHERE `key`=:key")
    fun getLive(key: String): LiveData<Kv?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(kv: Kv)

    @Query("DELETE FROM kv WHERE `key`=:key")
    fun delete(key: String)

}

class KvRepository(context: Context) {
    var db = AppDatabase.getDatabase(context).kvDao()

    fun get(key: String): Kv? {
        return db.get(key)
    }

    fun getLive(key: String): LiveData<Kv?> {
        return db.getLive(key)
    }

    fun set(kv: Kv) {
        return db.set(kv)
    }

    fun delete(key: String) {
        db.delete(key)
    }

}

class KvProxy(context: Context) {
    private val kvRepository = KvRepository(context)

    fun <T> observeKv(key: String, serializer: KSerializer<T>, default: T): LiveData<T?> {
        return kvRepository.getLive(key).map {
            if (it == null) return@map default
            val jsonValue = it.value
            Json.decodeFromString(serializer, jsonValue)
        }
    }

    fun <T> get(key: String, serializer: KSerializer<T>): T? {
        val kv = kvRepository.get(key) ?: return null //returns null when there is no database
        val jsonValue = kv.value
        return Json.decodeFromString(serializer, jsonValue)
    }


    fun <T> setKv(key: String, value: T, serializer: KSerializer<T>) {
        val jsonValue = Json.encodeToString(serializer, value)
        Log.i(TAG, jsonValue)
        kvRepository.set(Kv(key, jsonValue))
    }

    fun setAppSettings(value: AppSettings) {
        setKv(APP_SETTINGS_KEY, value, AppSettings.serializer())
        GlobalAppSettings.update(value)
    }

}
