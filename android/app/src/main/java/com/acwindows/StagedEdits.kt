package com.acwindows

import android.text.Editable
import android.text.TextWatcher
import android.widget.CompoundButton
import android.widget.EditText

/**
 * Tracks the values a settings screen opened with, so it can tell whether anything is
 * staged-but-unsaved (which reveals the header's "Discard changes" button) and put the
 * fields back the way they were. The screens auto-save on leave, so this — not a Cancel
 * button — is the way out of an edit.
 *
 * Covers both text fields and toggles ([checks]); a settings screen's dirty state has to
 * include its checkboxes or flipping one and leaving would silently not save. A toggle
 * usually also changes the rest of the screen, so this owns the single
 * OnCheckedChangeListener slot and forwards to [onToggle] — including when [discard]
 * puts a toggle back, which has to re-run the same screen updates.
 *
 * Construct it *after* the fields have been pre-filled: the values at that moment are
 * the baseline.
 */
class StagedEdits(
    private val fields: List<EditText>,
    private val checks: List<CompoundButton> = emptyList(),
    private val onToggle: (CompoundButton, Boolean) -> Unit = { _, _ -> },
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
        onDirtyChanged(false)
    }

    fun isDirty() = snapshot() != baseline

    /** Called once the staged values have been persisted: they become the new baseline. */
    fun commit() {
        baseline = snapshot()
        onDirtyChanged(false)
    }

    /** Throw away the staged edits, restoring the fields to the baseline. */
    fun discard() {
        fields.forEachIndexed { i, f -> f.setText(baseline[i]) }
        checks.forEachIndexed { i, c -> c.isChecked = baseline[fields.size + i].toBoolean() }
        onDirtyChanged(false)
    }

    private fun snapshot() =
        fields.map { it.text.toString() } + checks.map { it.isChecked.toString() }
}
