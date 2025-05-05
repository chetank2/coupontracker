package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.coupontracker.util.ThemeMode

/**
 * A component that allows the user to select a theme mode
 */
@Composable
fun ThemeSelector(
    selectedThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(16.dp)
    ) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        ThemeOption(
            text = "Light",
            icon = Icons.Default.LightMode,
            selected = selectedThemeMode == ThemeMode.LIGHT,
            onClick = { onThemeModeSelected(ThemeMode.LIGHT) }
        )
        
        ThemeOption(
            text = "Dark",
            icon = Icons.Default.DarkMode,
            selected = selectedThemeMode == ThemeMode.DARK,
            onClick = { onThemeModeSelected(ThemeMode.DARK) }
        )
        
        ThemeOption(
            text = "System Default",
            icon = Icons.Default.SettingsBrightness,
            selected = selectedThemeMode == ThemeMode.SYSTEM,
            onClick = { onThemeModeSelected(ThemeMode.SYSTEM) }
        )
    }
}

@Composable
private fun ThemeOption(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null // null because we're handling the click on the row
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
