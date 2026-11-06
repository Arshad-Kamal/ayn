package com.arshad.ayn.ui.tv.common

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * The text fields in [content] ask the system keyboard not to learn what is typed
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`, which no Compose option sets), so it can't offer one
 * profile's typing to another (§ B3p). Their own `KeyboardOptions` turn autocorrect off.
 */
@Composable
internal fun PrivateTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(NoPersonalizedLearning, content)
}

private val NoPersonalizedLearning = PlatformTextInputInterceptor { request, nextHandler ->
    val learningOff = object : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            request.createInputConnection(outAttributes).also {
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
    }
    nextHandler.startInputMethod(learningOff)
}
