package com.example.coupontracker.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Comprehensive latency tracking system that logs detailed performance metrics
 * for all stages of the coupon processing pipeline
 */
class ComprehensiveLatencyTracker private constructor(private val context: Context) {
    
    data class LatencyEvent(
        val timestamp: Long,
        val eventType: EventType,
        val stage: ProcessingStage,
        val durationMs: Long,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    enum class EventType {
        START, END, ERROR
    }
    
    enum class ProcessingStage {
        IMAGE_LOADING,
        ROI_DETECTION,
        ROI_PREPROCESSING,
        ROI_OCR,
        FIELD_EXTRACTION,
        COUPON_CREATION,
        DATABASE_SAVE,
        TOTAL_PIPELINE
    }
    
    data class ProcessingSession(
        val sessionId: String,
        val startTime: Long,
        val endTime: Long? = null,
        val events: MutableList<LatencyEvent> = mutableListOf(),
        val imageSize: String? = null,
        val roiCount: Int = 0,
        val success: Boolean = false
    )
    
    private val events = ConcurrentLinkedQueue<LatencyEvent>()
    private val activeSessions = mutableMapOf<String, ProcessingSession>()
    private val loggerTag = "ComprehensiveLatencyTracker"
    
    companion object {
        private const val LOG_DIR = "latency_logs"
        private const val SESSION_FILE = "sessions.csv"
        private const val EVENTS_FILE = "events.csv"
        private const val MAX_EVENTS_IN_MEMORY = 1000
        
        @Volatile
        private var INSTANCE: ComprehensiveLatencyTracker? = null
        
        fun getInstance(context: Context): ComprehensiveLatencyTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ComprehensiveLatencyTracker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Start a new processing session
     */
    fun startSession(sessionId: String, imageSize: String? = null): ProcessingSession {
        val session = ProcessingSession(
            sessionId = sessionId,
            startTime = System.currentTimeMillis(),
            imageSize = imageSize
        )
        activeSessions[sessionId] = session
        
        logEvent(LatencyEvent(
            timestamp = System.currentTimeMillis(),
            eventType = EventType.START,
            stage = ProcessingStage.TOTAL_PIPELINE,
            durationMs = 0,
            metadata = mapOf("sessionId" to sessionId, "imageSize" to (imageSize ?: "unknown"))
        ))
        
        Log.d(loggerTag, "Started processing session: $sessionId")
        return session
    }
    
    /**
     * End a processing session
     */
    fun endSession(sessionId: String, success: Boolean) {
        val session = activeSessions[sessionId] ?: return
        session.endTime = System.currentTimeMillis()
        session.success = success
        
        val totalDuration = session.endTime - session.startTime
        
        logEvent(LatencyEvent(
            timestamp = System.currentTimeMillis(),
            eventType = EventType.END,
            stage = ProcessingStage.TOTAL_PIPELINE,
            durationMs = totalDuration,
            metadata = mapOf(
                "sessionId" to sessionId,
                "success" to success,
                "roiCount" to session.roiCount
            )
        ))
        
        // Flush session data to disk
        flushSessionToDisk(session)
        
        activeSessions.remove(sessionId)
        Log.d(loggerTag, "Ended processing session: $sessionId (${totalDuration}ms, success=$success)")
    }
    
    /**
     * Log a stage start event
     */
    fun logStageStart(sessionId: String, stage: ProcessingStage, metadata: Map<String, Any> = emptyMap()) {
        val session = activeSessions[sessionId] ?: return
        
        val event = LatencyEvent(
            timestamp = System.currentTimeMillis(),
            eventType = EventType.START,
            stage = stage,
            durationMs = 0,
            metadata = metadata + mapOf("sessionId" to sessionId)
        )
        
        session.events.add(event)
        logEvent(event)
    }
    
    /**
     * Log a stage end event
     */
    fun logStageEnd(sessionId: String, stage: ProcessingStage, metadata: Map<String, Any> = emptyMap()) {
        val session = activeSessions[sessionId] ?: return
        
        // Find the corresponding start event
        val startEvent = session.events.findLast { 
            it.stage == stage && it.eventType == EventType.START 
        }
        
        val duration = if (startEvent != null) {
            System.currentTimeMillis() - startEvent.timestamp
        } else {
            0L
        }
        
        val event = LatencyEvent(
            timestamp = System.currentTimeMillis(),
            eventType = EventType.END,
            stage = stage,
            durationMs = duration,
            metadata = metadata + mapOf("sessionId" to sessionId)
        )
        
        session.events.add(event)
        logEvent(event)
        
        // Update session metadata
        when (stage) {
            ProcessingStage.ROI_DETECTION -> {
                session.roiCount = metadata["roiCount"] as? Int ?: 0
            }
            else -> { /* No specific updates needed */ }
        }
    }
    
    /**
     * Log an error event
     */
    fun logError(sessionId: String, stage: ProcessingStage, error: Throwable, metadata: Map<String, Any> = emptyMap()) {
        val event = LatencyEvent(
            timestamp = System.currentTimeMillis(),
            eventType = EventType.ERROR,
            stage = stage,
            durationMs = 0,
            metadata = metadata + mapOf(
                "sessionId" to sessionId,
                "error" to error.message ?: "Unknown error",
                "errorType" to error.javaClass.simpleName
            )
        )
        
        activeSessions[sessionId]?.events?.add(event)
        logEvent(event)
        
        Log.e(loggerTag, "Error in stage $stage for session $sessionId", error)
    }
    
    /**
     * Log a generic event
     */
    private fun logEvent(event: LatencyEvent) {
        events.offer(event)
        
        // Prevent memory overflow
        if (events.size > MAX_EVENTS_IN_MEMORY) {
            events.poll() // Remove oldest event
        }
        
        // Periodically flush to disk
        if (events.size % 100 == 0) {
            flushEventsToDisk()
        }
    }
    
    /**
     * Flush session data to disk
     */
    private fun flushSessionToDisk(session: ProcessingSession) {
        try {
            val sessionFile = getSessionFile()
            val fileExists = sessionFile.exists()
            
            FileWriter(sessionFile, true).use { writer ->
                if (!fileExists) {
                    // Write header
                    writer.appendLine("sessionId,startTime,endTime,durationMs,success,imageSize,roiCount,eventCount")
                }
                
                val duration = session.endTime?.let { it - session.startTime } ?: 0L
                writer.appendLine(
                    listOf(
                        session.sessionId,
                        session.startTime.toString(),
                        session.endTime?.toString() ?: "",
                        duration.toString(),
                        session.success.toString(),
                        session.imageSize ?: "",
                        session.roiCount.toString(),
                        session.events.size.toString()
                    ).joinToString(",")
                )
            }
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed to flush session to disk", e)
        }
    }
    
    /**
     * Flush events to disk
     */
    private fun flushEventsToDisk() {
        if (events.isEmpty()) return
        
        try {
            val eventsFile = getEventsFile()
            val fileExists = eventsFile.exists()
            
            FileWriter(eventsFile, true).use { writer ->
                if (!fileExists) {
                    // Write header
                    writer.appendLine("timestamp,eventType,stage,durationMs,sessionId,metadata")
                }
                
                val eventsToWrite = mutableListOf<LatencyEvent>()
                while (events.isNotEmpty()) {
                    events.poll()?.let { eventsToWrite.add(it) }
                }
                
                for (event in eventsToWrite) {
                    val metadataString = event.metadata.entries
                        .joinToString(";") { "${it.key}=${it.value}" }
                    
                    writer.appendLine(
                        listOf(
                            event.timestamp.toString(),
                            event.eventType.name,
                            event.stage.name,
                            event.durationMs.toString(),
                            event.metadata["sessionId"]?.toString() ?: "",
                            metadataString
                        ).joinToString(",")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed to flush events to disk", e)
        }
    }
    
    /**
     * Export all logs to a single CSV file
     */
    fun exportAllLogs(): File? {
        return try {
            val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, LOG_DIR)
            if (!exportDir.exists()) exportDir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(exportDir, "comprehensive_latency_logs_$timestamp.csv")
            
            // Flush any remaining events
            flushEventsToDisk()
            
            // Combine session and event data
            val sessionFile = getSessionFile()
            val eventsFile = getEventsFile()
            
            if (sessionFile.exists() || eventsFile.exists()) {
                exportFile.writeText("")
                
                if (sessionFile.exists()) {
                    exportFile.appendText("=== SESSIONS ===\n")
                    exportFile.appendText(sessionFile.readText())
                    exportFile.appendText("\n\n")
                }
                
                if (eventsFile.exists()) {
                    exportFile.appendText("=== EVENTS ===\n")
                    exportFile.appendText(eventsFile.readText())
                }
                
                Log.d(loggerTag, "Exported comprehensive logs to: ${exportFile.absolutePath}")
                exportFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed to export logs", e)
            null
        }
    }
    
    /**
     * Get performance summary
     */
    fun getPerformanceSummary(): Map<String, Any> {
        val sessionFile = getSessionFile()
        if (!sessionFile.exists()) return emptyMap()
        
        val lines = sessionFile.readLines()
        if (lines.size <= 1) return emptyMap() // Only header
        
        val sessions = lines.drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size >= 5) {
                mapOf(
                    "duration" to parts[3].toLongOrNull() ?: 0L,
                    "success" to parts[4].toBoolean(),
                    "roiCount" to parts[6].toIntOrNull() ?: 0
                )
            } else null
        }
        
        if (sessions.isEmpty()) return emptyMap()
        
        val totalSessions = sessions.size
        val successfulSessions = sessions.count { it["success"] == true }
        val averageDuration = sessions.map { it["duration"] as Long }.average()
        val averageRoiCount = sessions.map { it["roiCount"] as Int }.average()
        
        return mapOf(
            "totalSessions" to totalSessions,
            "successfulSessions" to successfulSessions,
            "successRate" to (successfulSessions.toDouble() / totalSessions),
            "averageDurationMs" to averageDuration,
            "averageRoiCount" to averageRoiCount
        )
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        try {
            getSessionFile().delete()
            getEventsFile().delete()
            events.clear()
            activeSessions.clear()
            Log.d(loggerTag, "Cleared all latency logs")
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed to clear logs", e)
        }
    }
    
    private fun getSessionFile(): File {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()
        return File(logDir, SESSION_FILE)
    }
    
    private fun getEventsFile(): File {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()
        return File(logDir, EVENTS_FILE)
    }
}
