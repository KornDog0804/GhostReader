package com.korndog.ghostreader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DOUBLE_TAP_MS = 300L
private const val LONG_PRESS_MS = 500L
private const val SWIPE_PX = 65f

/**
 * Gesture layer shared by the CBZ and image-folder comic readers.
 *
 *  - pinch (2 fingers): zoom 1x-4x around the fingers; one finger pans while zoomed
 *  - swipe left/right at 1x: next/previous page (same 65px threshold the readers already used)
 *  - tap left 30% / right 30%: previous / next page, immediately
 *  - tap centre: toggles the reader controls (after a short wait, so a double tap can claim it)
 *  - double tap centre: zoom to 2.5x there; double tap while zoomed: back to 1x
 * Zoom resets whenever [pageKey] changes. Previous/Next buttons stay in the readers; the same
 * two actions are also exposed as TalkBack custom actions.
 */
@Composable
internal fun GesturePage(
 pageKey: Any?, canPrev: Boolean, canNext: Boolean,
 onPrev: () -> Unit, onNext: () -> Unit, onCenterTap: () -> Unit,
 modifier: Modifier = Modifier,
 content: @Composable BoxScope.() -> Unit
) {
 var scale by remember { mutableFloatStateOf(1f) }
 var offset by remember { mutableStateOf(Offset.Zero) }
 LaunchedEffect(pageKey) { scale = 1f; offset = Offset.Zero }
 val prev by rememberUpdatedState(onPrev)
 val next by rememberUpdatedState(onNext)
 val center by rememberUpdatedState(onCenterTap)
 val hasPrev by rememberUpdatedState(canPrev)
 val hasNext by rememberUpdatedState(canNext)
 Box(
  modifier.clipToBounds()
   .semantics {
    customActions = listOf(
     CustomAccessibilityAction("Next page") { if (hasNext) { next(); true } else false },
     CustomAccessibilityAction("Previous page") { if (hasPrev) { prev(); true } else false }
    )
   }
   .pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    coroutineScope {
     val cs: CoroutineScope = this
     var pending: Job? = null
     var lastTapAt = -10_000L
     awaitEachGesture {
      fun tap(pos: Offset, now: Long) {
       val w = size.width.toFloat()
       val h = size.height.toFloat()
       val isDouble = now - lastTapAt < DOUBLE_TAP_MS
       if (scale > 1.01f) {
        if (isDouble) { scale = 1f; offset = Offset.Zero; lastTapAt = -10_000L } else lastTapAt = now
        return
       }
       when {
        pos.x < w * 0.30f -> { lastTapAt = -10_000L; if (hasPrev) prev() }
        pos.x > w * 0.70f -> { lastTapAt = -10_000L; if (hasNext) next() }
        isDouble -> {
         pending?.cancel(); pending = null; lastTapAt = -10_000L
         val c = pos - Offset(w / 2f, h / 2f)
         val maxX = w * (DOUBLE_TAP_ZOOM - 1f) / 2f
         val maxY = h * (DOUBLE_TAP_ZOOM - 1f) / 2f
         val o = c * (1f - DOUBLE_TAP_ZOOM)
         scale = DOUBLE_TAP_ZOOM
         offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        else -> {
         lastTapAt = now
         pending?.cancel()
         pending = cs.launch { delay(DOUBLE_TAP_MS); center(); pending = null }
        }
       }
      }

      val down = awaitFirstDown(requireUnconsumed = false)
      var totalPan = Offset.Zero
      var multi = false
      var moved = false
      var swipeDx = 0f
      var lastTime = down.uptimeMillis
      do {
       val event = awaitPointerEvent()
       if (event.changes.count { it.pressed } > 1) multi = true
       val zoomChange = event.calculateZoom()
       val pan = event.calculatePan()
       val centroid = event.calculateCentroid(useCurrent = false)
       totalPan += pan
       lastTime = event.changes.first().uptimeMillis
       if (!moved && (multi || totalPan.getDistance() > slop)) moved = true
       if (moved) {
        if (multi || scale > 1.01f) {
         val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
         val ratio = newScale / scale
         val c = if (centroid.isSpecified) centroid - Offset(size.width / 2f, size.height / 2f) else Offset.Zero
         val maxX = size.width * (newScale - 1f) / 2f
         val maxY = size.height * (newScale - 1f) / 2f
         val o = (offset - c) * ratio + c + pan
         scale = newScale
         offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        } else {
         swipeDx += pan.x
        }
        event.changes.forEach { if (it.positionChanged()) it.consume() }
       }
      } while (event.changes.any { it.pressed })

      if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
      if (!moved) {
       if (lastTime - down.uptimeMillis < LONG_PRESS_MS) tap(down.position, down.uptimeMillis)
      } else if (!multi && scale == 1f && abs(swipeDx) > SWIPE_PX) {
       if (swipeDx < 0f && hasNext) next() else if (swipeDx > 0f && hasPrev) prev()
      }
     }
    }
   }
 ) {
  Box(
   Modifier.fillMaxSize().graphicsLayer {
    scaleX = scale; scaleY = scale
    translationX = offset.x; translationY = offset.y
   },
   content = content
  )
 }
}
