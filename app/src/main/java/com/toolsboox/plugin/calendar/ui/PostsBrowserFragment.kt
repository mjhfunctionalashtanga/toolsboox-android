package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentPostsBrowserBinding
import com.toolsboox.plugin.calendar.nw.WPPublish
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Browse the active site's posts (any type) by status — drafts, scheduled, published, private,
 * trash — and open one to edit, trash it, or compose a new one. The read/manage side of Publish;
 * mirrors iOS `PostsBrowserView.swift`. Opening/composing hands off to [PublishFragment].
 */
@AndroidEntryPoint
class PostsBrowserFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_posts_browser
    private lateinit var binding: FragmentPostsBrowserBinding

    private var types: List<WPPublish.PostType> = emptyList()
    private var type = "posts"
    private var filterKey = "all"
    private var posts: List<WPPublish.WpPost> = emptyList()
    private var loading = false

    private data class Filter(val key: String, val label: String, val statuses: List<String>)

    private val filters = listOf(
        Filter("all", "All", listOf("publish", "future", "draft", "pending", "private")),
        Filter("draft", "Drafts", listOf("draft", "pending")),
        Filter("future", "Scheduled", listOf("future")),
        Filter("publish", "Published", listOf("publish")),
        Filter("private", "Private", listOf("private")),
        Filter("trash", "Trash", listOf("trash")),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPostsBrowserBinding.bind(view)
        binding.postsClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.postsCompose.setOnClickListener {
            NavHostFragment.findNavController(this).navigate(R.id.action_to_publish)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            types = withContext(Dispatchers.IO) { WPPublish.postTypes(ctx) }
            if (types.none { it.restBase == type }) types.firstOrNull()?.let { type = it.restBase }
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        // A publish/edit happens in a pushed destination; coming back should show the change.
        if (types.isNotEmpty()) reload()
    }

    private fun typeName(rest: String) = types.firstOrNull { it.restBase == rest }?.name ?: rest

    private fun reload() {
        val ctx = context ?: return
        if (loading) return
        loading = true
        renderControls()
        binding.postsContainer.let { c ->
            // keep controls (index 0..1), clear list below by re-render after fetch
        }
        // The view's scope: this exists only to draw the list, and a back-navigation mid-fetch
        // should cancel the render rather than ghost-write into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val statuses = filters.first { it.key == filterKey }.statuses
                posts = withContext(Dispatchers.IO) { WPPublish.list(ctx, type, statuses) }
                if (isAdded) { loading = false; render() }
            } finally {
                // Also on cancellation — a wedged flag here would refuse every future reload.
                loading = false
            }
        }
    }

    private fun renderControls() { /* controls are rebuilt inside render() */ }

    private fun render() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = binding.postsContainer
        c.removeAllViews()

        // Type + status controls
        c.addView(TextView(ctx).apply {
            text = "Type:  ${typeName(type)}   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(4), px(6), px(4), px(6)); setOnClickListener { pickType() }
        })
        val filterLabel = filters.first { f -> f.key == filterKey }.label
        c.addView(TextView(ctx).apply {
            text = "Show:  $filterLabel   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(4), px(2), px(4), px(8)); setOnClickListener { pickFilter() }
        })
        c.addView(View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                .apply { bottomMargin = px(6) }
        })

        if (!WPPublish.configured(ctx)) {
            c.addView(TextView(ctx).apply {
                text = "Add your site + application password in Calendar Settings, then your posts appear here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(4), px(16), px(4), 0)
            })
            return
        }
        if (loading) {
            c.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(16), px(4), 0) })
            return
        }
        if (posts.isEmpty()) {
            c.addView(TextView(ctx).apply { text = "Nothing here."; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(16), px(4), 0) })
            return
        }

        for (p in posts) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(8), px(10), px(8)); setBackgroundColor(0xFFF3F3F3.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(6)) }
                setOnClickListener { openEditor(p) }
            }
            card.addView(TextView(ctx).apply {
                text = p.title.ifBlank { "(no title)" }; textSize = 16f; setTextColor(0xFF000000.toInt()); maxLines = 2
            })
            val meta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(3), 0, 0) }
            meta.addView(TextView(ctx).apply {
                text = statusLabel(p.status); textSize = 11f
                setPadding(px(6), px(1), px(6), px(1))
                setTextColor(statusTint(p.status))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(8).toFloat(); setColor(statusTint(p.status) and 0x22FFFFFF.toInt())
                }
            })
            meta.addView(TextView(ctx).apply {
                text = "   " + p.date.take(10); textSize = 11f; setTextColor(0xFF666666.toInt())
                setPadding(px(6), px(1), 0, px(1))
            })
            card.addView(meta)
            if (p.status != "trash") {
                card.addView(TextView(ctx).apply {
                    text = "🗑  Trash"; textSize = 14f; setTextColor(0xFFB00020.toInt()); setPadding(0, px(4), 0, 0)
                    setOnClickListener { confirmTrash(p) }
                })
            }
            c.addView(card)
        }
    }

    private fun openEditor(p: WPPublish.WpPost) {
        NavHostFragment.findNavController(this).navigate(
            R.id.action_to_publish,
            bundleOf(PublishFragment.ARG_TYPE to p.type, PublishFragment.ARG_POST_ID to p.id)
        )
    }

    private fun pickType() {
        val ctx = context ?: return
        if (types.isEmpty()) return
        val labels = types.map { it.name }.toTypedArray()
        val current = types.indexOfFirst { it.restBase == type }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Post type")
            .setSingleChoiceItems(labels, current) { d, which -> type = types[which].restBase; d.dismiss(); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickFilter() {
        val ctx = context ?: return
        val labels = filters.map { it.label }.toTypedArray()
        val current = filters.indexOfFirst { it.key == filterKey }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Show")
            .setSingleChoiceItems(labels, current) { d, which -> filterKey = filters[which].key; d.dismiss(); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmTrash(p: WPPublish.WpPost) {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setMessage("Move “${p.title.ifBlank { "(no title)" }}” to Trash?")
            .setPositiveButton("Trash") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) { WPPublish.trash(ctx, p.type, p.id) }
                    android.widget.Toast.makeText(ctx, err ?: "Trashed", android.widget.Toast.LENGTH_SHORT).show()
                    if (err == null) reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusLabel(s: String): String = when (s) {
        "publish" -> "Published"; "future" -> "Scheduled"; "draft", "pending" -> "Draft"
        "private" -> "Private"; "trash" -> "Trash"; else -> s.replaceFirstChar { it.uppercase() }
    }

    private fun statusTint(s: String): Int = when (s) {
        "publish" -> 0xFF1B7A3D.toInt(); "future" -> 0xFFB4690E.toInt(); "private" -> 0xFF6A3FB0.toInt()
        "trash" -> 0xFFB00020.toInt(); else -> 0xFF2F6F96.toInt()
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
