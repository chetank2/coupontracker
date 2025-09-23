package com.example.coupontracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.coupontracker.ui.theme.BrandSpacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A tooltip overlay that provides guidance to users
 */
@Composable
fun TooltipOverlay(
    visible: Boolean,
    message: String,
    targetAlignment: Alignment = Alignment.Center,
    offsetY: Int = 0,
    onDismiss: () -> Unit
) {
    val visibleState = remember { MutableTransitionState(false) }
    
    LaunchedEffect(visible) {
        visibleState.targetState = visible
        if (visible) {
            // Auto-dismiss after 5 seconds
            delay(5000)
            onDismiss()
        }
    }
    
    val transition = updateTransition(visibleState, label = "tooltip")
    val alpha by transition.animateFloat(
        label = "alpha",
        transitionSpec = { tween(durationMillis = 500) }
    ) { if (it) 0.9f else 0f }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha * 0.5f)
            .background(Color.Black.copy(alpha = 0.5f))
            .zIndex(10f)
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(500)) + 
                   slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            exit = fadeOut(animationSpec = tween(500)) + 
                   slideOutVertically(animationSpec = tween(500)),
            modifier = Modifier
                .align(targetAlignment)
                .offset { IntOffset(0, offsetY) }
        ) {
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.8f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(BrandSpacing.Medium),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = BrandSpacing.Medium)
                    )
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Got it")
                    }
                }
            }
        }
    }
}

/**
 * A pulsating highlight that draws attention to a specific area
 */
@Composable
fun PulsatingHighlight(
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    var pulsating by remember { mutableStateOf(true) }
    val transition = updateTransition(pulsating, label = "pulse")
    val scale by transition.animateFloat(
        label = "scale",
        transitionSpec = { 
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ) 
        }
    ) { if (it) 1.1f else 0.9f }
    
    LaunchedEffect(pulsating) {
        if (visible) {
            delay(800)
            pulsating = !pulsating
        }
    }
    
    if (visible) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
                .padding(4.dp * scale)
        )
    }
}
