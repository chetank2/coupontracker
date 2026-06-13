package com.example.coupontracker.ui.components

import android.content.pm.ApplicationInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import java.text.SimpleDateFormat
import java.util.*

/**
 * Primary button with the brand style
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    BrandButton(
        text = if (loading) "Loading..." else text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        tier = BrandButtonTier.Primary,
        leadingIcon = leadingIcon,
    )
}

/**
 * Secondary button with the brand style
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    BrandButton(
        text = if (loading) "Loading..." else text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        tier = BrandButtonTier.Secondary,
        leadingIcon = leadingIcon,
    )
}

/**
 * Outlined button with the brand style
 */
@Composable
fun OutlinedBrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = BrandShapes.ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = text)
        }
    }
}

/**
 * Text button with the brand style
 */
@Composable
fun TextBrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = BrandShapes.ButtonShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(alpha = 0.38f)
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = contentColor,
                strokeWidth = 2.dp
            )
        } else {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = text)
        }
    }
}

/**
 * Brand card with consistent styling
 */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    shape: Shape = BrandShapes.CardShape,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        ),
        shape = shape,
        elevation = elevation,
        colors = colors,
        content = content
    )
}

/**
 * Enhanced text field with brand styling and error handling
 */
@Composable
fun BrandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    isPassword: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = 1,
    singleLine: Boolean = true
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = label?.let { { Text(text = it) } },
            placeholder = placeholder?.let { { Text(text = it) } },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            trailingIcon = when {
                isPassword -> {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                trailingIcon != null && onTrailingIconClick != null -> {
                    {
                        IconButton(onClick = onTrailingIconClick) {
                            Icon(
                                imageVector = trailingIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                isError -> {
                    {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> null
            },
            isError = isError,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onImeAction()
                },
                onNext = {
                    onImeAction()
                }
            ),
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            enabled = enabled,
            readOnly = readOnly,
            maxLines = maxLines,
            singleLine = singleLine,
            shape = BrandShapes.InputShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                errorLabelColor = MaterialTheme.colorScheme.error
            )
        )

        AnimatedVisibility(
            visible = isError && !errorMessage.isNullOrBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = errorMessage ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Section header with consistent styling
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        action?.invoke()
    }
}

/**
 * Empty state component with consistent styling
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Loading indicator with consistent styling
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )

        message?.let {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Status chip with consistent styling
 */
@Composable
fun StatusChip(
    text: String,
    type: StatusType,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = when (type) {
        StatusType.SUCCESS -> Pair(BrandColors.Success.copy(alpha = 0.12f), BrandColors.Success)
        StatusType.ERROR -> Pair(BrandColors.Error.copy(alpha = 0.12f), BrandColors.Error)
        StatusType.WARNING -> Pair(BrandColors.Warning.copy(alpha = 0.12f), BrandColors.Warning)
        StatusType.INFO -> Pair(BrandColors.Info.copy(alpha = 0.12f), BrandColors.Info)
        StatusType.NEUTRAL -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        modifier = modifier,
        shape = BrandShapes.PillShape,
        color = backgroundColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

enum class StatusType {
    SUCCESS, ERROR, WARNING, INFO, NEUTRAL
}

/**
 * Date formatter for consistent date display
 */
object DateFormatter {
    private val fullDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val monthDayFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

    private const val UNKNOWN_EXPIRY_TEXT = "Unknown"

    fun formatFull(date: Date?): String = date?.let(fullDateFormat::format) ?: UNKNOWN_EXPIRY_TEXT
    fun formatShort(date: Date?): String = date?.let(shortDateFormat::format) ?: UNKNOWN_EXPIRY_TEXT
    fun formatMonthDay(date: Date?): String = date?.let(monthDayFormat::format) ?: UNKNOWN_EXPIRY_TEXT

    fun getExpiryStatus(expiryDate: Date?): StatusType {
        val date = expiryDate ?: return StatusType.INFO
        val now = Calendar.getInstance().time
        val daysUntilExpiry = ((date.time - now.time) / (1000 * 60 * 60 * 24)).toInt()

        return when {
            date.before(now) -> StatusType.ERROR
            daysUntilExpiry <= 7 -> StatusType.WARNING
            else -> StatusType.SUCCESS
        }
    }

    fun getExpiryText(expiryDate: Date?): String {
        val date = expiryDate ?: return UNKNOWN_EXPIRY_TEXT
        val now = Calendar.getInstance().time
        val daysUntilExpiry = ((date.time - now.time) / (1000 * 60 * 60 * 24)).toInt()

        return when {
            date.before(now) -> "Expired"
            daysUntilExpiry == 0 -> "Expires today"
            daysUntilExpiry == 1 -> "Expires tomorrow"
            daysUntilExpiry <= 7 -> "Expires in $daysUntilExpiry days"
            else -> "Expires ${formatShort(date)}"
        }
    }
}

/**
 * Enhanced coupon card with consistent styling and image thumbnail
 */
@Composable
fun EnhancedCouponCard(
    storeName: String,
    description: String,
    expiryDate: Date?,
    amount: Double? = null,
    code: String? = null,
    imageUri: String? = null,
    onClick: () -> Unit,
    onCopyCode: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    cashbackDisplayText: String? = null,
    debugSnapshot: ExtractionDebugSnapshot? = null
) {
    val expiryStatus = DateFormatter.getExpiryStatus(expiryDate)
    val expiryText = DateFormatter.getExpiryText(expiryDate)
    val context = LocalContext.current
    val isDebugBuild = remember {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = BrandShapes.CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Image thumbnail (if available)
            if (!imageUri.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(120.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Coupon Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    )
                }
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            ) {
                // Store name and expiry status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = storeName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    StatusChip(
                        text = expiryText,
                        type = expiryStatus
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Coupon code badge with copy action
                if (!code.isNullOrBlank() && onCopyCode != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(BrandShapes.PillShape)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = BrandShapes.PillShape
                                )
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                onClick = { onCopyCode(code) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (isDebugBuild && debugSnapshot != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ExtractionDebugPanel(snapshot = debugSnapshot)
                }
            }
        }
    }
}

/**
 * Legacy version of EnhancedCouponCard for backward compatibility
 */
@Composable
fun EnhancedCouponCard(
        storeName: String,
        description: String,
        expiryDate: Date?,
        amount: Double? = null,
        code: String? = null,
        onClick: () -> Unit,
        onCopyCode: ((String) -> Unit)? = null,
        modifier: Modifier = Modifier
) {
    // Call the new version with imageUri = null and cashbackDisplayText = null (legacy fallback)
    EnhancedCouponCard(
        storeName = storeName,
        description = description,
        expiryDate = expiryDate,
        amount = amount,
        code = code,
        imageUri = null,
        onClick = onClick,
        onCopyCode = onCopyCode,
        modifier = modifier,
        cashbackDisplayText = null,
        debugSnapshot = null
    )
}
