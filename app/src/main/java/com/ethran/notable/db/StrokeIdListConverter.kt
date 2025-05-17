package com.ethran.notable.db

import androidx.room.TypeConverter

class StrokeIdListConverter {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString(",")

    @TypeConverter
    fun toList(data: String): List<String> = if (data.isEmpty()) emptyList() else data.split(",")
} 