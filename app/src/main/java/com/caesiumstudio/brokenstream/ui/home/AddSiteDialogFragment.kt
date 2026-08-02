package com.caesiumstudio.pinstream.ui.home

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.caesiumstudio.pinstream.data.SiteEntry

class AddSiteDialogFragment : DialogFragment() {

    interface Listener {
        fun onSiteConfirmed(url: String, existingId: Long?)
    }

    private var listener: Listener? = null
    private var existingId: Long? = null
    private var existingUrl: String? = null

    fun setListener(l: Listener) {
        listener = l
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editText = EditText(requireContext()).apply {
            hint = "https://example.com"
            setText(existingUrl ?: "")
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(48, 32, 48, 32)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (existingId != null) "Edit site" else "Add site")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) listener?.onSiteConfirmed(url, existingId)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        fun newInstance(existing: SiteEntry? = null) = AddSiteDialogFragment().apply {
            existingUrl = existing?.url
            existingId = existing?.id
        }
    }
}
