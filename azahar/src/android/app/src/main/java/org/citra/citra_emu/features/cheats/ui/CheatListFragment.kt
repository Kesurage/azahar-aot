// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.cheats.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.WindowCompat
import android.view.WindowManager
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.text.Spanned
import android.text.Editable
import android.content.res.Configuration
import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.divider.MaterialDividerItemDecoration
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.FragmentCheatListBinding
import org.citra.citra_emu.features.cheats.model.CheatsViewModel
import org.citra.citra_emu.ui.main.MainActivity

class CheatListFragment : Fragment() {
    private var _binding: FragmentCheatListBinding? = null
    private val binding get() = _binding!!

    private val cheatsViewModel: CheatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCheatListBinding.inflate(layoutInflater)
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cheatList.adapter = CheatsAdapter(requireActivity(), cheatsViewModel)
        binding.cheatList.layoutManager = LinearLayoutManager(requireContext())
        binding.cheatList.addItemDecoration(
            MaterialDividerItemDecoration(
                requireContext(),
                MaterialDividerItemDecoration.VERTICAL
            )
        )

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    cheatsViewModel.cheatAddedEvent.collect { position: Int? ->
                        position?.let {
                            binding.cheatList.apply {
                                post { (adapter as CheatsAdapter).notifyItemInserted(it) }
                            }
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    cheatsViewModel.cheatChangedEvent.collect { position: Int? ->
                        position?.let {
                            binding.cheatList.apply {
                                post { (adapter as CheatsAdapter).notifyItemChanged(it) }
                            }
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    cheatsViewModel.cheatDeletedEvent.collect { position: Int? ->
                        position?.let {
                            binding.cheatList.apply {
                                post { (adapter as CheatsAdapter).notifyItemRemoved(it) }
                            }
                        }
                    }
                }
            }
        }

        binding.fab.setOnClickListener {
            cheatsViewModel.startAddingCheat()
            cheatsViewModel.openDetailsView()
        }

        binding.toolbarCheatList.setNavigationOnClickListener {
            if (requireActivity() is MainActivity) {
                view.findNavController().popBackStack()
            } else {
                requireActivity().finish()
            }
        }

        binding.toolbarCheatList.inflateMenu(R.menu.menu_cheat_list)
        binding.toolbarCheatList.setOnMenuItemClickListener {
            if (it.itemId == R.id.cheats_edit_text) {
                showTextEditor()
                true
            } else {
                false
            }
        }

        setInsets()
    }

    private fun showTextEditor() {
        val ctx = requireContext()
        val dialog = Dialog(ctx, R.style.Theme_Azahar_FullscreenEditor)
        val root = LayoutInflater.from(dialog.context)
            .inflate(R.layout.dialog_cheat_text_editor, null)
        val toolbar = root.findViewById<MaterialToolbar>(R.id.editor_toolbar)
        val saveButton = root.findViewById<View>(R.id.editor_save)
        val edit = root.findViewById<TextInputEditText>(R.id.editor_text)
        root.findViewById<TextInputLayout>(R.id.editor_layout).hint =
            cheatsViewModel.getCheatFileName()

        val nameColor =
            MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        val dimColor =
            MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.let { highlightCheatText(it, nameColor, dimColor) }
            }
        })
        edit.setText(cheatsViewModel.getCheatFileText())

        toolbar.setNavigationOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            cheatsViewModel.replaceCheatFileText(normalizeCheatText(edit.text.toString()))
            @Suppress("NotifyDataSetChanged")
            _binding?.cheatList?.adapter?.notifyDataSetChanged()
            dialog.dismiss()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()

        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        dialog.window?.let {
            WindowCompat.getInsetsController(it, root).isAppearanceLightStatusBars = !night
        }
    }

    private fun highlightCheatText(e: Editable, nameColor: Int, dimColor: Int) {
        e.getSpans(0, e.length, ForegroundColorSpan::class.java).forEach { e.removeSpan(it) }
        e.getSpans(0, e.length, StyleSpan::class.java).forEach { e.removeSpan(it) }
        val text = e.toString()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            if (end > start) {
                when (text[start]) {
                    '[' -> {
                        e.setSpan(
                            ForegroundColorSpan(nameColor), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        e.setSpan(
                            StyleSpan(Typeface.BOLD), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    '*' -> e.setSpan(
                        ForegroundColorSpan(dimColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            start = end + 1
        }
    }

    private fun normalizeCheatText(text: String): String =
        text.replace("\r\n", "\n").split("\n").joinToString("\n") { line ->
            val t = line.trim()
            if (t.length > 4 && t.startsWith("[[") && t.endsWith("]]")) {
                "[" + t.substring(2, t.length - 2).trim() + "]"
            } else {
                line
            }
        }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            val mlpAppBar = binding.toolbarCheatList.layoutParams as MarginLayoutParams
            mlpAppBar.leftMargin = leftInsets
            mlpAppBar.rightMargin = rightInsets
            binding.toolbarCheatList.layoutParams = mlpAppBar

            binding.cheatList.updatePadding(
                left = leftInsets,
                right = rightInsets,
                bottom = barInsets.bottom +
                    resources.getDimensionPixelSize(R.dimen.spacing_fab_list)
            )

            val mlpFab = binding.fab.layoutParams as MarginLayoutParams
            val fabPadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpFab.leftMargin = leftInsets + fabPadding
            mlpFab.bottomMargin = barInsets.bottom + fabPadding
            mlpFab.rightMargin = rightInsets + fabPadding
            binding.fab.layoutParams = mlpFab
            windowInsets
        }
    }
}
