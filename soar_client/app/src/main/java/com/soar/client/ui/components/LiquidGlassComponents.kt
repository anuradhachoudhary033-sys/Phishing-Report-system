package com.soar.client.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.ui.shaders.liquidDropletShader
import com.soar.client.ui.shaders.liquidGlass
import com.soar.client.ui.shaders.staticCircleGlass
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarDanger
import com.soar.client.ui.theme.SoarSurfaceGlass
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary

import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    borderWidth: Dp = 1.dp,
    tintColor: Color = SoarSurfaceGlass,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(shape = shape, borderWidth = borderWidth, tintColor = tintColor)
    ) {
        content()
    }
}

@Composable
fun LiquidGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                borderWidth = 1.dp
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (onBackClick != null) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Drawer",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
    }
}

/**
 * Liquid Glass Navigation Bar with True Optical Refraction & Liquid Droplet Physics.
 *
 * Left: Elongated liquid glass capsule pill with:
 *   0. Inbox button
 *   1. Outbox / Sent button
 *   2. Compose button (rounded square with [+] icon)
 * Right: Standalone circular liquid glass button with User Profile icon that opens Side Bar Drawer.
 *
 * The active droplet is rendered ENTIRELY inside the AGSL fragment shader applied to the
 * parent pill layer (fill + border + icons), so it genuinely refracts everything under it.
 *
 * Motion uses a Dual-Spring (Head + Tail) model:
 *   head = fast leading spring, tail = slower underdamped trailing spring.
 *   |head - tail| stretches radiusX while radiusY compresses (area conservation),
 *   then the underdamped tail overshoots -> jelly wobble -> settles into a 66x66dp circle.
 */
