package com.mooncity.gpsmock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns a plain [EditText] into an address field with live suggestions.
 *
 * Typing is debounced and each keystroke cancels the previous lookup, both to stay
 * responsive and to keep well inside Nominatim's one-request-per-second limit.
 */
class PlaceSuggest(
    private val field: EditText,
    private val scope: CoroutineScope,
    private val onPicked: (Place) -> Unit
) {

    private companion object {
        const val DEBOUNCE_MS = 450L
        const val MIN_CHARS = 3
        const val MAX_ROWS = 6
    }

    private val popup = ListPopupWindow(field.context).apply {
        anchorView = field
        isModal = false
        setDropDownGravity(android.view.Gravity.START)
    }

    private val adapter = PlaceAdapter()
    private var job: Job? = null

    /** Set while text is being written programmatically, to avoid a feedback loop. */
    private var suppress = false

    init {
        popup.setAdapter(adapter)
        popup.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { pick(it) }
        }

        field.doAfterTextChanged { text ->
            if (suppress) return@doAfterTextChanged
            val q = text?.toString()?.trim().orEmpty()
            job?.cancel()
            if (q.length < MIN_CHARS) {
                dismiss()
                return@doAfterTextChanged
            }
            job = scope.launch {
                delay(DEBOUNCE_MS)
                val results = runCatching { Nominatim.search(q, MAX_ROWS) }.getOrDefault(emptyList())
                if (results.isEmpty() || !field.isAttachedToWindow) {
                    dismiss()
                } else {
                    adapter.submit(results)
                    popup.height = ListPopupWindow.WRAP_CONTENT
                    popup.width = field.width
                    if (field.hasFocus()) popup.show()
                }
            }
        }

        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) dismiss() }
    }

    /** Writes a chosen place into the field without triggering another lookup. */
    fun setText(text: String) {
        suppress = true
        field.setText(text)
        field.setSelection(text.length)
        suppress = false
    }

    private fun pick(place: Place) {
        job?.cancel()
        dismiss()
        setText(place.primary)
        field.clearFocus()
        onPicked(place)
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private inner class PlaceAdapter : BaseAdapter() {

        private var items: List<Place> = emptyList()

        fun submit(list: List<Place>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Place? = items.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_place, parent, false)
            val place = items[position]
            view.findViewById<TextView>(R.id.placePrimary).text = place.primary
            view.findViewById<TextView>(R.id.placeSecondary).apply {
                text = place.secondary
                visibility = if (place.secondary.isBlank()) View.GONE else View.VISIBLE
            }
            return view
        }
    }
}
