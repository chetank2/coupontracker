package com.example.coupontracker.extraction

import com.example.coupontracker.universal.PatternLearningEngine
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ProgressiveExtractionServiceTest {

    private val service = ProgressiveExtractionService(
        structuredExtractor = StructuredFieldExtractor(),
        semanticExtractor = SemanticFieldExtractor(),
        heuristicExtractor = HeuristicFieldExtractor(),
        learnedPatternEngine = mockk(relaxed = true),
        defaultProvider = DefaultFieldProvider(),
        llmService = null,
        extractionLearningIntegration = null
    )

    @Test
    fun `parseDate handles comma separated month format`() {
        val parsed = service.parseDate("31 May, 2025")

        assertNotNull("Expected the date parser to handle comma separated month", parsed)

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2025-05-31", formatter.format(parsed!!))
    }
}
