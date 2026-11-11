package com.acwindows

import android.text.Editable
import android.text.TextWatcher
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar

/**
 * Tracks the values a settings screen opened with, so it can tell whether anything is
 * staged-but-unsaved (which reveals the header's "Discard changes" button) and put the
 * fields back the way they were. The screens auto-save on leave, so this — not a Cancel
 * button — is the way out of an edit.
 *
 * Covers text fields, toggles ([checks]) and sliders ([sliders]); a settings screen's
 * dirty state has to include every control on it or changing one and leaving would
 * silently not save. A toggle or a slider usually also changes the rest of the screen
 * (a live "120%" readout, a colour preview), so this owns their single listener slot and
 * forwards to [onToggle] / [onSlide] — including when [discard] puts one back, which has
 * to re-run the same screen updates.
 *
 * Construct it *after* the fields have been pre-filled: the values at that moment are
 * the baseline.
 */
class StagedEdits(
    private val fields: List<EditText>,
    private val checks: List<CompoundButton> = emptyList(),
    private val sliders: List<SeekBar> = emptyList(),
    private val onToggle: (CompoundButton, Boolean) -> Unit = { _, _ -> },
    private val onSlide: (SeekBar, Int) -> Unit = { _, _ -> },
    private val onDirtyChanged: (Boolean) -> Unit,
) {

    private var baseline = snapshot()

    init {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onDirtyChanged(isDirty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        fields.forEach { it.addTextChangedListener(watcher) }
        checks.forEach { c ->
            c.setOnCheckedChangeListener { btn, on -> onToggle(btn, on); onDirtyChanged(isDirty()) }
        }
        sliders.forEach { s ->
            s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    onSlide(bar, progress); onDirtyChanged(isDirty())
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        onDirtyChanged(false)
    }

    fun isDirty() = snapshot() != baseline

    /** Called once the staged values have been persisted: they become the new baseline. */
    fun commit() {
        baseline = snapshot()
        onDirtyChanged(false)
    }

    /** Throw away the staged edits, restoring the controls to the baseline. */
    fun discard() {
        fields.forEachIndexed { i, f -> f.setText(baseline[i]) }
        checks.forEachIndexed { i, c -> c.isChecked = baseline[fields.size + i].toBoolean() }
        sliders.forEachIndexed { i, s ->
            s.progress = baseline[fields.size + checks.size + i].toIntOrNull() ?: s.progress
        }
        onDirtyChanged(false)
    }

    private fun snapshot() =
        fields.map { it.text.toString() } +
            checks.map { it.isChecked.toString() } +
            sliders.map { it.progress.toString() }
}
