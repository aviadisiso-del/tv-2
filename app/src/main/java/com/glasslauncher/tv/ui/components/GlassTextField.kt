package com.glasslauncher.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.GlassFonts

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * TV-friendly text input: a glass button until you press OK, then a real text field
 * (so scrolling past it never pops up the on-screen keyboard). Done submits.
 */
@Composable
fun GlassTextField(
    value: String,
    placeholder: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    forceLtr: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val buttonRequester = remember { FocusRequester() }

    if (!editing) {
        // After Done, put focus back on the button instead of losing it.
        LaunchedEffect(Unit) {
            if (returnFocus) {
                withFrameNanos { }
                runCatching { buttonRequester.requestFocus() }
                returnFocus = false
            }
        }
        GlassButton(
            text = value.ifEmpty { placeholder },
            onClick = { editing = true },
            modifier = modifier.focusRequester(buttonRequester),
        )
        return
    }

    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }

    val direction = if (forceLtr) LayoutDirection.Ltr else LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 19.sp,
                fontFamily = GlassFonts.forText(text.text),
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                returnFocus = true
                editing = false
                onSubmit(text.text.trim())
            }),
            modifier = modifier
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus) editing = false // focus moved away: cancel edit
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .clip(PillShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .border(2.dp, Glass.Accent, PillShape)
                        .padding(horizontal = 26.dp, vertical = 13.dp)
                ) {
                    if (text.text.isEmpty()) {
                        Text(placeholder, color = Glass.TextSecondary, fontSize = 19.sp)
                    }
                    inner()
                }
            },
        )
    }
}
