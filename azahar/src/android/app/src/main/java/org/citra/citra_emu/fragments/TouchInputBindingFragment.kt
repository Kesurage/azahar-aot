// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.FragmentTouchInputBinding
import org.citra.citra_emu.features.touchinput.TouchInputBinding
import org.citra.citra_emu.features.touchinput.TouchInputBindingAdapter
import org.citra.citra_emu.features.touchinput.TouchInputBindingManager
import org.citra.citra_emu.features.touchinput.TouchInputBindingProfileManager
import org.citra.citra_emu.utils.ControllerMappingHelper

class TouchInputBindingFragment : Fragment() {
    private var _binding: FragmentTouchInputBinding? = null
    private val nudgeRepeatHandler = Handler(Looper.getMainLooper())
    private var nudgeRepeatRunnable: Runnable? = null
    private val binding get() = _binding!!

    private lateinit var profileManager: TouchInputBindingProfileManager
    // The binding the user chose to rebind; replaced once a new input has been captured
    private var rebindTarget: TouchInputBinding? = null
    private var bindingsBeforeRebind: List<TouchInputBinding> = emptyList()

    private var currentProfile = ""
    private var selectedBinding: TouchInputBinding? = null
    private var isTestMode = false
    private var bindingAdapter: TouchInputBindingAdapter? = null