@Composable
fun SoarLiquidBottomBar(
    selectedIndex: Int, // 0 = INBOX, 1 = SENT, 2 = COMPOSE (+)
    onInboxClick: () -> Unit,
    onSentClick: () -> Unit,
    onComposeClick: () -> Unit,
    onProfileClick: () -> Unit,
    backdropLayer: GraphicsLayer? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val pillHeight = 66.dp
    val slotWidth = 72.dp
    val pillWidth = 216.dp // 3 slots * 72.dp

    val slotWidthPx = with(density) { slotWidth.toPx() }
    val baseRadiusPx = with(density) { 33.dp.toPx() }
    val pillCenterYPx = with(density) { (pillHeight / 2).toPx() }
    val minCenterX = slotWidthPx * 0.5f
    val maxCenterX = slotWidthPx * 2.5f

    var pillRootOffset by remember { mutableStateOf(Offset.Zero) }
    var profileRootOffset by remember { mutableStateOf(Offset.Zero) }

    var isDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(minCenterX) }

    val restCenterX = selectedIndex.coerceIn(0, 2) * slotWidthPx + slotWidthPx * 0.5f
    val targetCenterX = if (isDragging) dragX else restCenterX

    // Leading edge: fast, nearly critically damped
    val headX by animateFloatAsState(
        targetValue = targetCenterX,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "dropletHeadX"
    )
    // Trailing edge: slower + underdamped -> lag (stretch) and overshoot (wobble)
    val tailX by animateFloatAsState(
        targetValue = targetCenterX,
        animationSpec = spring(dampingRatio = 0.64f, stiffness = 170f),
        label = "dropletTailX"
    )

    // NOTE: headX / tailX are only read inside these lambdas (from graphicsLayer / offset blocks),
    // so animation frames never trigger recomposition of the bar.
    val lensCenter: () -> Offset = { Offset((headX + tailX) * 0.5f, pillCenterYPx) }
    val lensRadius: () -> Offset = { liquidDropletRadii(headX, tailX, baseRadiusPx) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Layer order matters:
        //   clip(Capsule)            -> final output (incl. a stretched droplet) is clipped to the pill
        //   liquidDropletShader      -> render-effect layer that records EVERYTHING below it:
        //   drawWithContent          -> live slice of scrolling email backdrop underneath + dark glass tint
        //   children (icons)         -> icons are refracted by the droplet
        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(pillHeight)
                .onGloballyPositioned { coords ->
                    pillRootOffset = coords.positionInRoot()
                }
                .clip(CircleShape)
                .liquidDropletShader(lensCenter = lensCenter, lensRadius = lensRadius)
                .drawWithContent {
                    if (backdropLayer != null) {
                        translate(-pillRootOffset.x, -pillRootOffset.y) {
                            drawLayer(backdropLayer)
                        }
                    }
                    drawRect(color = SoarSurfaceGlass)
                    drawContent()
                }
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                .pointerInput(slotWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragX = offset.x.coerceIn(minCenterX, maxCenterX)
                        },
                        onDragEnd = {
                            isDragging = false
                            val snappedIndex = (dragX / slotWidthPx.coerceAtLeast(1f)).toInt().coerceIn(0, 2)
                            when (snappedIndex) {
                                0 -> onInboxClick()
                                1 -> onSentClick()
                                2 -> onComposeClick()
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragX = (dragX + dragAmount).coerceIn(minCenterX, maxCenterX)
                        }
                    )
                }
        ) {
            // Fallback droplet for API < 33 (no RuntimeShader): same physics via scale.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((lensCenter().x - baseRadiusPx).roundToInt(), 0) }
                        .size(pillHeight)
                        .graphicsLayer {
                            val r = lensRadius()
                            scaleX = r.x / baseRadiusPx
                            scaleY = r.y / baseRadiusPx
                        }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape)
                )
            }

            // 3 white action icons at fixed opacity, drawn INSIDE the shader layer so the
            // droplet magnifies/bends their strokes (no extra icon scaling on top).
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 0. Inbox button
                Box(
                    modifier = Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onInboxClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = "Inbox",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 1. Outbox / Sent button (Paper plane)
                Box(
                    modifier = Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSentClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Outbox",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 2. Compose button: Rounded square with [+]
                Box(
                    modifier = Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onComposeClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .border(
                                width = 1.8.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(9.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Compose",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Standalone circular glass button with Profile icon (Opens Side Bar).
        // Shader layer placed before background/border so the fill + rim are refracted too.
        val profileRadiusPx = with(density) { (pillHeight / 2).toPx() }
        Box(
            modifier = Modifier
                .size(pillHeight)
                .onGloballyPositioned { coords ->
                    profileRootOffset = coords.positionInRoot()
                }
                .clip(CircleShape)
                .staticCircleGlass(radius = profileRadiusPx)
                .drawWithContent {
                    if (backdropLayer != null) {
                        translate(-profileRootOffset.x, -profileRootOffset.y) {
                            drawLayer(backdropLayer)
                        }
                    }
                    drawRect(color = SoarSurfaceGlass)
                    drawContent()
                }
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onProfileClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Menu / Profile",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Elliptical droplet radii from the head/tail spring separation.
 *
 *   radiusX = min(R0 + |head - tail| * 0.42, 1.65 * R0)
 *   radiusY = R0 * (R0 / radiusX)^0.45, clamped to [0.74 * R0, R0]   (approximate area conservation)
 *
 * At rest (head == tail) this returns exactly (R0, R0) -> a 66x66dp circle.
 */
internal fun liquidDropletRadii(headX: Float, tailX: Float, baseRadiusPx: Float): Offset {
    val stretchDelta = abs(headX - tailX)
    val radiusX = (baseRadiusPx + stretchDelta * 0.42f).coerceAtMost(baseRadiusPx * 1.65f)
    val radiusY = (baseRadiusPx * (baseRadiusPx / radiusX).toDouble().pow(0.45).toFloat())
        .coerceIn(baseRadiusPx * 0.74f, baseRadiusPx)
    return Offset(radiusX, radiusY)
}

data class BottomNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
    val isAlertBadge: Boolean = false
)

/**
 * Floating Liquid Glass Navigation Bar.
 *
 * Icons and text are brought forwards with high contrast, sharp rendering, and active illuminated capsules.
 */
@Composable
fun LiquidGlassBottomNavigationBar(
    items: List<BottomNavItem>,
    currentRoute: String,
    onItemSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        LiquidGlassCard(
            shape = RoundedCornerShape(100.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route

                    val activeColor = SoarCyan
                    val inactiveColor = Color(0xFFD4D4D8) // Polished neutral steel-silver

                    val itemColor by animateColorAsState(
                        targetValue = if (isSelected) activeColor else inactiveColor,
                        animationSpec = tween(200),
                        label = "navColor"
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (isSelected) SoarCyan.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = SoarCyan.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(24.dp)
                                    )
                                } else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onItemSelected(item.route) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = itemColor,
                                    modifier = Modifier.size(26.dp)
                                )
                                if (item.badgeCount > 0) {
                                    LiquidGlassPill(
                                        count = item.badgeCount,
                                        isDanger = item.isAlertBadge,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.title,
                                fontSize = 11.sp,
                                color = itemColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiquidGlassPill(
    count: Int,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isDanger) SoarDanger else SoarCyan)
            .border(0.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
