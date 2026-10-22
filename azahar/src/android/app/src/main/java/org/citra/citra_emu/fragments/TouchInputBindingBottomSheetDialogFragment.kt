// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.animation.ObjectAnimator
import android.content.DialogInterface
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.abs
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.DialogTouchInputBindingBinding
import org.citra.citra_emu.features.touchinput.TouchInputBinding
import org.citra.citra_emu.features.touchinput.TouchInputBindingManager
import org.citra.citra_emu.features.touchinput.TouchPointButtons

/**
 * Bottom sheet shown after tapping the touchscreen preview. Waits for a controller or
 * keyboard key press or stick movement and binds it to the tapped position, or lets the user
 * pick one of the on-screen overlay buttons instead.
 */
class TouchInputBindingBottomSheetDialogFragment : BottomSheetDialogFragment() {

    companion object {
        /** Fragment result keys, listened for by the bindings screen. */
        const val RESULT_BINDING_ADDED = "touch_binding_added"
        const val RESULT_BINDING_CANCELLED = "touch_binding_cancelled"

        private const val ARG_X = "touch_x"
        private const val ARG_Y = "touch_y"

        private const val KEY_THRESHOLD = 0.5f
        private const val AXIS_THRESHOLD = 0.7f

        private const val PULSE_DURATION_MS = 900L
        private const val PULSE_MIN_ALPHA = 0.4f

        fun newInstance(x: Float, y: Float) =
            TouchInputBindingBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putFloat(ARG_X, x)
                    putFloat(ARG_Y, y)
                }
            }
    }

    private var _binding: DialogTouchInputBindingBinding? = null
    private val binding get() = _binding!!

    private var touchX = 0f
    private var touchY = 0f
    private var bindingAdded = false
    private var pulseAnimator: ObjectAnimator? = null
    private var overlayMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        touchX = arguments?.getFloat(ARG_X) ?: 0f
        touchY = arguments?.getFloat(ARG_Y) ?: 0f
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTouchInputBindingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        expandSheet(view)
        bindTexts()
        startPulse()
        listenForInput()

        binding.buttonCancel.setOnClickListener { dismiss() }
        setupSourceToggle()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (!bindingAdded) {
            parentFragmentManager.setFragmentResult(RESULT_BINDING_CANCELLED, Bundle())
        }
    }

    override fun onDestroyView() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroyView()
        _binding = null
    }

    // region Setup

    private fun expandSheet(view: View) {
        (view.parent as? View)?.let {
            BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
        }
        isCancelable = true
    }

    private fun bindTexts() {
        binding.textTitle.setText(R.string.bind_touch_input)
        binding.textMessage.setText(R.string.input_dialog_description)
        binding.buttonCancel.setText(R.string.bind_touch_input_cancel)
        binding.textPosition.text = TouchInputBinding.formatPosition(touchX, touchY)
    }

    /** Gentle pulse on the icon to show the sheet is waiting for input. */
    private fun startPulse() {
        pulseAnimator = ObjectAnimator.ofFloat(
            binding.iconBind,
            View.ALPHA,
            1f,
            PULSE_MIN_ALPHA
        ).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Switches between waiting for a controller/keyboard press and picking an overlay button. */
    private fun setupSourceToggle() {
        binding.addOverlayButton.setOnClickListener {
            commitBinding(
                TouchInputBinding(
                    overlayButton = TouchPointButtons.allocateId(),
                    x = touchX,
                    y = touchY
                )
            )
        }

        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applySource(checkedId == R.id.sourceOverlayButton)
        }
        applySource(false)
    }

    private fun applySource(overlay: Boolean) {
        overlayMode = overlay
        binding.addOverlayButton.visibility = if (overlay) View.VISIBLE else View.GONE
        binding.iconBind.visibility = if (overlay) View.GONE else View.VISIBLE
        binding.textMessage.setText(
            if (overlay) R.string.touch_input_overlay_message else R.string.input_dialog_description
        )
    }

    private fun listenForInput() {
        val window = dialog?.window ?: return

        dialog?.setOnKeyListener { _, _, event -> handleKeyEvent(event) }
        window.decorView.setOnGenericMotionListener { _, event -> handleAxisEvent(event) }

        // Make sure key events reach the dialog on all devices
        window.decorView.requestFocus()
    }

    // endregion

    // region Input handling

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (overlayMode) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.device == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false

        commitBinding(
            TouchInputBinding(
                keyCode = event.keyCode,
                axis = TouchInputBinding.DEFAULT_UNBOUND,
                positive = true,
                analog = false,
                threshold = KEY_THRESHOLD,
                x = touchX,
                y = touchY
            )
        )
        return true
    }

    private fun handleAxisEvent(event: MotionEvent): Boolean {
        if (overlayMode) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false

        val device = event.device ?: return false

        val moved = device.motionRanges.firstOrNull {
            abs(event.getAxisValue(it.axis)) >= AXIS_THRESHOLD
        } ?: return false

        commitBinding(
            TouchInputBinding(
                keyCode = TouchInputBinding.DEFAULT_UNBOUND,
                axis = moved.axis,
                positive = event.getAxisValue(moved.axis) > 0f,
                analog = false,
                threshold = AXIS_THRESHOLD,
                x = touchX,
                y = touchY
            )
        )
        return true
    }

    private fun commitBinding(binding: TouchInputBinding) {
        TouchInputBindingManager.addBinding(binding)
        bindingAdded = true
        parentFragmentManager.setFragmentResult(RESULT_BINDING_ADDED, Bundle())
        dismiss()
    }

    // endregion
}
