package com.example.coupontracker.data.local

import androidx.room.*

/**
 * Entity capturing validator override and user correction datasets for offline training.
 */
@Entity(
    tableName = "validator_feedback_v1",
    indices = [
        Index(value = ["timestamp"], orders = [Index.Order.DESC]),
        Index(value = ["eventType"])
    ]
)
data class ValidatorFeedbackRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventType: String,
    val fieldOutcomesJson: String,
    val rationaleJson: String,
    val metadataJson: String,
    val ocrHash: String?,
    val ocrPreview: String?,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ValidatorFeedbackDao {
    @Insert
    suspend fun insert(record: ValidatorFeedbackRecord): Long

    @Query(
        """
        SELECT * FROM validator_feedback_v1
        WHERE eventType = :eventType
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getRecent(eventType: String, limit: Int): List<ValidatorFeedbackRecord>

    @Query(
        """
        DELETE FROM validator_feedback_v1
        WHERE timestamp < :cutoff
        """
    )
    suspend fun purgeOlderThan(cutoff: Long): Int
}
