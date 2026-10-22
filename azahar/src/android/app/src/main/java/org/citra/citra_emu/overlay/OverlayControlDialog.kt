// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.features.touchinput.TouchPointButtons
import org.citra.citra_emu.databinding.DialogOverlayControlBinding
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.utils.OverlayProfile

/**
 * The dialog that opens when a button is tapped while the overlay is being edited. Everything in
 * it only touches that one button: its size, opacity, hold mode and image.
 */
object OverlayControlDialog {
    private const val SCALE_DEFAULT = 50 // stored value, shown as 100%
    private const val SCALE_OFFSET = 50 // shown percent = stored value + 50
    private const val OPACITY_DEFAULT = 100 // percent of the global opacity

    /** Swap and Turbo trigger an action on press, so holding them down would not make sense. */
    fun supportsHold(id: Int): Boolean =
        id != NativeLibrary.ButtonType.BUTTON_SWAP && id != NativeLibrary.ButtonType.BUTTON_TURBO

    private fun nameOf(id: Int): Int = when (id) {
        NativeLibrary.ButtonType.BUTTON_A -> R.string.button_a
        NativeLibrary.ButtonType.BUTTON_B -> R.string.button_b
        NativeLibrary.ButtonType.BUTTON_X -> R.string.button_x
        NativeLibrary.ButtonType.BUTTON_Y -> R.string.button_y
        NativeLibrary.ButtonType.TRIGGER_L -> R.string.button_l
        NativeLibrary.ButtonType.TRIGGER_R -> R.string.button_r
        NativeLibrary.ButtonType.BUTTON_ZL -> R.string.button_zl
        NativeLibrary.ButtonType.BUTTON_ZR -> R.string.button_zr
        NativeLibrary.ButtonType.BUTTON_START -> R.string.button_start
        NativeLibrary.ButtonType.BUTTON_SELECT -> R.string.button_select
        NativeLibrary.ButtonType.BUTTON_HOME -> R.string.button_home
        NativeLibrary.ButtonType.BUTTON_SWAP -> R.string.button_swap
        NativeLibrary.ButtonType.BUTTON_TURBO -> R.string.button_turbo
        NativeLibrary.ButtonType.DPAD -> R.string.controller_dpad
        Hotkey.COMBO_BUTTON.button -> R.string.button_combo
        Hotkey.COMBO_BUTTON_2.button -> R.string.button_combo_2
        Hotkey.COMBO_BUTTON_3.button -> R.string.button_combo_3
        Hotkey.COMBO_BUTTON_4.button -> R.string.button_combo_4
        Hotkey.COMBO_BUTTON_5.button -> R.string.button_combo_5
        else -> if (TouchPointButtons.isTouchButton(id)) {
            R.string.button_touch
        } else {
            R.string.emulation_control_adjust
        }
    }

    private fun hintOf(modeId: Int): Int = when (modeId) {
        R.id.mode_hold -> R.string.overlay_control_hold_description
        R.id.mode_rapid -> R.string.overlay_control_rapid_description
        else -> R.string.overlay_control_mode_normal_description
    }

    /**
     * @param id        the button id (the up id for the D-Pad), used as key for the settings
     * @param control   the drawable that was tapped, used to pick its images
     * @param onChanged called whenever the controls have to be redrawn
     */
    fun show(activity: AppCompatActivity, id: Int, control: Any, onChanged: () -> Unit) {
        val prefId = if (control is InputOverlayDrawableDpad) NativeLibrary.ButtonType.DPAD else id
        val scaleKey = "controlScale-$prefId"
        val opacityKey = "controlOpacity-$prefId"
        val holdKey = "buttonHold-$prefId"
        val rapidKey = "buttonRapid-$prefId"
        val prefs = OverlayProfile.prefs()

        val startScale = prefs.getInt(scaleKey, SCALE_DEFAULT)
        val startOpacity = prefs.getInt(opacityKey, OPACITY_DEFAULT)
        val startHold = prefs.getBoolean(holdKey, false)
        val startRapid = prefs.getBoolean(rapidKey, false)

        val binding = DialogOverlayControlBinding.inflate(LayoutInflater.from(activity))
        val holdAllowed = control is InputOverlayDrawableButton && supportsHold(id)

        fun save(scale: Int, opacity: Int, hold: Boolean, rapid: Boolean) {
            OverlayProfile.prefs().edit()
                .putInt(scaleKey, scale)
                .putInt(opacityKey, opacity)
                .putBoolean(holdKey, hold)
                .putBoolean(rapidKey, rapid)
                .apply()
            onChanged()
        }

        binding.apply {
            if (TouchPointButtons.isTouchButton(prefId)) {
                title.text = activity.getString(
                    R.string.button_touch_format,
                    TouchPointButtons.numberOf(prefId)
                )
            } else {
                title.setText(nameOf(prefId))
            }

            scaleSlider.valueFrom = SCALE_OFFSET.toFloat()
            scaleSlider.valueTo = (SCALE_OFFSET + 150).toFloat()
            scaleSlider.stepSize = 1f
            scaleSlider.value = (startScale + SCALE_OFFSET).toFloat()
                .coerceIn(scaleSlider.valueFrom, scaleSlider.valueTo)
            scaleValue.text = "${scaleSlider.value.toInt()}%"

            opacitySlider.valueFrom = 0f
            opacitySlider.valueTo = 100f
            opacitySlider.stepSize = 1f
            opacitySlider.value = startOpacity.toFloat().coerceIn(0f, 100f)
            opacityValue.text = "${opacitySlider.value.toInt()}%"

            // Normal / Hold / Rapid fire. Only one can be active, same as the old switches.
            val startMode = when {
                holdAllowed && startHold -> R.id.mode_hold
                holdAllowed && startRapid -> R.id.mode_rapid
                else -> R.id.mode_normal
            }
            modeGroup.check(startMode)
            modeHint.setText(hintOf(startMode))
            val modeVisibility = if (holdAllowed) android.view.View.VISIBLE else android.view.View.GONE
            modeGroup.visibility = modeVisibility
            modeHint.visibility = modeVisibility

            fun saveCurrent() = save(
                scaleSlider.value.toInt() - SCALE_OFFSET,
                opacitySlider.value.toInt(),
                modeGroup.checkedButtonId == R.id.mode_hold,
                modeGroup.checkedButtonId == R.id.mode_rapid
            )

            // Live preview while the sliders move
            scaleSlider.addOnChangeListener { _, value, fromUser ->
                scaleValue.text = "${value.toInt()}%"
                if (fromUser) saveCurrent()
            }
            opacitySlider.addOnChangeListener { _, value, fromUser ->
                opacityValue.text = "${value.toInt()}%"
                if (fromUser) saveCurrent()
            }
            modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    modeHint.setText(hintOf(checkedId))
                    saveCurrent()
                }
            }
        }

        val imagesAvailable = OverlayTheme.imagesOf(control).isNotEmpty()
        binding.changeImageButton.visibility =
            if (imagesAvailable) android.view.View.VISIBLE else android.view.View.GONE
        binding.changeImageButton.setOnClickListener {
            OverlayThemeDialog.show(activity, control, onChanged)
        }

        val revert = {
            save(startScale, startOpacity, startHold, startRapid)
        }

        MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setPositiveButton(R.string.overlay_control_done, null)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int -> revert() }
            .setNeutralButton(R.string.overlay_control_reset) { _: DialogInterface?, _: Int ->
                save(SCALE_DEFAULT, OPACITY_DEFAULT, false, false)
            }
            .setOnCancelListener { revert() }
            .show()
    }
}
