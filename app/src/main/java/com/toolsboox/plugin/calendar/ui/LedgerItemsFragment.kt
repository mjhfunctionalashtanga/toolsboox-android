package com.toolsboox.plugin.calendar.ui

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.databinding.FragmentLedgerItemsBinding
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Tasks & Events — the structured items extracted from a day's handwriting, listed per day.
 * Each row shows text or ink per the item's own toggle; tasks check off, events show their time.
 * Edits (done / display) persist back into the day JSON so they sync.
 */
@AndroidEntryPoint
class LedgerItemsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_ledger_items

    private lateinit var binding: FragmentLedgerItemsBinding
    private lateinit var adapter: LedgerItemAdapter

    private var anchor: LocalDate = LocalDate.now()
    private var day: CalendarDay? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerItemsBinding.bind(view)

        adapter = LedgerItemAdapter(emptyList(), emptyMap(), ::persist)
        binding.itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.itemsRecycler.adapter = adapter
        binding.itemsRecycler.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        binding.prevButton.setOnClickListener { anchor = anchor.minusDays(1); load() }
        binding.nextButton.setOnClickListener { anchor = anchor.plusDays(1); load() }
        binding.ledgerButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        load()
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        binding.dayTitle.text = DateFormat.getDateFormat(requireContext())
            .format(Date.from(anchor.atStartOfDay(ZoneId.systemDefault()).toInstant()))
        lifecycleScope.launch {
            val (d, strokes) = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val cd = runCatching { calendarDayService.load(root, anchor, null, Locale.getDefault()) }
                    .onFailure { Timber.w(it, "ledger items: load failed") }.getOrNull()
                val map = HashMap<String, Stroke>()
                cd?.calendarStrokes?.values?.forEach { list -> list.forEach { map[it.strokeId.toString()] = it } }
                cd?.noteStrokes?.values?.forEach { list -> list.forEach { map[it.strokeId.toString()] = it } }
                cd to map
            }
            day = d
            // Tasks first, then events; done tasks sink to the bottom of the task group.
            val items = (d?.ledgerItems ?: emptyList()).sortedWith(
                compareBy({ it.kind != LedgerItem.Kind.TASK }, { it.kind == LedgerItem.Kind.TASK && it.done }, { it.top })
            )
            adapter = LedgerItemAdapter(items, strokes, ::persist)
            binding.itemsRecycler.adapter = adapter
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** The item is a reference into [day].ledgerItems, so just re-save the day. */
    private fun persist(item: LedgerItem) {
        val d = day ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { calendarDayService.save(documentsRoot(), anchor, d) }
                .onFailure { Timber.w(it, "ledger items: save failed") }
        }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    override fun showLoading() {}
    override fun hideLoading() {}
}
