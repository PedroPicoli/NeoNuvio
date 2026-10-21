package com.nuvio.tv.ui.util

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun rememberLongPressKeyTracker(
    timeoutMillis: Long = 700L
): LongPressKeyTracker = remember { LongPressKeyTracker(timeoutMillis) }

class LongPressKeyTracker(
    val timeoutMillis: Long = 700L
) {
    private var pressedKeyCode: Int? = null
    private var pressedAtMillis: Long = 0L
    private var handledLongPress = false

    var isHolding: Boolean by mutableStateOf(false)
        private set

    fun handle(
        event: KeyEvent,
        isLongPressKey: (Int) -> Boolean,
        onLongPress: () -> Unit
    ): Boolean {
        if (!isLongPressKey(event.keyCode)) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event, onLongPress)
            KeyEvent.ACTION_UP -> handleUp(event, onLongPress)
            else -> false
        }
    }

    private fun handleDown(event: KeyEvent, onLongPress: () -> Unit): Boolean {
        if (event.repeatCount == 0 || pressedKeyCode != event.keyCode) {
            pressedKeyCode = event.keyCode
            pressedAtMillis = event.eventTime
            handledLongPress = false
            isHolding = true
        }

        if (event.repeatCount > 0) {
            if (!handledLongPress && (event.isLongPress || heldDurationMillis(event) >= timeoutMillis)) {
                handledLongPress = true
                isHolding = false
                onLongPress()
            }
            return true
        }

        return false
    }

    private fun handleUp(event: KeyEvent, onLongPress: () -> Unit): Boolean {
        val wasHandled = handledLongPress && pressedKeyCode == event.keyCode
        val isLongPress = !wasHandled && heldDurationMillis(event) >= timeoutMillis

        reset()

        if (isLongPress) {
            onLongPress()
            return true
        }

        return wasHandled
    }

    private fun heldDurationMillis(event: KeyEvent): Long {
        val startedAt = if (pressedKeyCode == event.keyCode) pressedAtMillis else event.downTime
        return event.eventTime - startedAt
    }

    fun reset() {
        pressedKeyCode = null
        pressedAtMillis = 0L
        handledLongPress = false
        isHolding = false
    }
}
