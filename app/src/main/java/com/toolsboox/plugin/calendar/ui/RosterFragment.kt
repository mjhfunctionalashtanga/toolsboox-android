package com.toolsboox.plugin.calendar.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.da.Attachment
import com.toolsboox.ot.InkPadView
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.nw.LedgerWebBridge
import com.toolsboox.plugin.calendar.nw.RecordPrefs
import com.toolsboox.plugin.calendar.nw.RosterBridge
import com.toolsboox.plugin.calendar.ot.ConnectionStore
import com.toolsboox.plugin.calendar.ot.PanelOcr
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Booking Roster — the day's attendees (FluentBooking) as touchable cards grouped by time slot.
 *
 * Each slot header carries the class title, a per-class bell (opt this class in/out of the pre-session
 * record nudge — [RecordPrefs]) and a record button (an A/V gram, lands in today's page like any
 * other). Tapping an attendee opens them: photo, contact lines, a jump to their FluentCRM profile,
 * and an ink pad whose handwriting is OCR'd (ML Kit, [PanelOcr]) straight onto their CRM timeline
 * (/crm/note). On a save the person also joins the connection graph, linked to the session day.
 *
 * Reuses the "Community & Boards" bridge creds. Read-only-safe: no creds → an empty list with a hint.
 * Every network call runs off the main thread and fails quietly. Mirrors iOS `App/RosterView.swift`.
 */
@AndroidEntryPoint
class RosterFragment @Inject constructor() : ScreenFragment() {

    companion object {
        /** How many days one window may fetch. A year of a busy studio is 365 round trips and
         *  nobody asked a pane for that; six weeks is the widest picture the surface can draw
         *  without becoming a scrape. Beyond it the window is truncated, not refused — a quarter
         *  still opens, on its first six weeks. */
        private const val MAX_WINDOW_DAYS = 42
    }

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var navigatorImageView: ImageView
    private var navBar: CalendarNavBarHost? = null

    /** The pair the almanac strip owns: how wide the window is, and where it sits. "day" is the
     *  live view this pane has always had; the rest are what the strip added. */
    private var navPeriod: String = "day"
    private var date: LocalDate = LocalDate.now()
    private var attendees: List<RosterBridge.Attendee> = emptyList()
    private var loading = true

