package com.toolsboox.plugin.calendar.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentPublishBinding
import com.toolsboox.plugin.calendar.nw.WPPublish
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import javax.inject.Inject

/**
 * Compose and publish to the ACTIVE WordPress site from inside Ledger — pick the post type (any
 * public CPT), write it, set categories/tags (create new ones inline), attach an image as the
 * featured image, and choose its fate: save a draft, schedule it, publish now, or keep it private.
 * Mirrors iOS `PublishView.swift`. Editing an existing post: pass [ARG_TYPE] + [ARG_POST_ID].
 * Seed from a gram/page: set [pendingImage]/[pendingTitle]/[pendingBody] before navigating.
 */
@AndroidEntryPoint
class PublishFragment @Inject constructor() : ScreenFragment() {

    companion object {
        const val ARG_TYPE = "publish_type"
        const val ARG_POST_ID = "publish_post_id"

        /** Optional seed for "publish this gram / page" entry points; consumed once on load. */
        @JvmStatic
        var pendingImage: Bitmap? = null

        @JvmStatic
        var pendingTitle: String = ""

        @JvmStatic
        var pendingBody: String = ""
    }

    override val view = R.layout.fragment_publish
    private lateinit var binding: FragmentPublishBinding

    private val draft = WPPublish.Draft()
    private var types: List<WPPublish.PostType> = emptyList()
    private var categories: MutableList<WPPublish.Term> = mutableListOf()
    private var tags: MutableList<WPPublish.Term> = mutableListOf()
    private var image: Bitmap? = null
    private var statusChoice = "draft"          // draft | publish | future | private
    private var scheduleMillis = System.currentTimeMillis() + 3_600_000L
    private var editing: WPPublish.WpPost? = null
    private var publishing = false
    private var loaded = false

