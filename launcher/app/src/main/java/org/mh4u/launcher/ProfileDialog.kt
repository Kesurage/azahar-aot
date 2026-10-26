package org.mh4u.launcher

import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Per-game tuning dialog: renderer, fps target, resolution, FSR, frameskip. */
object ProfileDialog {

    fun show(activity: MainActivity, game: Game, onDone: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_profile, null)
        val renderer = view.findViewById<Spinner>(R.id.renderer)
        val fps = view.findViewById<Spinner>(R.id.fps)
        val resolution = view.findViewById<SeekBar>(R.id.resolution)
        val resolutionLabel = view.findViewById<TextView>(R.id.resolution_label)
        val frameskip = view.findViewById<SeekBar>(R.id.frameskip)
        val frameskipLabel = view.findViewById<TextView>(R.id.frameskip_label)
        val fsr = view.findViewById<CheckBox>(R.id.fsr)
        val shaderCache = view.findViewById<CheckBox>(R.id.shader_cache)

        renderer.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item,
            listOf("vulkan", "gles", "auto")
        )
        fps.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item,
            listOf("30", "60")
        )

        val p = ProfilesStore.get(activity, game.uri.toString())
        renderer.setSelection(listOf("vulkan", "gles", "auto").indexOf(p.renderer).coerceAtLeast(0))
        fps.setSelection(if (p.fpsTarget == 60) 1 else 0)
        resolution.progress = p.resolution
        frameskip.progress = p.frameskip
        fsr.isChecked = p.fsr
        shaderCache.isChecked = p.shaderCache

        fun labels() {
            resolutionLabel.text = activity.getString(
                R.string.resolution_value, resolution.progress
            )
            frameskipLabel.text = activity.getString(
                R.string.frameskip_value, frameskip.progress
            )
        }
        labels()
        resolution.setOnSeekBarChangeListener(simple { labels() })
        frameskip.setOnSeekBarChangeListener(simple { labels() })

        AlertDialog.Builder(activity)
            .setTitle(game.title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ProfilesStore.put(
                    activity, game.uri.toString(),
                    GameProfile(
                        renderer = renderer.selectedItem as String,
                        fpsTarget = (fps.selectedItem as String).toInt(),
                        resolution = resolution.progress,
                        fsr = fsr.isChecked,
                        frameskip = frameskip.progress,
                        shaderCache = shaderCache.isChecked,
                    )
                )
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun simple(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
