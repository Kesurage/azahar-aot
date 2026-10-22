// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.EmulationMenuSettings

/**
 * Custom [BitmapDrawable] that is capable
 * of storing it's own ID.
 *
 * @param res                             [Resources] instance.
 * @param defaultStateBitmap              [Bitmap] of the default state.
 * @param pressedOneDirectionStateBitmap  [Bitmap] of the pressed state in one direction.
 * @param pressedTwoDirectionsStateBitmap [Bitmap] of the pressed state in two direction.
 * @param upId                            Identifier for the up button.
 * @param downId                          Identifier for the down button.
 * @param leftId                          Identifier for the left button.
 * @param rightId                         Identifier for the right button.
 * @param opacity                         0-255 alpha value
 */
class InputOverlayDrawableDpad(
    res: Resources,
    defaultStateBitmap: Bitmap,
    pressedOneDirectionStateBitmap: Bitmap,
    pressedTwoDirectionsStateBitmap: Bitmap,
    val upId: Int,
    val downId: Int,
    val leftId: Int,
    val rightId: Int,
    val opacity: Int
) {
    var trackId: Int
    private var previousTouchX = 0
    private var previousTouchY = 0
    private var controlPositionX = 0
    private var controlPositionY = 0
    val width: Int
    val height: Int
    private val defaultStateBitmap: BitmapDrawable
    private val pressedOneDirectionStateBitmap: BitmapDrawable
    private val pressedTwoDirectionsStateBitmap: BitmapDrawable
    private var upButtonState = false
    private var downButtonState = false
    private var leftButtonState = false
    private var rightButtonState = false

    init {
        this.defaultStateBitmap = BitmapDrawable(res, defaultStateBitmap)
        this.pressedOneDirectionStateBitmap = BitmapDrawable(res, pressedOneDirectionStateBitmap)
        this.pressedTwoDirectionsStateBitmap = BitmapDrawable(res, pressedTwoDirectionsStateBitmap)
        width = this.defaultStateBitmap.intrinsicWidth
        height = this.defaultStateBitmap.intrinsicHeight
        trackId = -1
    }

    fun updateStatus(
        event: MotionEvent,
        pointerIndex: Int,
        hasActiveButtons: Boolean,
        dpadSlide: Boolean,
        overlay: InputOverlay
    ): Boolean {
        var isDown = false
        val xPosition = event.getX(pointerIndex).toInt()
        val yPosition = event.getY(pointerIndex).toInt()
        val pointerId = event.getPointerId(pointerIndex)
        val motionEvent = event.action and MotionEvent.ACTION_MASK
        var isActionDown =
            motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_POINTER_DOWN
        if (!isActionDown && EmulationMenuSettings.buttonSlide != ButtonSlidingMode.Disabled.int) {
            isActionDown = motionEvent == MotionEvent.ACTION_MOVE && !hasActiveButtons
        }

        val isActionUp =
            motionEvent == MotionEvent.ACTION_UP || motionEvent == MotionEvent.ACTION_POINTER_UP
        if (isActionDown) {
            // Slightly forgiving hit area so a thumb landing just off the pad still counts
            val margin = (bounds.width() * HIT_MARGIN).toInt()
            val hitArea = Rect(bounds).apply { inset(-margin, -margin) }
            if (!hitArea.contains(xPosition, yPosition)) {
                return false
            }
            isDown = true
            trackId = pointerId
        }
        if (isActionUp) {
            if (trackId != pointerId) {
                return false
            }
            trackId = -1
            upButtonState = false
            downButtonState = false
            leftButtonState = false
            rightButtonState = false
            overlay.hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
            return true
        }
        if (trackId == -1) {
            return false
        }
        if (!dpadSlide && !isActionDown) {
            return false
        }
        for (i in 0 until event.pointerCount) {
            if (trackId != event.getPointerId(i)) {
                continue
            }
            // Finger offset from the centre of the pad, in pixels
            val dx = event.getX(i) - bounds.exactCenterX()
            val dy = event.getY(i) - bounds.exactCenterY()
            val halfSize = bounds.width() / 2f

            val upState = upButtonState
            val downState = downButtonState
            val leftState = leftButtonState
            val rightState = rightButtonState

            val absX = abs(dx)
            val absY = abs(dy)
            if (hypot(dx, dy) < halfSize * CENTER_DEADZONE) {
                // Small dead spot in the very middle only
                upButtonState = false
                downButtonState = false
                leftButtonState = false
                rightButtonState = false
            } else {
                // 8-way by angle: each axis is active when it is at least DIAGONAL_RATIO of the
                // other one. Cardinal directions get wide 55 degree zones, diagonals 35 degrees,
                // so a normal press on an arm never turns into an accidental diagonal.
                val horizontalActive = absX >= absY * DIAGONAL_RATIO
                val verticalActive = absY >= absX * DIAGONAL_RATIO
                upButtonState = verticalActive && dy < 0
                downButtonState = verticalActive && dy > 0
                leftButtonState = horizontalActive && dx < 0
                rightButtonState = horizontalActive && dx > 0
            }

            val stateChanged =
                upState != upButtonState || downState != downButtonState ||
                    leftState != leftButtonState ||
                    rightState != rightButtonState

            if (stateChanged) {
                overlay.hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            } else if (isDown) {
                overlay.hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }

            return stateChanged
        }
        return false
    }

    fun draw(canvas: Canvas) {
        val px = controlPositionX + width / 2
        val py = controlPositionY + height / 2

        // Pressed up
        if (upButtonState && !leftButtonState && !rightButtonState) {
            pressedOneDirectionStateBitmap.alpha = opacity
            pressedOneDirectionStateBitmap.draw(canvas)
            return
        }

        // Pressed down
        if (downButtonState && !leftButtonState && !rightButtonState) {
            canvas.save()
            canvas.rotate(180f, px.toFloat(), py.toFloat())
            pressedOneDirectionStateBitmap.alpha = opacity
            pressedOneDirectionStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Pressed left
        if (leftButtonState && !upButtonState && !downButtonState) {
            canvas.save()
            canvas.rotate(270f, px.toFloat(), py.toFloat())
            pressedOneDirectionStateBitmap.alpha = opacity
            pressedOneDirectionStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Pressed right
        if (rightButtonState && !upButtonState && !downButtonState) {
            canvas.save()
            canvas.rotate(90f, px.toFloat(), py.toFloat())
            pressedOneDirectionStateBitmap.alpha = opacity
            pressedOneDirectionStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Pressed up left
        if (upButtonState && leftButtonState && !rightButtonState) {
            pressedTwoDirectionsStateBitmap.alpha = opacity
            pressedTwoDirectionsStateBitmap.draw(canvas)
            return
        }

        // Pressed up right
        if (upButtonState && !leftButtonState && rightButtonState) {
            canvas.save()
            canvas.rotate(90f, px.toFloat(), py.toFloat())
            pressedTwoDirectionsStateBitmap.alpha = opacity
            pressedTwoDirectionsStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Pressed down left
        if (downButtonState && leftButtonState && !rightButtonState) {
            canvas.save()
            canvas.rotate(270f, px.toFloat(), py.toFloat())
            pressedTwoDirectionsStateBitmap.alpha = opacity
            pressedTwoDirectionsStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Pressed down right
        if (downButtonState && !leftButtonState && rightButtonState) {
            canvas.save()
            canvas.rotate(180f, px.toFloat(), py.toFloat())
            pressedTwoDirectionsStateBitmap.alpha = opacity
            pressedTwoDirectionsStateBitmap.draw(canvas)
            canvas.restore()
            return
        }

        // Not pressed
        defaultStateBitmap.alpha = opacity
        defaultStateBitmap.draw(canvas)
    }

    val upStatus: Int
        get() = if (upButtonState) {
            NativeLibrary.ButtonState.PRESSED
        } else {
            NativeLibrary.ButtonState.RELEASED
        }
    val downStatus: Int
        get() = if (downButtonState) {
            NativeLibrary.ButtonState.PRESSED
        } else {
            NativeLibrary.ButtonState.RELEASED
        }
    val leftStatus: Int
        get() = if (leftButtonState) {
            NativeLibrary.ButtonState.PRESSED
        } else {
            NativeLibrary.ButtonState.RELEASED
        }
    val rightStatus: Int
        get() = if (rightButtonState) {
            NativeLibrary.ButtonState.PRESSED
        } else {
            NativeLibrary.ButtonState.RELEASED
        }

    fun onConfigureTouch(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                previousTouchX = fingerPositionX
                previousTouchY = fingerPositionY
            }

            MotionEvent.ACTION_MOVE -> {
                controlPositionX += fingerPositionX - previousTouchX
                controlPositionY += fingerPositionY - previousTouchY
                setBounds(
                    controlPositionX,
                    controlPositionY,
                    width + controlPositionX,
                    height + controlPositionY
                )
                previousTouchX = fingerPositionX
                previousTouchY = fingerPositionY
            }
        }
        return true
    }

    fun setPosition(x: Int, y: Int) {
        controlPositionX = x
        controlPositionY = y
    }

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        defaultStateBitmap.setBounds(left, top, right, bottom)
        pressedOneDirectionStateBitmap.setBounds(left, top, right, bottom)
        pressedTwoDirectionsStateBitmap.setBounds(left, top, right, bottom)
    }

    val bounds: Rect
        get() = defaultStateBitmap.bounds

    companion object {
        /** Radius of the dead spot in the centre, as a fraction of half the pad size. */
        private const val CENTER_DEADZONE = 0.10f

        /** tan(~27.5 degrees): how strong the weaker axis must be to add a diagonal. */
        private const val DIAGONAL_RATIO = 0.52f

        /** Extra touch area around the pad, as a fraction of its width. */
        private const val HIT_MARGIN = 0.10f
    }
}
