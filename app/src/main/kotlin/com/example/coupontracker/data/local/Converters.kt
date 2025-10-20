package com.example.coupontracker.data.local

import androidx.room.TypeConverter
import org.json.JSONObject
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    @TypeConverter
    fun confidenceMapToJson(map: Map<String, Float>?): String {
        if (map.isNullOrEmpty()) {
            return "{}"
        }
        val jsonObject = JSONObject()
        map.forEach { (key, value) ->
            jsonObject.put(key, value.toDouble())
        }
        return jsonObject.toString()
    }

    @TypeConverter
    fun jsonToConfidenceMap(json: String?): Map<String, Float> {
        if (json.isNullOrBlank()) {
            return emptyMap()
        }
        return try {
            val jsonObject = JSONObject(json)
            val result = mutableMapOf<String, Float>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObject.optDouble(key, Double.NaN)
                if (!value.isNaN()) {
                    result[key] = value.toFloat()
                }
            }
            result
        } catch (ignored: Exception) {
            emptyMap()
        }
    }
}
