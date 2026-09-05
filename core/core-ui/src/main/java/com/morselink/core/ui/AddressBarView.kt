package com.morselink.core.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat

/** One step in an address-bar trail. [key] is the path this step navigates to. */
data class AddressSegment(val label: String, val key: String)

/**
 * Interactive address bar for the file manager and the Send-flow Files tab.
 *
 * Replaces the old plain-text breadcrumb, which was read-only and lost its root
 * segment on some navigation paths. Provides:
 *
 * - **Trail** — every segment is a button that jumps straight back to that level.
 * - **Sibling caret** — the chevron before a segment lists the other folders at
 *   that level, so you can move sideways without climbing back up.
 * - **Direct entry** — tapping the empty space after the last segment turns the
 *   bar into a text field pre-filled with the current path.
 */
class AddressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onSegmentClick: ((index: Int, segment: AddressSegment) -> Unit)? = null
    var onCaretClick: ((index: Int, segment: AddressSegment) -> Unit)? = null
    var onPathSubmitted: ((path: String) -> Unit)? = null

    private var segments: List<AddressSegment> = emptyList()
    private var siblingCache = HashMap<Int, List<AddressSegment>>()

    private val trailScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
    }
    private val trail = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val spacer = View(context)
    private val editor = EditText(context).apply {
        visibility = GONE
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setBackgroundResource(android.R.color.transparent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private val accent: Int by lazy {
        runCatching { ContextCompat.getColor(context, R.color.primary) }
            .getOrDefault(0xFF1FA36B.toInt())
    }
    private val textPrimary: Int by lazy {
        runCatching { ContextCompat.getColor(context, R.color.textPrimary) }
            .getOrDefault(0xFF1A1A1A.toInt())
    }
    private val textSecondary: Int by lazy {
        runCatching { ContextCompat.getColor(context, R.color.textSecondary) }
            .getOrDefault(0xFF6B6B6B.toInt())
    }

    init {
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)

        trail.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        trailScroll.addView(
            trail,
            HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        addView(
            trailScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            editor,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        // Tapping the empty tail of the bar switches to direct path entry.
        trail.setOnClickListener { beginEditing() }
        spacer.setOnClickListener { beginEditing() }

        editor.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            ) {
                commitEditing()
                true
            } else false
        }
        editor.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitEditing() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s?.endsWith("\n") == true) {
                    s.delete(s.length - 1, s.length)
                    commitEditing()
                }
            }
        })
    }

    fun setSegments(value: List<AddressSegment>) {
        segments = value
        siblingCache.clear()
        render()
    }

    /** Host supplies the sibling list for a caret; called from [onCaretClick]. */
    fun showSiblings(depth: Int, siblings: List<AddressSegment>) {
        siblingCache[depth] = siblings
        if (siblings.isEmpty()) return
        val anchor = trail.getChildAt(caretPositionFor(depth)) ?: return
        val menu = PopupMenu(context, anchor)
        siblings.forEachIndexed { index, sibling ->
            menu.menu.add(0, index, index, sibling.label)
        }
        menu.setOnMenuItemClickListener { item ->
            onSegmentClick?.invoke(depth, siblings[item.itemId])
            true
        }
        menu.show()
    }

    fun currentPath(): String = segments.lastOrNull()?.key.orEmpty()

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        trail.isEnabled = enabled
        editor.isEnabled = enabled
    }

    // ----------------------------------------------------------------- drawing

    private fun render() {
        trail.removeAllViews()
        segments.forEachIndexed { index, segment ->
            if (index > 0) trail.addView(caretFor(index, segment))
            trail.addView(segmentButton(index, segment))
        }
        trail.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        trailScroll.post { trailScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Child position of the caret that precedes segment [depth]. */
    private fun caretPositionFor(depth: Int): Int = (depth * 2) - 1

    private fun segmentButton(index: Int, segment: AddressSegment): TextView =
        TextView(context).apply {
            text = segment.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (index == segments.lastIndex) textPrimary else textSecondary)
            setPadding(pad(6), pad(4), pad(6), pad(4))
            isClickable = true
            background = roundedSelector()
            setOnClickListener { onSegmentClick?.invoke(index, segment) }
        }

    private fun caretFor(index: Int, segment: AddressSegment): ImageView =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            val size = pad(20)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            contentDescription = "Folders at this level"
            setOnClickListener { onCaretClick?.invoke(index, segment) }
        }

    private fun roundedSelector() = GradientDrawable().apply {
        setColor(0x00000000)
        cornerRadius = 12 * resources.displayMetrics.density
    }

    private fun beginEditing() {
        if (editor.visibility == VISIBLE) return
        editor.setText(currentPath())
        editor.setSelection(editor.text.length)
        editor.visibility = VISIBLE
        trailScroll.visibility = GONE
        editor.requestFocus()
        editor.post { editor.showSoftInputOnFocus = true }
    }

    private fun commitEditing() {
        if (editor.visibility != VISIBLE) return
        val path = editor.text.toString().trim()
        editor.visibility = GONE
        trailScroll.visibility = VISIBLE
        editor.clearFocus()
        if (path.isNotEmpty() && path != currentPath()) onPathSubmitted?.invoke(path)
        else if (path.isEmpty()) render()
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
