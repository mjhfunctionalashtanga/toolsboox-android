package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.databinding.FragmentRolodexBinding
import com.toolsboox.plugin.calendar.da.v2.Contact
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The rolodex — a searchable list of contacts backed by the synced [ContactStore]. Tap a row to
 * edit (an AlertDialog editor); the ＋ button adds one. Wire-compatible with the iOS RolodexView.
 */
@AndroidEntryPoint
class RolodexFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_rolodex
    private lateinit var binding: FragmentRolodexBinding
    private lateinit var adapter: ContactAdapter
    private var allContacts: List<Contact> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentRolodexBinding.bind(view)

        adapter = ContactAdapter(emptyList(), ::openEditor)
        binding.contactsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecycler.adapter = adapter
        binding.contactsRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        binding.addContactButton.setOnClickListener { openEditor(Contact()) }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) {}
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { ContactStore.list(requireContext()) }
            allContacts = contacts
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = binding.searchField.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) allContacts else allContacts.filter {
            it.name.lowercase().contains(q) || it.phone.contains(q) ||
                it.email.lowercase().contains(q) || it.org.lowercase().contains(q)
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Add / edit a contact via a simple field dialog; Save upserts, Delete tombstones. */
    private fun openEditor(contact: Contact) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(ctx).apply {
                this.hint = hint; setText(value); inputType = type; setSingleLine(hint != "Notes")
            }
            root.addView(e); return e
        }
        val nameE = field("Name", contact.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phoneE = field("Phone", contact.phone, InputType.TYPE_CLASS_PHONE)
        val emailE = field("Email", contact.email, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT)
        val orgE = field("Organization", contact.org, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val bdayE = field("Birthday (e.g. Mar 4)", contact.birthday, InputType.TYPE_CLASS_TEXT)
        val bioE = field("Notes", contact.bio, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (contact.name.isBlank()) "New Contact" else "Edit Contact")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                contact.name = nameE.text.toString().trim()
                contact.phone = phoneE.text.toString().trim()
                contact.email = emailE.text.toString().trim()
                contact.org = orgE.text.toString().trim()
                contact.birthday = bdayE.text.toString().trim()
                contact.bio = bioE.text.toString().trim()
                if (contact.name.isNotBlank()) {
                    ContactStore.upsert(requireContext(), contact)
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
        if (contact.name.isNotBlank()) {
            builder.setNeutralButton("Delete") { _, _ ->
                ContactStore.delete(requireContext(), contact.id)
                load()
            }
        }
        builder.show()
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
