package com.example.coupontracker.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.R
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.CouponTrackerTheme

/**
 * Frosted-glass surface used as the base for vault cards, sheets, and pinned action bars.
 *
 * On API 31+ (Android S) renders a real blur backdrop tinted with [tint].
 * On older API levels falls back to a denser tint composited with a low-alpha noise
 * texture (`R.drawable.glass_noise`) so the surface still feels layered rather than flat.
 *
 * A 1.dp theme outline hairline border is always drawn on top to define the edge.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = BrandShapes.Large,
    tint: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    val stroke = MaterialTheme.colorScheme.outline
    Box(modifier = modifier.clip(shape)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(24.dp)
                    .background(tint.copy(alpha = 0.72f))
            )
        } else {
            val noise: Painter = painterResource(id = R.drawable.glass_noise)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = 0.92f))
                    .paint(noise, contentScale = ContentScale.Crop, alpha = 0.06f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(BorderStroke(BrandSpacing.Hairline, stroke), shape)
        )
        content()
    }
}

@Preview(name = "Glass — Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Glass — Light")
@Composable
private fun GlassSurfacePreview() {
    CouponTrackerTheme {
        GlassSurface(Modifier.size(240.dp, 120.dp)) {
            Text("Glass", Modifier.padding(16.dp))
        }
    }
}
