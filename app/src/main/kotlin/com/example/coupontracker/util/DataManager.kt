package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    suspend fun exportData(uri: Uri, coupons: List<Coupon>) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    val json = gson.toJson(coupons)
                    writer.write(json)
                }
            }
        }
    }

    suspend fun importData(uri: Uri): List<Coupon> {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val json = reader.readText()
                    val type = object : TypeToken<List<Coupon>>() {}.type
                    gson.fromJson(json, type)
                }
            } ?: emptyList()
        }
    }
} 