    // The root never changes; its content is inflated again when the configuration does
    private var contentHost: FrameLayout? = null
    private var inflatedOrientation = Configuration.ORIENTATION_UNDEFINED
    private var inflatedNightMode = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val host = FrameLayout(inflater.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Fallback for hosts that don't pass configuration changes on to their fragments
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                post { rebuildContentIfNeeded() }
            }
        }
        contentHost = host
        inflateContent()
        return host
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileManager = TouchInputBindingProfileManager(requireContext())
        currentProfile = profileManager.getCurrentProfile()

        setupResultListeners()
        bindViews()
    }

    override fun onResume() {
        super.onResume()
        refreshBindings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildContentIfNeeded()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopNudgeRepeat()
        _binding = null
        contentHost = null
    }

    private fun inflateContent() {
        val host = contentHost ?: return
        host.removeAllViews()
        _binding = FragmentTouchInputBinding.inflate(LayoutInflater.from(host.context), host, true)

        val config = resources.configuration
        inflatedOrientation = config.orientation
        inflatedNightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    /**
     * The host activity handles rotation and light/dark changes itself, so the view isn't rebuilt
     * automatically. Inflating it again picks up the matching layout and colors.
     */
    private fun rebuildContentIfNeeded() {
        if (_binding == null || !isAdded) return

        val config = resources.configuration
        val nightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (config.orientation == inflatedOrientation && nightMode == inflatedNightMode) return

        inflateContent()
        bindViews()
    }

    private fun bindViews() {
        binding.profileButton.setOnClickListener { showProfileMenu(it) }
        binding.panel.addBindingButton.setOnClickListener {
            selectBinding(null)
            showBindSheet(0.5f, 0.5f)
        }
        bindingAdapter = TouchInputBindingAdapter(
            onRowClicked = { selectBinding(if (it == selectedBinding) null else it) },
            onDeleteClicked = { deleteBinding(it) }
        )
        binding.panel.bindingsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = bindingAdapter
            itemAnimator = null
        }

        binding.touchInputBindingView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            onEmptySpotTapped = { x, y ->
                selectBinding(null)
                showBindSheet(x, y)
            }
            onBindingTapped = { selectBinding(if (it == selectedBinding) null else it) }
            onSelectedMoved = { x, y, committed -> handleSelectedMoved(x, y, committed) }
            setOnKeyListener { _, _, event -> isTestMode && handleTestKeyEvent(event) }
            setOnGenericMotionListener { _, event -> isTestMode && handleTestMotionEvent(event) }
        }

        binding.editTestToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            setTestMode(checkedId == R.id.testModeButton)
        }
        binding.overflowButton.setOnClickListener { showOverflowMenu(it) }

        bindHoldToRepeat(binding.panel.inspectorRow.nudgeLeft) { nudgeSelected(-NUDGE_STEP, 0f) }
        bindHoldToRepeat(binding.panel.inspectorRow.nudgeRight) { nudgeSelected(NUDGE_STEP, 0f) }
        bindHoldToRepeat(binding.panel.inspectorRow.nudgeUp) { nudgeSelected(0f, -NUDGE_STEP) }
        bindHoldToRepeat(binding.panel.inspectorRow.nudgeDown) { nudgeSelected(0f, NUDGE_STEP) }
        binding.panel.inspectorRow.inspectorDelete.setOnClickListener {
            selectedBinding?.let { deleteBinding(it) }
        }
        binding.panel.inspectorRow.inspectorDone.setOnClickListener { selectBinding(null) }
        binding.panel.inspectorRow.rebindButton.setOnClickListener {
            selectedBinding?.let { startRebind(it) }
        }
        val editCoordinates = View.OnClickListener { selectedBinding?.let { showEditBindingDialog(it) } }
        binding.panel.inspectorRow.xField.setOnClickListener(editCoordinates)
        binding.panel.inspectorRow.yField.setOnClickListener(editCoordinates)

        applyTestModeToViews()
        updateProfileButton()
        loadCurrentProfile()
    }

    private fun setupResultListeners() {
        parentFragmentManager.setFragmentResultListener(
            TouchInputBindingBottomSheetDialogFragment.RESULT_BINDING_ADDED,
            viewLifecycleOwner
        ) { _, _ ->
            finishRebind()
            commitChanges()
        }

        parentFragmentManager.setFragmentResultListener(
            TouchInputBindingBottomSheetDialogFragment.RESULT_BINDING_CANCELLED,
            viewLifecycleOwner
        ) { _, _ -> rebindTarget = null }
    }

    // region Profiles

    private fun updateProfileButton() {
        binding.profileButton.text = currentProfile
    }

    /** Dropdown with every profile (current one checked), plus "create" and the current one's rename/delete. */
    private fun showProfileMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            val profiles = profileManager.getProfiles()
            profiles.forEachIndexed { index, name ->
                menu.add(MENU_GROUP_PROFILES, MENU_PROFILE_BASE + index, index, name).apply {
                    isCheckable = true
                    isChecked = name == currentProfile
                }
            }
            menu.setGroupCheckable(MENU_GROUP_PROFILES, true, true)
            menu.add(0, MENU_CREATE, profiles.size, R.string.create_profile)
            menu.add(
                0, MENU_RENAME, profiles.size + 1,
                getString(R.string.profile_action_rename_format, currentProfile)
            )
            menu.add(
                0, MENU_DELETE, profiles.size + 2,
                getString(R.string.profile_action_delete_format, currentProfile)
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CREATE -> showCreateProfileDialog()
                    MENU_RENAME -> showRenameProfileDialog(currentProfile)
                    MENU_DELETE -> showDeleteProfileDialog(currentProfile)
                    else -> {
                        val name = profiles.getOrNull(item.itemId - MENU_PROFILE_BASE)
                            ?: return@setOnMenuItemClickListener false
                        if (name != currentProfile) selectProfile(name)
                    }
                }
                true
            }
            show()
        }
    }

    private fun selectProfile(profileName: String) {
        currentProfile = profileName
        profileManager.setCurrentProfile(profileName)
        updateProfileButton()
        loadCurrentProfile()
    }

    private fun loadCurrentProfile() {
        TouchInputBindingManager.setBindings(profileManager.loadProfile(currentProfile))
        selectBinding(null)
        refreshBindings()
    }

    /** Saves the active bindings to the current profile and updates the screen. */
    private fun commitChanges() {
        profileManager.saveProfile(currentProfile, TouchInputBindingManager.getBindings())
        refreshBindings()
    }

    private fun showCreateProfileDialog() {
        val suggestedName = profileManager.suggestNextProfileName()
        val nameField = createTextField(R.string.profile_name, suggestedName)
        // The suggested name is already a fine choice on its own; select it so typing replaces
        // it in one go instead of the user having to clear the field first.
        nameField.editText?.let {
            it.requestFocus()
            it.post { it.selectAll() }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_new_profile)
            .setView(createDialogContent(nameField))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameField.text
                if (name.isEmpty()) return@setPositiveButton

                if (profileManager.createProfile(name)) {
                    selectProfile(name)
                } else {
                    showToast(R.string.profile_already_exists)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameProfileDialog(targetProfile: String) {
        val nameField = createTextField(R.string.profile_name, targetProfile)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_profile_name)
            .setView(createDialogContent(nameField))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = nameField.text
                if (newName.isEmpty() || newName == targetProfile) {
                    return@setPositiveButton
                }

                if (profileManager.renameProfile(targetProfile, newName)) {
                    currentProfile = profileManager.getCurrentProfile()
                    updateProfileButton()
                } else {
                    showToast(R.string.profile_name_already_exists)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteProfileDialog(targetProfile: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, targetProfile))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (profileManager.deleteProfile(targetProfile)) {
                    showToast(R.string.profile_deleted)
                    val wasActive = currentProfile == targetProfile
                    currentProfile = profileManager.getCurrentProfile()
                    updateProfileButton()
                    if (wasActive) loadCurrentProfile()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Bindings list and canvas

    private fun refreshBindings() {
        val bindings = TouchInputBindingManager.getBindings()
        val isEmpty = bindings.isEmpty()

        binding.touchInputBindingView.setBindings(bindings)
        binding.panel.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE

        if (selectedBinding !in bindings) {
            selectBinding(null)
        } else {
            // The list may hold a fresher copy (same input, new position); adopt it
            selectBinding(bindings.first { it == selectedBinding })
        }
    }

    /** Highlights [touchBinding] on the canvas, in the list, and in the inspector, or clears it. */
    private fun selectBinding(touchBinding: TouchInputBinding?) {
        selectedBinding = touchBinding
        binding.touchInputBindingView.setSelectedBinding(touchBinding)
        updateInspector()
    }

    private fun updateInspector() {
        val touchBinding = selectedBinding
        val bindings = TouchInputBindingManager.getBindings()
        val showInspector = touchBinding != null && !isTestMode

        binding.panel.inspectorRow.root.isVisible = showInspector
        binding.panel.bindingsTitle.text =
            getString(R.string.touch_input_all_bindings, bindings.size)
        bindingAdapter?.selectedBinding = touchBinding
        if (bindingAdapter?.currentList != bindings) bindingAdapter?.submitList(bindings.toList())

        if (touchBinding != null && showInspector) {
            val number = bindings.indexOf(touchBinding) + 1
            binding.panel.inspectorRow.inspectorBadge.text = number.toString()
            binding.panel.inspectorRow.inspectorName.text = touchBinding.displayName()
            binding.panel.inspectorRow.inspectorPosition.text = getString(
                R.string.touch_input_touches_format,
                touchBinding.positionLabel()
            )
            binding.panel.inspectorRow.xValue.text = formatShort(touchBinding.x)
            binding.panel.inspectorRow.yValue.text = formatShort(touchBinding.y)
        }

        binding.hintText.setText(
            when {
                isTestMode -> R.string.touch_input_hint_test
                bindings.isEmpty() -> R.string.touch_input_hint_empty
                showInspector -> R.string.touch_input_hint_drag
                else -> R.string.touch_input_hint_select
            }
        )
    }

    private fun formatShort(value: Float): String = String.format(Locale.US, "%.2f", value)

    /** Opens the bind sheet at the binding's own spot; the old input is swapped out once a new one is captured. */
    private fun startRebind(touchBinding: TouchInputBinding) {
        rebindTarget = touchBinding
        bindingsBeforeRebind = TouchInputBindingManager.getBindings().toList()
        showBindSheet(touchBinding.x, touchBinding.y)
    }

    private fun finishRebind() {
        val old = rebindTarget ?: return
        rebindTarget = null
        val added = TouchInputBindingManager.getBindings().filter { it !in bindingsBeforeRebind }
        // Choosing the very same input again changes nothing, so the old binding must stay
        if (added.isNotEmpty()) {
            TouchInputBindingManager.removeBinding(old)
            selectedBinding = added.first()
        }
    }

    /**
     * Makes [view] fire [action] immediately on press, then keep firing it every
     * [NUDGE_REPEAT_INTERVAL_MS] while held down, instead of once per tap. Used for the nudge
     * arrows so lining up a binding doesn't take dozens of individual presses.
     */
    private fun bindHoldToRepeat(view: View, action: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    val repeat = object : Runnable {
                        override fun run() {
                            action()
                            nudgeRepeatHandler.postDelayed(this, NUDGE_REPEAT_INTERVAL_MS)
                        }
                    }
                    nudgeRepeatRunnable = repeat
                    nudgeRepeatHandler.postDelayed(repeat, NUDGE_REPEAT_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopNudgeRepeat()
                    true
                }
                else -> false
            }
        }
    }

    private fun stopNudgeRepeat() {
        nudgeRepeatRunnable?.let { nudgeRepeatHandler.removeCallbacks(it) }
        nudgeRepeatRunnable = null
    }

    private fun nudgeSelected(dx: Float, dy: Float) {
        val current = selectedBinding ?: return
        val newX = (current.x + dx).coerceIn(0f, 1f)
        val newY = (current.y + dy).coerceIn(0f, 1f)
        applyMove(current, newX, newY)
    }

    private fun handleSelectedMoved(x: Float, y: Float, committed: Boolean) {
        val current = selectedBinding ?: return
        if (!committed) {
            // Live feedback only; the manager isn't touched until the drag ends
            binding.panel.inspectorRow.inspectorPosition.text = getString(
                R.string.touch_input_touches_format,
                TouchInputBinding.formatPosition(x, y)
            )
            binding.panel.inspectorRow.xValue.text = formatShort(x)
            binding.panel.inspectorRow.yValue.text = formatShort(y)
            return
        }
        applyMove(current, x, y)
    }

    private fun applyMove(current: TouchInputBinding, x: Float, y: Float) {
        val moved = current.copy(x = x, y = y)
        TouchInputBindingManager.replaceBinding(current, moved)
        // Point the selection at the moved value before the refresh runs, or it would look like
        // the old position vanished and the selection would be cleared
        selectBinding(moved)
        commitChanges()
    }

    private fun showBindSheet(x: Float, y: Float) {
        TouchInputBindingBottomSheetDialogFragment
            .newInstance(x, y)
            .show(parentFragmentManager, BIND_SHEET_TAG)
    }

    private fun showEditBindingDialog(touchBinding: TouchInputBinding) {
        val decimalInput = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val xField = createTextField(
            R.string.x_coordinate,
            formatCoordinate(touchBinding.x),
            decimalInput
        )
        val yField = createTextField(
            R.string.y_coordinate,
            formatCoordinate(touchBinding.y),
            decimalInput
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit)
            .setView(createDialogContent(xField, yField))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val x = parseCoordinate(xField.text)
                val y = parseCoordinate(yField.text)
                if (x == null || y == null) {
                    showToast(R.string.invalid_coordinates)
                    return@setPositiveButton
                }
                applyMove(touchBinding, x, y)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteBinding(touchBinding: TouchInputBinding) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_touch_input_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (touchBinding == selectedBinding) selectBinding(null)
                TouchInputBindingManager.removeBinding(touchBinding)
                commitChanges()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_DELETE_ALL, 0, R.string.delete_all)
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_DELETE_ALL) showDeleteAllDialog()
                true
            }
            show()
        }
    }

    private fun showDeleteAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_all)
            .setMessage(R.string.delete_all_touch_input_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectBinding(null)
                TouchInputBindingManager.clearBindings()
                commitChanges()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Test mode

    private fun setTestMode(enabled: Boolean) {
        isTestMode = enabled
        if (enabled) selectBinding(null)
        applyTestModeToViews()
        if (enabled) binding.touchInputBindingView.requestFocus()
    }

    private fun applyTestModeToViews() {
        val toggleId = if (isTestMode) R.id.testModeButton else R.id.editModeButton
        if (binding.editTestToggle.checkedButtonId != toggleId) {
            binding.editTestToggle.check(toggleId)
        }
        binding.touchInputBindingView.testMode = isTestMode
        updateInspector()
    }

    private fun handleTestKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val match = TouchInputBindingManager.getBindings()
            .firstOrNull { it.isKeyBinding && it.keyCode == event.keyCode }
            ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> binding.touchInputBindingView.setTestHighlight(match)
            KeyEvent.ACTION_UP -> binding.touchInputBindingView.setTestHighlight(null)
        }
        return true
    }

    private fun handleTestMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val hit = TouchInputBindingManager.getBindings().firstOrNull { touchBinding ->
            if (!touchBinding.isAxisBinding) return@firstOrNull false
            val value = ControllerMappingHelper.scaleAxis(
                event.device,
                touchBinding.axis,
                event.getAxisValue(touchBinding.axis)
            )
            if (touchBinding.analog) {
                abs(value) > touchBinding.threshold
            } else if (touchBinding.positive) {
                value > TEST_AXIS_DEADZONE
            } else {
                value < -TEST_AXIS_DEADZONE
            }
        }
        binding.touchInputBindingView.setTestHighlight(hit)
        return hit != null
    }

    // endregion

    private fun createTextField(
        hintRes: Int,
        initialText: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): TextInputLayout {
        val cornerRadius = dpToPx(TEXT_FIELD_CORNER_RADIUS_DP).toFloat()

        val layout = TextInputLayout(
            requireContext(),
            null,
            MaterialR.attr.textInputOutlinedStyle
        ).apply {
            setHint(hintRes)
            setBoxCornerRadii(cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        }

        layout.addView(
            TextInputEditText(layout.context).apply {
                setText(initialText)
                this.inputType = inputType
            }
        )
        return layout
    }

    private fun createDialogContent(vararg fields: TextInputLayout): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), 0)

            fields.forEachIndexed { index, field ->
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) {
                    params.topMargin = dpToPx(12)
                }
                addView(field, params)
            }
        }

    private val TextInputLayout.text: String
        get() = editText?.text?.toString()?.trim().orEmpty()

    private fun formatCoordinate(value: Float): String =
        String.format(Locale.US, "%.3f", value)

    /** Accepts "0.5" or "0,5", and returns null unless the value is between 0 and 1. */
    private fun parseCoordinate(text: String): Float? =
        text.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..1f }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun showToast(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val BIND_SHEET_TAG = "TouchInputBindingBottomSheet"

        private const val TEXT_FIELD_CORNER_RADIUS_DP = 28

        private const val MENU_GROUP_PROFILES = 10
        private const val MENU_PROFILE_BASE = 1000
        private const val MENU_CREATE = 4
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
        private const val MENU_DELETE_ALL = 3

        // One grid cell on the 3DS bottom screen (1 / 16th of its width)
        private const val NUDGE_STEP = 1f / 160f
        private const val NUDGE_REPEAT_INITIAL_DELAY_MS = 350L
        private const val NUDGE_REPEAT_INTERVAL_MS = 60L

        private const val TEST_AXIS_DEADZONE = 0.5f
    }
}