    private val density get() = resources.displayMetrics.density
    private fun px(v: Int): Int = (v * density).toInt()
    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private val dateStr: String get() = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Sets `toolbar` from the host activity (the base builds no view when `view` is null).
        super.onCreateView(inflater, container, savedInstanceState)
        return buildRoot()
    }

    private fun buildRoot(): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Header bar: title on the left, actions on the right.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(10), px(14), px(6))
        }
        titleView = TextView(ctx).apply {
            textSize = 18f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun barBtn(label: String, onTap: () -> Unit) = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 13f; minWidth = 0
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { onTap() }
        }
        bar.addView(titleView)
        // Today is the almanac chip's ✕ in a button: it resets BOTH halves of the pair, because a
        // pane left on "this month, three months ago" is not returned to today by moving one of
        // them. The ‹ › that used to sit here are gone — the strip carries its own, and they step
        // by the window, which is the thing a bespoke pair always gets wrong.
        bar.addView(barBtn("Today") { navPeriod = "day"; date = LocalDate.now(); navBar?.setGranularity("day"); load() })
        bar.addView(barBtn("↻") { load() })
        // Booking is a facet of the roster (a roster is just the attendees of an event), so the public
        // booking page lives here rather than as its own menu row — the member-facing web version of
        // exactly what this surface manages.
        bar.addView(barBtn("Booking") { openBookingPage() })
        bar.addView(barBtn("Close") { NavHostFragment.findNavController(this).popBackStack() })
        col.addView(bar)

        // The real Almanac strip, the one Mail and the feeds already filter with — not a look-alike.
        // It goes under the title bar rather than at the foot, where the old ‹ Today › lived, so it
        // reads as the pane's date chrome instead of as a pagination footer.
        navigatorImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = null
        }
        col.addView(navigatorImageView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, px(52)
        ).apply { setMargins(px(4), 0, px(4), px(2)) })

        content = FrameLayout(ctx)
        col.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return col
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Passing onSelectPeriod is what turns the strip from a way OUT of the pane into its date
        // filter: a slot tap scopes the roster in place instead of jumping to the calendar page for
        // that period (see CalendarNavBarHost.select). The arrows move this pane's own anchor, by
        // the active window — a month steps a month.
        navBar = CalendarNavBarHost(requireContext(), navigatorImageView, this,
            onStepDay = { d -> date = d; load() },
            onSelectPeriod = { period, d -> navPeriod = period; date = d; load() })
        load()   // draws the strip on its way past — see renderNav()
    }

    /** Redraw the Almanac strip for the anchor (dots for filled days), as every hosting surface does. */
    private fun renderNav() {
        val bar = navBar ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = Locale.getDefault()
            val anchor = date
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, anchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, anchor, loc) }.getOrNull()
            }
            // A missing pattern must not kill the strip: an unrendered CalendarNavBarHost never
            // sets its currentDay, and a bar with no currentDay swallows every touch — the silent
            // way a date filter can "render once, then no-op" on a fresh year.
            val safePat = pat ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(anchor.year, loc).fill()
            if (isAdded) bar.render(day, safePat)
        }
    }

    /** Inclusive day range for the active window — the same shape [LedgerItemsFragment] uses. */
    private fun periodRange(): Pair<LocalDate, LocalDate> = when (navPeriod) {
        "week" -> {
            val s = date.with(java.time.temporal.WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
            s to s.plusDays(6)
        }
        "month" -> date.withDayOfMonth(1) to date.withDayOfMonth(date.lengthOfMonth())
        "quarter" -> {
            val s = date.withMonth((date.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            val e = s.plusMonths(2)
            s to e.withDayOfMonth(e.lengthOfMonth())
        }
        "year" -> LocalDate.of(date.year, 1, 1) to LocalDate.of(date.year, 12, 31)
        else -> date to date
    }

    /**
     * What a period MEANS here: the bridge answers one day at a time, so a window is that many
     * days — fetched together, capped at [MAX_WINDOW_DAYS]. "day" is the one-element case, so
     * there is no separate path for it.
     */
    private fun windowDays(): List<String> {
        val (start, end) = periodRange()
        val out = mutableListOf<String>()
        var cur = start
        while (!cur.isAfter(end) && out.size < MAX_WINDOW_DAYS) {
            out.add(cur.toString()); cur = cur.plusDays(1)
        }
        return out.ifEmpty { listOf(dateStr) }
    }

    /** True while the strip is scoped wider than one day — what decides whether a slot header has
     *  to name its date, and how an empty pane words itself. */
    private fun wideWindow(): Boolean = navPeriod != "day"

    /** The pair, as one string — the stale-response guard, so a slow month landing after you have
     *  stepped on doesn't stamp itself over the day you are now looking at. */
    private fun windowKey(): String = "$navPeriod|$date"

    override fun onResume() {
        super.onResume()
        // The pre-session nudge can also surface here (it primarily fires from the day page).
        AppointmentNudge.maybeShow(this) { recordForToday() }
    }

    /** The member-facing booking page (FluentBooking) in the in-app WebView — the web face of the
     *  same bookings this roster manages. */
    private fun openBookingPage() {
        NavHostFragment.findNavController(this).navigate(
            R.id.action_to_site_web,
            android.os.Bundle().apply { putString(SiteWebFragment.ARG_TARGET, "booking") }
        )
    }

    /**
     * The roster's own site: the ROSTER surface affinity (remembered → theyoga.club, where
     * FluentBooking's calendar and the attendees actually live → active). Threaded into every
     * [RosterBridge] call so the studio's day is readable — and its CRM writable — regardless of
     * which site the rest of the app points at (Michael's no-dominant-global-site ruling).
     */
    private fun rosterSite(): com.toolsboox.plugin.calendar.nw.LedgerSite? =
        com.toolsboox.plugin.calendar.nw.SiteAffinity.siteFor(
            requireContext(), com.toolsboox.plugin.calendar.nw.SiteRouting.ROSTER
        )

    private fun rosterCfg(): LedgerWebBridge.Config? =
        com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
            requireContext(), com.toolsboox.plugin.calendar.nw.SiteRouting.ROSTER
        )

    private fun load() {
        if (!isAdded) return
        // The date left the title when the strip arrived. It was in there because nothing else on
        // the screen said which day you were looking at; the almanac says it now, with dots.
        // Michael, 2026-08-07: "Change Roster to 'Event Attendees'". The old name described a
        // document (a roster is a list you hold); the new one describes the PEOPLE, which is what
        // you actually came to this screen for.
        titleView.text = "Event Attendees"
        loading = true
        renderNav()
        renderLoading()
        val ctx = requireContext()
        val days = windowDays()
        val target = windowKey()
        // The view's scope: the fetch exists only to draw this roster, so back-navigation
        // cancels it instead of ghost-rendering into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            val cfg = rosterCfg()
            val list = withContext(Dispatchers.IO) {
                // Concurrently, because a month is thirty round trips and doing them in a row is
                // the difference between a pane that opens and a pane you wait for. One day's
                // failure is caught to an empty list, exactly as the posts fan-out does — an
                // unreachable Tuesday must not empty the rest of the week.
                coroutineScope {
                    days.map { d ->
                        async { runCatching { RosterBridge.roster(ctx, d, cfg) }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                }
            }
            if (!isAdded || target != windowKey()) return@launch
            attendees = list
            loading = false
            render()
        }
    }

    private fun renderLoading() {
        content.removeAllViews()
        content.addView(TextView(requireContext()).apply {
            text = "Loading…"; setTextColor(Color.parseColor("#666666"))
            setPadding(px(20), px(24), px(20), px(20))
        })
    }

    private fun render() {
        val ctx = requireContext()
        content.removeAllViews()
        if (attendees.isEmpty()) {
            content.addView(TextView(ctx).apply {
                text = if (wideWindow())
                    "Nobody's booked in this $navPeriod, or the site bridge isn't reachable."
                else "Nobody's booked on $dateStr, or the site bridge isn't reachable."
                setTextColor(Color.parseColor("#666666")); textSize = 15f
                setPadding(px(20), px(24), px(20), px(20))
            })
            return
        }
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(24))
        }
        // A slot is a (day, time), not a time. Grouping on the clock alone was right while the pane
        // could only ever show one day; with a month on screen it would fold the 3rd's nine o'clock
        // into the 12th's — one header, one class title borrowed from whichever booking sorted
        // first, and no way to tell the two mornings apart. The header only SAYS the date when the
        // window is wider than a day, because on a day view the strip has already said it.
        val slots = attendees.map { it.day to it.clock }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        for ((day, clock) in slots) {
            val inSlot = attendees.filter { it.day == day && it.clock == clock }
            list.addView(slotHeader(if (wideWindow()) "$day · $clock" else clock, inSlot.first()))
            list.addView(cardGrid(inSlot))
        }
        scroll.addView(list)
        content.addView(scroll)
    }

    private fun slotHeader(slot: String, sample: RosterBridge.Attendee): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(14), px(12), px(4))
        }
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(ctx).apply {
            text = slot; textSize = 16f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
        })
        if (sample.eventTitle.isNotBlank()) labels.addView(TextView(ctx).apply {
            text = sample.eventTitle; textSize = 12f; setTextColor(Color.parseColor("#777777"))
        })
        row.addView(labels)

        if (sample.eventId > 0) {
            lateinit var bell: TextView
            bell = TextView(ctx).apply {
                textSize = 20f; setPadding(px(10), 0, px(10), 0)
                fun paint() {
                    val on = RecordPrefs.enabled(ctx, sample.eventId)
                    text = if (on) "🔔" else "🔕"   // bell / bell-slash
                    setTextColor(if (on) Color.parseColor("#2F6F96") else Color.parseColor("#999999"))
                }
                paint()
                setOnClickListener {
                    RecordPrefs.setEnabled(ctx, sample.eventId, !RecordPrefs.enabled(ctx, sample.eventId))
                    paint()
                    // Michael, 2026-08-07: "What does 'Record Prompt' mean in Roster?" — a fair
                    // question, because the label was the name of a PREFERENCE KEY rather than of
                    // a behaviour. It means: before this class starts, ask whether to record it
                    // and open its notes. So the toast says that.
                    toast(if (RecordPrefs.enabled(ctx, sample.eventId))
                        "Before this class: I'll offer to record and open its notes"
                    else "No reminder before this class")
                }
            }
            row.addView(bell)
        }
        row.addView(TextView(ctx).apply {
            text = "⏺"   // record dot
            textSize = 20f; setTextColor(Color.parseColor("#B00020")); setPadding(px(8), 0, px(8), 0)
            setOnClickListener { recordForToday() }
        })
        return row
    }

    /** Lay the slot's attendees out in wrapping rows of three. */
    private fun cardGrid(people: List<RosterBridge.Attendee>): View {
        val ctx = requireContext()
        val perRow = 3
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), 0, px(10), 0)
        }
        var rowView: LinearLayout? = null
        people.forEachIndexed { i, a ->
            if (i % perRow == 0) {
                rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(rowView)
            }
            rowView!!.addView(card(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(px(4), px(4), px(4), px(4))
            })
        }
        // Pad the last row so the final card doesn't stretch full-width.
        rowView?.let {
            val remainder = people.size % perRow
            if (remainder != 0) repeat(perRow - remainder) { _ ->
                it.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(px(4), 0, px(4), 0) })
            }
        }
        return grid
    }

    private fun card(a: RosterBridge.Attendee): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(8), px(10), px(8), px(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F4F4"))
                cornerRadius = px(12).toFloat()
            }
            alpha = if (a.status == "no-show" || a.status == "cancelled") 0.55f else 1f
            isClickable = true
            setOnClickListener { openDetail(a) }
        }
        val avatar = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(52), px(52))
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        loadPhotoInto(avatar, a.photo)
        box.addView(avatar)
        box.addView(TextView(ctx).apply {
            text = a.name; textSize = 13f; setTextColor(Color.BLACK); maxLines = 1
            setPadding(0, px(6), 0, 0)
        })
        box.addView(TextView(ctx).apply {
            text = a.status; textSize = 11f; setTextColor(tint(a.status))
        })
        return box
    }

    private fun tint(status: String): Int = when (status) {
        "scheduled" -> Color.parseColor("#2E7D32")
        "completed" -> Color.parseColor("#1565C0")
        "cancelled" -> Color.parseColor("#B00020")
        else -> Color.parseColor("#777777")
    }

    /** Fetch a public avatar to the card, off the main thread. Silent on failure. */
    private fun loadPhotoInto(view: ImageView, url: String) {
        if (url.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    // Bounded: a slow/hung avatar host must not pin an IO thread (one fires per card).
                    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 8_000; readTimeout = 8_000
                    }
                    c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (isAdded && bmp != null) view.setImageBitmap(bmp)
        }
    }

    /* ---------------------------------------------------------------
     * Attendee detail — their record, a jump to the CRM, and an ink
     * pad whose handwriting is OCR'd onto their CRM timeline.
     * ------------------------------------------------------------- */

    private fun openDetail(a: RosterBridge.Attendee) {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(18), px(22), px(14))
        }

        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(60), px(60)).apply { rightMargin = px(12) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        loadPhotoInto(avatar, a.photo)
        headRow.addView(avatar)
        headRow.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = a.name; textSize = 18f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD })
            if (a.email.isNotBlank()) addView(TextView(ctx).apply { text = a.email; textSize = 12f; setTextColor(Color.parseColor("#777777")) })
            addView(TextView(ctx).apply { text = "${a.clock} · ${a.status}"; textSize = 12f; setTextColor(Color.parseColor("#777777")) })
        })
        col.addView(headRow)

        if (a.crmContactId > 0) {
            col.addView(Button(ctx).apply {
                text = "Open CRM profile"; isAllCaps = false; textSize = 14f
                setPadding(0, px(10), 0, px(4))
                setOnClickListener { openCrmProfile(a) }
            })
        }

        col.addView(TextView(ctx).apply {
            text = "Note (handwritten → their CRM)"
            textSize = 12f; setTextColor(Color.parseColor("#777777")); setPadding(0, px(10), 0, px(6))
        })

        val ink = InkPadView(ctx)
        // Close / Save ABOVE the pad, not as platform buttons below it — the handwriting-panel
        // rule (see LedgerTitlePad): a hand resting under a 240dp pad palm-taps whatever is there.
        lateinit var dialog: AlertDialog
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, px(6))
            addView(TextView(ctx).apply {
                text = "CLOSE"; textSize = 15f; setTextColor(Color.parseColor("#555555"))
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(6), px(28), px(6))
                setOnClickListener { dialog.dismiss() }
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(ctx).apply {
                text = "SAVE TO CRM"; textSize = 15f; setTextColor(Color.parseColor("#2F6F96"))
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(px(28), px(6), 0, px(6))
                // First tap only — saveNote launches OCR + a CRM write; a palm bounce
                // used to run both twice (two paid recognitions, two CRM rows).
                setOnClickListener { isEnabled = false; saveNote(a, ink, dialog) }
            })
        })
        col.addView(InkPadView.penBar(ctx, ink))
        col.addView(FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(px(2), px(2), px(2), px(2))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(240)))
        })

        scroll.addView(col)

        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Attendee")
            .setView(scroll)
            .create()
        // The pad holds a CRM note mid-write — a palm outside the dialog must not cost the ink.
        // CLOSE / SAVE above the pad and the back gesture remain the ways out.
        showGuardedModal(dialog)
    }

    /** Open the attendee's FluentCRM record IN-APP: the wp-admin subscriber deep link rendered in the
     *  persistent-session [SiteWebFragment] WebView (sign in once, cookies stick), matching iOS where
     *  "Open CRM profile" pushes a SiteWebView. The path rides off the ROSTER's own site's base
     *  ([SiteWebFragment.ARG_BASE]) — the attendee's CRM record lives where their booking does. */
    private fun openCrmProfile(a: RosterBridge.Attendee) {
        val site = rosterSite()
        if (site == null && LedgerWebBridge.config(requireContext()).site.isBlank()) { toast("Site not configured"); return }
        val path = "wp-admin/admin.php?page=fluentcrm-admin#/subscribers/${a.crmContactId}"
        NavHostFragment.findNavController(this).navigate(
            R.id.action_to_site_web,
            androidx.core.os.bundleOf(
                SiteWebFragment.ARG_PATH to path,
                SiteWebFragment.ARG_TITLE to "CRM · ${a.name}",
                SiteWebFragment.ARG_BASE to (site?.url ?: "")
            )
        )
    }

    private fun saveNote(a: RosterBridge.Attendee, ink: InkPadView, dialog: AlertDialog) {
        val ctx = requireContext()
        val bmp: Bitmap? = ink.render()
        if (bmp == null) { toast("Write a note first"); return }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { PanelOcr.recognizeImage(bmp) }.trim()
            bmp.recycle()
            if (text.isEmpty()) { toast("Couldn't read the note"); return@launch }
            val ok = withContext(Dispatchers.IO) { RosterBridge.saveNote(ctx, a.crmContactId, a.email, text, rosterCfg()) }
            if (!isAdded) return@launch
            if (ok) {
                // Into the rhizome: the person joins the connection graph, linked to the session day.
                val personUri = if (a.crmContactId > 0) "crm://${a.crmContactId}" else "booking://${a.id}"
                withContext(Dispatchers.IO) {
                    ConnectionStore.connect(
                        ctx, personUri, "ledger://${a.day}/default",
                        fromLabel = a.name, toLabel = "Session · ${a.day}"
                    )
                    // Into the roots: the note's text joins the annotation corpus with the person as its
                    // provenance, so a session observation can rhyme with the rest (iOS parity).
                    com.toolsboox.plugin.chat.da.AnnotationCorpus.record(
                        ctx, surface = "crm",
                        id = "crmnote-${a.id}-${System.currentTimeMillis()}",
                        uri = personUri, label = a.name, text = text
                    )
                }
                toast("Saved to ${a.name}'s CRM")
                dialog.dismiss()
            } else {
                toast("Couldn't save the note")
            }
        }
    }

    /* ---------------------------------------------------------------
     * Record — an A/V gram for today, exactly like the day page's.
     * ------------------------------------------------------------- */

    private fun recordForToday() {
        captureAvGram { att -> persistGramToToday(att) }
    }

    /** Append a captured A/V gram to today's day JSON (same sink as the day page). */
    private fun persistGramToToday(att: Attachment) {
        val ctx = requireContext()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
                    val today = LocalDate.now()
                    val day = calendarDayService.load(root, today, null, Locale.getDefault())
                    day.avGrams.add(att)
                    calendarDayService.save(root, today, day)
                }
            }
            if (isAdded) toast("Recorded")
        }
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
