package com.glasslauncher.tv.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.Motion
import com.glasslauncher.tv.ui.theme.tvSpring

enum class MenuTab(@StringRes val label: Int) {
    HOME(R.string.menu_home),
    SEARCH(R.string.menu_search),
    WATCH(R.string.menu_watch),
    LIBRARY(R.string.menu_library),
    SETTINGS(R.string.menu_settings),
}

private data class TabSpan(val left: Float, val width: Float)

/**
 * tvOS-style top menu. Focus selects a tab; the blue underline glides to it on a spring.
 * When focus is down in the content rows the bar shrinks and fades ([compact]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MenuBar(
    selected: MenuTab,
    compact: Boolean,
    requesters: Map<MenuTab, FocusRequester>,
    onTabFocused: (MenuTab) -> Unit,
    onTabClicked: (MenuTab) -> Unit,
    modifier: Modifier = Modifier,
    /** Deep in the rows: slide the bar up out of view (it springs back when focus heads up). */
    hidden: Boolean = false,
) {
    // Physical x positions inside the row content area (RTL-safe: draw space is physical).
    val spans = remember { mutableStateMapOf<MenuTab, TabSpan>() }
    val target = spans[selected]
    val indLeft by animateFloatAsState(target?.left ?: 0f, tvSpring(), label = "indLeft")
    val indWidth by animateFloatAsState(target?.width ?: 0f, tvSpring(), label = "indWidth")
    val barScale by animateFloatAsState(if (compact) 0.88f else 1f, tvSpring(), label = "barScale")
    val barAlpha by animateFloatAsState(if (compact) 0.5f else 1f, tween(Motion.FADE), label = "barAlpha")
    val hide by animateFloatAsState(if (hidden) 1f else 0f, tvSpring(), label = "barHide")

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = barScale
                scaleY = barScale
                translationY = -90.dp.toPx() * hide
                alpha = barAlpha * (1f - hide).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(Glass.MenuFill)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .drawBehind {
                if (indWidth > 1f) {
                    val h = 3.dp.toPx()
                    val inset = 20.dp.toPx()
                    drawRoundRect(
                        color = Glass.Accent,
                        topLeft = Offset(indLeft + inset, size.height - h),
                        size = Size((indWidth - 2 * inset).coerceAtLeast(h), h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
            }
            // Coming up from the rows always lands on the selected tab, like tvOS.
            .focusProperties { enter = { requesters.getValue(selected) } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuTab.entries.forEach { tab ->
            MenuItem(
                tab = tab,
                selected = tab == selected,
                requester = requesters.getValue(tab),
                onFocused = { onTabFocused(tab) },
                onClick = { onTabClicked(tab) },
                modifier = Modifier.onGloballyPositioned {
                    spans[tab] = TabSpan(it.positionInParent().x, it.size.width.toFloat())
                },
            )
        }
    }
}

@Composable
private fun MenuItem(
    tab: MenuTab,
    selected: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(150), label = "tabBg")
    val fg by animateColorAsState(
        targetValue = when {
            focused -> Color.Black
            selected -> Glass.TextPrimary
            else -> Glass.TextSecondary
        },
        animationSpec = tween(150),
        label = "tabFg",
    )
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tvSpring(), label = "tabScale")

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .focusRequester(requester)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) onFocused()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(tab.label),
            color = fg,
            fontSize = 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
