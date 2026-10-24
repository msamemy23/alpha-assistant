package com.alphainternational.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay

/**
 * Puts the cursor (and the keyboard) in a text box when the owner arrived through "Ask" (a shortcut, the Quick Settings tile or the widget). Whichever
 * screen has the box on it when the request comes in takes it; the request is cleared once the box has focus, so going back to a chat later does not
 * pop the keyboard up again.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun Modifier.focusOnAsk(activity:MainActivity):Modifier {
    val focus=remember {FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    val stamp=activity.composerFocus
    LaunchedEffect(stamp) {
        if(stamp==0L) return@LaunchedEffect
        // The screen is still opening, so the box may not be ready on the first try.
        var tries=0
        while(tries++<6) {
            delay(150)
            if(runCatching {focus.requestFocus()}.isSuccess) {keyboard?.show();break}
        }
        activity.composerFocused()
    }
    return focusRequester(focus)
}
