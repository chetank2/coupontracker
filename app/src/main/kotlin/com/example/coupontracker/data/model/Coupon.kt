package com.example.coupontracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "coupons")
data class Coupon(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val storeName: String,
    val description: String,
    val expiryDate: Date,
    val cashbackAmount: Double,
    val redeemCode: String?,
    val imageUri: String?,
    val category: String? = null,
    val status: String? = null,

    // New fields
    val minimumPurchase: Double? = null,
    val maximumDiscount: Double? = null,
    val isPriority: Boolean = false,
    val paymentMethod: String? = null,
    val usageLimit: Int? = null,
    val usageCount: Int = 0,
    val reminderDate: Date? = null,
    val platformType: String? = null,

    // Existing tracking fields
    val rating: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)