    // Kept across re-renders so a picker rebuild never loses what you've typed.
    private var titleField: EditText? = null
    private var bodyField: EditText? = null

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) { null }
                }
                if (bmp != null) { image = bmp; render() }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPublishBinding.bind(view)
        binding.publishClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.publishAction.setOnClickListener { go() }
        load()
    }

    private fun load() {
        val ctx = context ?: return
        val argType = arguments?.getString(ARG_TYPE)
        val argId = arguments?.getInt(ARG_POST_ID, 0) ?: 0
        lifecycleScope.launch {
            if (!argType.isNullOrBlank() && argId > 0) {
                val post = withContext(Dispatchers.IO) { WPPublish.getPost(ctx, argType, argId) }
                if (post != null) {
                    editing = post
                    draft.type = post.type
                    draft.title = post.title
                    draft.content = post.content
                    draft.categories = post.categories
                    draft.tags = post.tags
                    draft.featuredMedia = post.featuredMedia
                    statusChoice = if (post.status in listOf("draft", "publish", "future", "private")) post.status else "draft"
                }
            }
            // Seed from a gram / page (one-shot).
            pendingImage?.let { image = it }; pendingImage = null
            if (pendingTitle.isNotBlank()) { draft.title = pendingTitle; pendingTitle = "" }
            if (pendingBody.isNotBlank()) { draft.content = pendingBody; pendingBody = "" }

            types = withContext(Dispatchers.IO) { WPPublish.postTypes(ctx) }
            if (types.none { it.restBase == draft.type }) types.firstOrNull()?.let { draft.type = it.restBase }
            categories = withContext(Dispatchers.IO) { WPPublish.terms(ctx, "categories") }.toMutableList()
            tags = withContext(Dispatchers.IO) { WPPublish.terms(ctx, "tags") }.toMutableList()
            loaded = true
            render()
        }
    }

    /** Read the live title/body fields back into the draft before any rebuild or save. */
    private fun syncText() {
        titleField?.let { draft.title = it.text.toString() }
        bodyField?.let { draft.content = it.text.toString() }
    }

    private fun typeName(rest: String) = types.firstOrNull { it.restBase == rest }?.name ?: rest

    private fun render() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = binding.publishContainer
        c.removeAllViews()

        binding.publishAction.text = when (statusChoice) {
            "publish" -> "Publish"; "future" -> "Schedule"; else -> "Save"
        }

        if (!WPPublish.configured(ctx)) {
            c.addView(TextView(ctx).apply {
                text = "Add your site + application password in Calendar Settings, then publish from here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(4), px(16), px(4), 0)
            })
            return
        }

        fun sectionLabel(t: String) = TextView(ctx).apply {
            text = t; textSize = 11f; setTextColor(0xFF888888.toInt())
            setPadding(px(4), px(14), px(4), px(3)); letterSpacing = 0.06f
        }

        // Title
        titleField = EditText(ctx).apply {
            setText(draft.title); hint = "Title"; textSize = 20f; setSingleLine(true)
            setPadding(px(8), px(8), px(8), px(8))
        }
        c.addView(titleField)

        // Body
        bodyField = EditText(ctx).apply {
            setText(draft.content); hint = "Write…"; textSize = 16f
            gravity = android.view.Gravity.TOP; minLines = 8; setSingleLine(false)
            setPadding(px(8), px(8), px(8), px(8))
        }
        c.addView(bodyField)

        // Type
        c.addView(sectionLabel("TYPE"))
        c.addView(TextView(ctx).apply {
            text = "${typeName(draft.type)}   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(8), px(6), px(8), px(6))
            setOnClickListener { pickType() }
        })

        // Status
        c.addView(sectionLabel("STATUS"))
        val statusRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun statusChip(label: String, key: String) = TextView(ctx).apply {
            text = label; textSize = 15f; isAllCaps = false
            setPadding(px(12), px(6), px(12), px(6))
            val on = statusChoice == key
            setTextColor(if (on) 0xFF000000.toInt() else 0xFF888888.toInt())
            if (on) { setTypeface(typeface, android.graphics.Typeface.BOLD); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }
            setOnClickListener { if (statusChoice != key) { syncText(); statusChoice = key; render() } }
        }
        statusRow.addView(statusChip("Draft", "draft"))
        statusRow.addView(statusChip("Publish", "publish"))
        statusRow.addView(statusChip("Schedule", "future"))
        statusRow.addView(statusChip("Private", "private"))
        c.addView(statusRow)

        if (statusChoice == "future") {
            c.addView(TextView(ctx).apply {
                val f = java.text.SimpleDateFormat("EEE d MMM yyyy · HH:mm", java.util.Locale.getDefault())
                text = "When:  ${f.format(java.util.Date(scheduleMillis))}   ▾"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), px(6), px(8), px(6))
                setOnClickListener { pickSchedule() }
            })
        }

        // Taxonomy
        c.addView(sectionLabel("TAXONOMY"))
        c.addView(TextView(ctx).apply {
            text = "Categories (${draft.categories.size})   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(8), px(6), px(8), px(6))
            setOnClickListener { pickTerms("categories") }
        })
        c.addView(TextView(ctx).apply {
            text = "Tags (${draft.tags.size})   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(8), px(6), px(8), px(6))
            setOnClickListener { pickTerms("tags") }
        })

        // Featured image
        c.addView(sectionLabel("FEATURED IMAGE"))
        val img = image
        if (img != null) {
            c.addView(ImageView(ctx).apply {
                setImageBitmap(img); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_START; maxHeight = px(180)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(4); bottomMargin = px(4) }
            })
            c.addView(TextView(ctx).apply {
                text = "✕  Remove image"; textSize = 14f; setTextColor(0xFFB00020.toInt()); setPadding(px(8), px(2), px(8), px(6))
                setOnClickListener { image = null; draft.featuredMedia = 0; syncText(); render() }
            })
        } else if (draft.featuredMedia > 0) {
            c.addView(TextView(ctx).apply {
                text = "Featured image attached (#${draft.featuredMedia})"; textSize = 14f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(4), px(8), px(2))
            })
            c.addView(TextView(ctx).apply {
                text = "✕  Remove image"; textSize = 14f; setTextColor(0xFFB00020.toInt()); setPadding(px(8), px(2), px(8), px(6))
                setOnClickListener { draft.featuredMedia = 0; syncText(); render() }
            })
        }
        c.addView(TextView(ctx).apply {
            text = if (img == null && draft.featuredMedia == 0) "🖼  Attach an image" else "🖼  Replace image"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), px(6), px(8), px(12))
            setOnClickListener { galleryLauncher.launch("image/*") }
        })
    }

    private fun pickType() {
        val ctx = context ?: return
        if (types.isEmpty()) { toast("No post types (check the site & app password)"); return }
        val labels = types.map { it.name }.toTypedArray()
        val current = types.indexOfFirst { it.restBase == draft.type }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Post type")
            .setSingleChoiceItems(labels, current) { d, which ->
                syncText(); draft.type = types[which].restBase; d.dismiss(); render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickSchedule() {
        val ctx = context ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = scheduleMillis }
        DatePickerDialog(ctx, { _, y, m, day ->
            TimePickerDialog(ctx, { _, h, min ->
                cal.set(y, m, day, h, min, 0)
                scheduleMillis = cal.timeInMillis
                render()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** Multi-select a taxonomy's terms, with a "New…" button that creates one on the spot. */
    private fun pickTerms(taxonomy: String) {
        val ctx = context ?: return
        val list = if (taxonomy == "categories") categories else tags
        val selected = (if (taxonomy == "categories") draft.categories else draft.tags).toMutableSet()
        if (list.isEmpty()) {
            // Nothing yet — offer to create the first one.
            promptNewTerm(taxonomy, selected)
            return
        }
        val labels = list.map { it.name }.toTypedArray()
        val checked = BooleanArray(list.size) { selected.contains(list[it].id) }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (taxonomy == "categories") "Categories" else "Tags")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(list[which].id) else selected.remove(list[which].id)
            }
            .setNeutralButton("New…") { _, _ ->
                if (taxonomy == "categories") draft.categories = selected.toList() else draft.tags = selected.toList()
                promptNewTerm(taxonomy, selected)
            }
            .setPositiveButton("Done") { _, _ ->
                if (taxonomy == "categories") draft.categories = selected.toList() else draft.tags = selected.toList()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptNewTerm(taxonomy: String, selected: MutableSet<Int>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val input = EditText(ctx).apply {
            hint = if (taxonomy == "categories") "New category" else "New tag"; setSingleLine()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (taxonomy == "categories") "New category" else "New tag")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) { pickTerms(taxonomy); return@setPositiveButton }
                lifecycleScope.launch {
                    val term = withContext(Dispatchers.IO) { WPPublish.createTerm(ctx, taxonomy, name) }
                    if (term != null) {
                        val listRef = if (taxonomy == "categories") categories else tags
                        listRef.add(term); listRef.sortBy { it.name.lowercase() }
                        selected.add(term.id)
                        if (taxonomy == "categories") draft.categories = selected.toList() else draft.tags = selected.toList()
                        render()
                    } else toast("Couldn't create it")
                    // Reopen the picker so more can be toggled/added.
                    pickTerms(taxonomy)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pickTerms(taxonomy) }
            .show()
    }

    private fun successLabel(): String = when {
        editing != null -> "Updated"
        statusChoice == "publish" -> "Published"
        statusChoice == "future" -> "Scheduled"
        else -> "Saved"
    }

    private fun go() {
        val ctx = context ?: return
        if (!loaded || publishing) return
        syncText()
        if (draft.title.isBlank()) { toast("Add a title"); return }
        if (!WPPublish.configured(ctx)) { toast("Set an active site in Settings"); return }
        publishing = true
        binding.publishAction.isEnabled = false
        lifecycleScope.launch {
            val img = image
            if (img != null) {
                val png = withContext(Dispatchers.IO) {
                    val baos = ByteArrayOutputStream(); img.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val mediaId = withContext(Dispatchers.IO) { WPPublish.uploadMedia(ctx, png, draft.title) }
                if (mediaId != null) draft.featuredMedia = mediaId
            }
            draft.status = statusChoice
            draft.dateIso = if (statusChoice == "future") WPPublish.isoLocal(scheduleMillis) else null
            val res = withContext(Dispatchers.IO) { WPPublish.save(ctx, draft, editing?.id) }
            publishing = false
            if (!isAdded) return@launch
            binding.publishAction.isEnabled = true
            if (res.ok) {
                toast(successLabel())
                NavHostFragment.findNavController(this@PublishFragment).popBackStack()
            } else {
                toast("Couldn't publish — check the site & app password")
            }
        }
    }

    private fun toast(t: String) {
        if (isAdded) Toast.makeText(requireContext(), t, Toast.LENGTH_SHORT).show()
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
