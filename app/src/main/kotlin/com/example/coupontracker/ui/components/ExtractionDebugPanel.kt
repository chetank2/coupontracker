package com.example.coupontracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.debug.StageStatus
import com.example.coupontracker.ui.theme.BrandColors

private const val HEALTHY_THRESHOLD = 75
private const val DEGRADED_THRESHOLD = 45

@Composable
fun ExtractionDebugPanel(
    snapshot: ExtractionDebugSnapshot,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val overallStatus = statusForScore(snapshot.overallScore)
            Text(
                text = "Debug score: ${snapshot.overallScore}",
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(overallStatus)
            )

            snapshot.primaryCause?.let { cause ->
                Text(
                    text = "Primary culprit: ${cause.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandColors.Error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            snapshot.stageScores.forEach { stage ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stage.component.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stage.score.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(stage.status)
                    )
                }
                if (stage.notes.isNotEmpty()) {
                    Text(
                        text = stage.notes.first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            if (snapshot.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = snapshot.notes.joinToString(separator = " • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun statusColor(status: StageStatus): Color = when (status) {
    StageStatus.HEALTHY -> BrandColors.Success
    StageStatus.DEGRADED -> BrandColors.Warning
    StageStatus.FAILED -> BrandColors.Error
}

private fun statusForScore(score: Int): StageStatus = when {
    score >= HEALTHY_THRESHOLD -> StageStatus.HEALTHY
    score >= DEGRADED_THRESHOLD -> StageStatus.DEGRADED
    else -> StageStatus.FAILED
}
