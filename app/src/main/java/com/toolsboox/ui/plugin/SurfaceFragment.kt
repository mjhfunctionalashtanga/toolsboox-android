package com.toolsboox.ui.plugin

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.utils.DeviceFeatureUtil
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.R
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.databinding.ToolbarDrawingBinding
import com.toolsboox.ot.LedgerContextMenu
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.ot.StrokeClipboard
import com.toolsboox.plugin.calendar.CalendarNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.*
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SurfaceView fragment of Boox pen and native stylus supports.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
abstract class SurfaceFragment : ScreenFragment() {

    companion object {
        const val CANVAS_WIDTH = 1404
        const val CANVAS_HEIGHT = 1872
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 4.0f

        /**
         * Debounce window for re-applying the Onyx raw-drawing limit rect after a
         * surfaceChanged. On open the surface is resized more than once (toolbar/immersive
         * relayout), and each limit-rect re-apply toggles setRawDrawingEnabled false->true,
         * which cold-starts the native pen reader (seconds to spin up on the Go 6 Gen 2).
         * Coalescing to the final size cold-starts the reader at most once.
         */
        private const val LIMIT_RECT_DEBOUNCE_MS = 250L

        /**
         * Delay before re-baking committed strokes to the SurfaceView after a pen-up. The
         * live Onyx hardware overlay already shows the stroke, so the heavy full-canvas
         * re-post (redraw all strokes + a blocking surface post) doesn't need to run on every
         * lift — doing so hogs the main thread and delays delivery of the next pen-down
         * (~1s "won't start writing" on the Go 6 Gen 2). Deferring and resetting it on each
         * new stroke means rapid write-lift-write stays fluid; the bake fires once the user
         * actually pauses. Storage save still happens immediately on pen-up.
         */
        private const val COMMIT_VISUAL_DEBOUNCE_MS = 700L

        /**
         * Always-on ink-gesture thresholds (canvas space, 1404×1872). Tunable — these
         * govern how aggressively a stroke is read as a circle (→ lasso) or a scribble
         * (→ erase) rather than committed as ink. Raise to reduce false positives.
         */
        // Size FLOOR only — a degenerate-loop guard, NOT the safeguard against false lassos.
        // The real discriminator is "does the ring enclose a DIFFERENT existing stroke"
        // (see enclosesOtherInk): a small tight ring around a letter should lasso, but a
        // handwritten "e"/"o" encloses only itself → rejected. So the floor can be tiny.
        // User clue: circling TIGHTLY around one letter made a <140px ring the old gate ate.
        private const val GESTURE_MIN_CIRCLE_DIAG = 60f        // reject only near-degenerate loops; enclosure does the real work
        private const val GESTURE_MIN_POINTS = 6               // a fast Boox capture can be sparse; 8→6 so quick loops pass.
        private const val GESTURE_ENCLOSE_MAJORITY = 0.5f      // an existing stroke counts as encircled if ≥ this fraction of
                                                               // its points fall inside the ring (tolerates grazing/overlap)

        // Circle-FIT detector (replaces the wobble-sensitive net/abs-turning heuristic that
        // failed on shaky hand-drawn rings). Fit a circle to the points: centroid, mean
        // radius r̄, radial band, and angular coverage around the centroid. Tolerant of
        // ovals, wobble, and an unclosed loop.
        // Device toast from two real failed lassos: in-band 65–68%, coverage 360°,
        // enclosesOtherInk=yes — i.e. ONLY the radial-band gate rejected clearly-intentional
        // rings. Widened band 0.35→0.45 (raises in-band %) and dropped the threshold 0.72→0.55
        // so wobbly hand-drawn rings pass with margin; coverage(≥270°)+enclosesOtherInk still guard.
        private const val GESTURE_CIRCLE_BAND = 0.45f          // a point is "on the ring" if |r_i − r̄| ≤ this × r̄
        private const val GESTURE_CIRCLE_BAND_FRAC = 0.55f     // ≥ this fraction of points must sit in that radial band
        private const val GESTURE_CIRCLE_COVERAGE_DEG = 270f   // points must wrap ≥ this many degrees around the centroid…
        private const val GESTURE_CIRCLE_CLOSURE_R = 0.5f      // …OR the endpoint returns within this × r̄ of the start
        private const val GESTURE_DEBUG_TOAST = false          // temp: toast the deciding metrics on a REJECTED ring attempt
        private const val GESTURE_SCRIBBLE_LENGTH_RATIO = 3.8f // path length ≥ this × bbox diagonal — a tight, in-place
                                                               // back-and-forth. Cursive flows sideways (low ratio), so
                                                               // this is the main thing that stops cursive erasing; eased
                                                               // 4.5→3.8 (kept high enough to spare cursive).
        private const val GESTURE_SCRIBBLE_MIN_ABS_TURN = 8.5f // total accumulated wiggle (radians); eased 11→8.5
        private const val GESTURE_SCRIBBLE_MIN_REVERSALS = 4   // minimum direction reversals; eased 6→4 — these two are the
                                                               // zig-zag signal, loosened for reliable firing; the length
                                                               // ratio above still guards against normal writing.

        /**
         * Calligraphy nib width as a multiple of the base stroke width, scaled by pen
         * pressure. Lightest touch ≈ MIN, firmest ≈ MAX — the spread is what gives the
         * baked stroke its fountain-pen taper. Tunable.
         */
        private const val CALLIGRAPHY_MIN_FACTOR = 0.18f    // thinnest — stroke running ALONG the nib
        private const val CALLIGRAPHY_MAX_FACTOR = 2.8f      // thickest — stroke running ACROSS the nib
        private const val CALLIGRAPHY_NIB_ANGLE_DEG = 45f    // broad-nib orientation (classic italic)

        // Calligraphy uses its own narrower base-width mapping: the shared width
        // presets read too thick through the broad-nib factors (MJH: "standard
        // calligraphy medium is a bit too thick"). 0.72 thins every preset ~28%
        // proportionally, so the thin/medium/thick ladder still feels even and
        // ballpoint widths are untouched.
        private const val CALLIGRAPHY_WIDTH_SCALE = 0.72f

        /** Pasted images: downscale the longest side to this on import, and place at this fraction of page width. */
        private const val IMAGE_MAX_DIM = 1400
        private const val IMAGE_PLACE_FRACTION = 0.6f
        private const val IMAGE_HANDLE_SIZE = 64f   // pen-friendly resize handle
        private const val IMAGE_CHIP_SIZE = 64f     // pen-friendly delete chip
        private const val MIN_TEXTBOX_WIDTH = 80f   // narrowest a text box may be dragged before words stop reflowing

        /**
         * Touch drawing state.
         */
        private var touchDrawingState: Boolean = false

        /**
         * When true, a single finger can pan / swipe-navigate (convenient for browsing).
         * When false (default), gestures require two fingers — palm rejection while
         * writing with a stylus.
         */
        @JvmStatic protected var singleFingerGesturesEnabled: Boolean = false

        // List of unrecognized actions.
        private val actions = mutableListOf<String>()

        // List of unrecognized buttons.
        private val buttons = mutableListOf<String>()
    }

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var privateFirebaseAnalytics: FirebaseAnalytics

    /**
     * The injected presenter.
     */
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    /**
     * The Moshi instance.
     */
    @Inject
    lateinit var moshi: Moshi

    /**
     * The stroke clipboard singleton.
     */
    @Inject
    lateinit var strokeClipboard: StrokeClipboard

    // --- Lasso selection state ---
    /** True while the lasso tool is active (drawing the selection polygon). */
    private var selectionMode = false

    /** True after a lasso is completed and strokes are selected, waiting for copy/paste. */
    private var hasSelection = false

    /** Points of the lasso polygon being drawn. */
    private var selectionPoints = mutableListOf<PointF>()

    /** Strokes that fell inside the lasso polygon. */
    private var selectedStrokes = mutableListOf<Stroke>()

    /** Bounding box (canvas-space) of the current selection, set when the lasso closes. */
    private var selBox: RectF? = null

    /** Always-on gestures: a circle around ink auto-lassos it; a scribble over ink auto-erases it. */
    private var autoGesturesEnabled = true

    /** When true the pen lays down pressure-variable calligraphy ink (live via the Onyx fountain nib). */
    private var calligraphyMode = false

    /** Image insert/manipulate mode (toolbar image button): select, move, resize, delete pasted images. */
    private var imageMode = false
    private var imageElements: MutableList<ImageElement> = mutableListOf()
    private var selectedImage: ImageElement? = null
    private enum class ImageDrag { NONE, MOVE, RESIZE }
    private var imageDrag = ImageDrag.NONE
    private var imageDragStartX = 0f
    private var imageDragStartY = 0f

    /** Text box selected in element-manipulation mode (dragged like an image). */
    private var selectedTextBox: TextElement? = null
    private var textBoxDrag = false
    private var textBoxResize = false
    private var textBoxOrigX = 0f
    private var textBoxOrigY = 0f
    private var imageOrigRect = RectF()
    private var cropMode = false
    private var cropDragging = false
    private var cropRect: RectF? = null
    private var cropStartX = 0f
    private var cropStartY = 0f
    private val imageBitmapCache = HashMap<UUID, Bitmap>()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    /** Photo/file picker fallback when the clipboard has no image. Registered at construction. */
    private val imagePickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { handlePickedImage(it) }
        }
    }

    /** Image URI waiting for the page data to finish loading before insertion. */
    private var deferredInsertUri: Uri? = null

    /**
     * True when the fragment's page data is loaded enough to accept element
     * changes. Activity results (picker/camera) can arrive BEFORE the async
     * page load completes — inserting then crashes on lateinit page state and
     * the insert would be wiped by the load's renderPage anyway.
     */
    open fun isPageDataReady(): Boolean = true

    /** Insert a picked/captured image now, or defer it until the page data is ready. */
    private fun handlePickedImage(uri: Uri) {
        if (isPageDataReady()) {
            insertImageFromUri(uri)
        } else {
            Timber.i("Page data not ready; deferring image insert")
            deferredInsertUri = uri
        }
    }

    /** Queue an externally shared image (share-to-Ledger) — inserts now or defers to page load. */
    fun queueSharedImageInsert(uri: Uri) = handlePickedImage(uri)

    /** Shared text (and its intact source URL) waiting for page data before insert. */
    private var deferredInsertText: Pair<String, String?>? = null

    /** Queue externally shared text (share-to-Ledger) — inserts now or defers to page load. */
    fun queueSharedTextInsert(text: String, sourceUrl: String? = null) {
        if (isPageDataReady()) {
            insertSharedTextBox(text, sourceUrl)
        } else {
            Timber.i("Page data not ready; deferring text insert")
            deferredInsertText = text to sourceUrl
        }
    }

    /** Insert shared text as a movable text box, centered on the page. */
    private fun insertSharedTextBox(text: String, sourceUrl: String? = null) {
        val lines = text.split("\n")
        val fontSize = 24f
        val approxWidth = (lines.maxOf { it.length } * fontSize * 0.55f).coerceIn(200f, CANVAS_WIDTH - 100f)
        val approxHeight = lines.size * fontSize * 1.25f
        val x = ((CANVAS_WIDTH - approxWidth) / 2f).coerceAtLeast(50f)
        val y = ((CANVAS_HEIGHT - approxHeight) / 2f).coerceIn(100f, CANVAS_HEIGHT - 100f)
        val element = TextElement(x = x, y = y, text = text, fontSize = fontSize, sourceUrl = sourceUrl)
        textElements.add(element)
        onTextElementsChanged(textElements)
        // Land selected and draggable, same as a freshly inserted image.
        enterTextBoxManipulation(element)
    }

    /**
     * A text-box drag just ended. Fragments override to react to where the box
     * landed (e.g. the intake page files a link dropped onto a panel).
     */
    open fun onTextBoxDropped(element: TextElement) {}

    /** Called by fragments once page data is loaded — completes a deferred insert. */
    fun consumeDeferredImageInsert() {
        deferredInsertText?.let { (text, sourceUrl) ->
            deferredInsertText = null
            Timber.i("Completing deferred text insert (%d chars)", text.length)
            insertSharedTextBox(text, sourceUrl)
        }
        val uri = deferredInsertUri ?: return
        deferredInsertUri = null
        Timber.i("Completing deferred image insert: %s", uri)
        insertImageFromUri(uri)
    }

    /** Where the next inserted element should land (canvas coords) — set by long-press. */
    private var pendingPlacePoint: PointF? = null

    /** Camera capture target for the long-press "Image — camera" flow. */
    private var pendingCameraUri: Uri? = null

    /** Camera capture launcher (long-press menu). Registered at construction. */
    private val cameraCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            handlePickedImage(uri)
        }
    }

    /** Which transform is currently being driven by the stylus (NONE = idle). */
    private enum class SelectionDrag { NONE, HANDLE_TL, HANDLE_TR, HANDLE_BL, HANDLE_BR, MOVE }
    private var selectionDrag = SelectionDrag.NONE

    /** Anchor (opposite corner) for the active scale drag and the bbox snapshot at drag start. */
    private var scaleAnchorX = 0f
    private var scaleAnchorY = 0f
    private var scaleOrigBox: RectF? = null

    /** Pen-down canvas coords when a MOVE drag begins. */
    private var moveStartX = 0f
    private var moveStartY = 0f

    /**
     * Snapshot of the original (x,y) for every point of every selected stroke at the
     * moment a scale drag starts. Each ACTION_MOVE re-derives the stroke positions from
     * this snapshot so the math stays linear and doesn't compound across ticks.
     */
    private var scaleOrigPoints: Map<UUID, List<Pair<Float, Float>>> = emptyMap()

    /** True while in paste-placement mode (next tap places the clipboard contents). */
    private var pasteMode = false

    // --- Undo/redo history ---
    /** Combined undo/redo snapshot so strokes and images undo together, in chronological order. */
    private class CanvasSnapshot(val strokes: List<Stroke>, val images: List<ImageElement>)
    private val undoStack = mutableListOf<CanvasSnapshot>()
    private val redoStack = mutableListOf<CanvasSnapshot>()

    // --- Zoom and pan state ---
    protected var twoFingerGesture = false
    private var zoomScale = 1.0f
    private var panX = 0.0f
    private var panY = 0.0f
    private var baseScale = 1.0f
    private val viewMatrix = Matrix()
    private val inverseViewMatrix = Matrix()
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var doubleTapDetector: GestureDetector? = null
    // Small floating grip that overlays the canvas to reopen the toolbar when it's collapsed to
    // zero width (so the calendar can use the full screen width). Created lazily.
    private var toolbarReopenHandle: View? = null
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var isPanning = false

    // --- Text tool state ---
    /** True while in text placement mode (next tap opens text input dialog). */
    private var textMode = false

    /** The list of text elements on the current page. */
    private var textElements: MutableList<TextElement> = mutableListOf()

    /** Paint used for rendering text elements on canvas. */
    // TextPaint (not plain Paint) so it can back a StaticLayout for word-wrapping.
    private var textPaint = TextPaint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.BLACK
        textSize = 24f
    }

    /**
     * The paint in the bitmap.
     */
    private var paint = Paint()

    /**
     * Paint for the lasso polygon line.
     */
    private var lassoPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.DKGRAY
        strokeWidth = 2.0f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    /**
     * Paint for highlighting selected strokes.
     */
    private var selectionHighlightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.DKGRAY
        strokeWidth = 5.0f
    }

    /**
     * TouchHelper of the Onyx's pen.
     */
    private var touchHelper: TouchHelper? = null

    /**
     * Viwoods AiPaper fast-ink backend. Non-null only on Viwoods hardware (where the Onyx
     * TouchHelper is inert); puts the panel into the FAST e-ink waveform so our software
     * stroke rendering refreshes quickly. Null on Boox.
     */
    private var viwoodsInk: com.toolsboox.ot.ViwoodsFastInk? = null

    /**
     * Coalescing guard for the Viwoods software live-ink preview. The digitizer fires many
     * ACTION_MOVE events per second (sometimes several per frame); each one would otherwise
     * trigger a full-stroke lockCanvas + EPD FAST refresh. Those serialize on the panel and
     * build a backlog, so the ink trails the pen. We capture every point but post at most
     * once per display frame via [viwoodsLivePostRunnable], which drains the backlog and
     * keeps the live stroke tight to the nib. Viwoods software path only.
     */
    private var viwoodsLivePostScheduled = false
    /** Index into stylusPointList of the last point already posted to the panel; the live EPD
     *  refresh covers only points since this index, so update cost is O(new segment), not O(stroke). */
    private var viwoodsLastLiveIndex = 0
    /** Cached page snapshot for the hardware-ink path, re-pushed each move to beat the async
     *  engine-readiness race (see onBeginDrawing). Generated once per stroke (O(strokes) render),
     *  re-pushed cheaply (native call only). */
    private var viwoodsCachedSnapshot: android.graphics.Bitmap? = null
    private val viwoodsLivePostRunnable = Runnable {
        viwoodsLivePostScheduled = false
        if (penState && stylusPointList.isNotEmpty()) renderLivePreviewSoftware()
    }

    /**
     * The gesture detector
     */
    protected lateinit var gestureDetector: GestureDetectorCompat

    /**
     * The gesture listener
     */
    protected lateinit var gestureListener: OnGestureListener

    /**
     * The bitmap of the canvas (for export).
     */
    private var bitmap: Bitmap? = null

    /**
     * The canvas of the surface view (for export).
     */
    private lateinit var canvas: Canvas

    /**
     * The callback of the surface holder.
     */
    private var surfaceCallback: SurfaceHolder.Callback? = null

    /**
     * The last point of the stroke.
     */
    private var lastPoint: StrokePoint? = null

    // First point timestamp.
    private var firstPointTimestamp = 0L

    /**
     * The list of stylus points.
     */
    private val stylusPointList: MutableList<StrokePoint> = mutableListOf()

    /**
     * The list of strokes.
     */
    private var strokes: MutableList<Stroke> = mutableListOf()

    /**
     * The list of strokes to add.
     */
    private var strokesToAdd: MutableList<Stroke> = mutableListOf()

    /**
     * The actual size of the surface.
     */
    private var surfaceSize: Rect = Rect(0, 0, 0, 0)

    /** Surface size for which the Onyx limit rect is currently applied (-1 = none yet). */
    private var appliedLimitWidth = -1
    private var appliedLimitHeight = -1

    /** Latest surface size awaiting a (debounced) limit-rect apply. */
    private var pendingLimitWidth = 0
    private var pendingLimitHeight = 0

    /** Coalesces rapid/duplicate surfaceChanged callbacks into a single reader reconfigure. */
    private val applyLimitRectRunnable = Runnable { applyPendingLimitRect() }

    /** Deferred full-canvas re-bake after a pen-up; see [COMMIT_VISUAL_DEBOUNCE_MS]. */
    private val commitVisualRunnable = Runnable { applyStrokes(strokes, false) }

    /**
     * Pen or eraser state.
     */
    private var penState: Boolean = true

    // In case of eraser, is it procrastinator?
    private var procrastinator: Boolean = false

    /**
     * The canvas of the navigator.
     */
    protected lateinit var navigatorCanvas: Canvas

    /**
     * The bitmap of the navigator.
     */
    protected lateinit var navigatorBitmap: Bitmap

    /**
     * The canvas of the template.
     */
    protected lateinit var templateCanvas: Canvas

    /**
     * The bitmap of the template.
     */
    protected lateinit var templateBitmap: Bitmap

    /**
     * SurfaceView provide method.
     *
     * @return the actual surfaceView
     */
    abstract fun provideSurfaceView(): SurfaceView

    /**
     * Provide toolbar of drawing's bindings.
     *
     * @return the actual bindings of toolbar of drawings
     */
    abstract fun provideToolbarDrawing(): ToolbarDrawingBinding

    /**
     * Add strokes callback.
     *
     * @param strokes list of strokes
     */
    open fun onStrokesAdded(strokes: List<Stroke>) {}

    /**
     * On side switched event.
     */
    open fun onSideSwitched() {}

    /**
     * Delete strokes callback.
     *
     * @param strokeIds the list UUID of the strokes
     */
    open fun onStrokesDeleted(strokeIds: List<UUID>) {}

    /**
     * Stroke changed callback.
     *
     * @param strokes the actual strokes
     */
    open fun onStrokeChanged(strokes: MutableList<Stroke>) {}

    /**
     * Strokes procrastinated callback.
     *
     * @param strokes the strokes to procrastinate
     */
    open fun onStrokesProcrastinated(strokes: List<Stroke>) {}

    /**
     * Text elements changed callback.
     *
     * @param textElements the current text elements
     */
    open fun onTextElementsChanged(textElements: MutableList<TextElement>) {}

    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()

        // Immersive fullscreen: hide system bars + action bar, draw into the display
        // cutout area, so the canvas truly fills the whole screen. System bars can
        // still be revealed by swiping from the edge for back/home access.
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.hide()
        requireActivity().window.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        // The activity's DrawerLayout has fitsSystemWindows="true" which reserves
        // space for the now-hidden bars (visible as black strips). Turn it off here
        // and restore in onPause so the settings/about screens still inset correctly.
        requireActivity().findViewById<View>(R.id.drawerLayout)?.let { drawer ->
            drawer.fitsSystemWindows = false
            drawer.setPadding(0, 0, 0, 0)
        }

        initializeSurface()
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = true
        // Viwoods FAST waveform is (re)activated from surfaceChanged once dimensions are known.

        penState = true
        selectionMode = false
        hasSelection = false
        pasteMode = false
        textMode = false
        selectionPoints.clear()
        selectedStrokes.clear()
        provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
        provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarCopy.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)

        // Restore the single-finger-gestures preference, then sync the toolbar icon.
        singleFingerGesturesEnabled = sharedPreferences.getBoolean("singleFingerGesturesEnabled", false)
        autoGesturesEnabled = sharedPreferences.getBoolean("autoGesturesEnabled", true)
        calligraphyMode = sharedPreferences.getBoolean("calligraphyMode", false)
        if (singleFingerGesturesEnabled)
            provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_draw)
        else
            provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_touch)

        provideToolbarDrawing().toolbarPen.setOnClickListener {
            if (penState && !procrastinator) {
                showPenSettingsDialog()
            } else {
                penState = true
                procrastinator = false
                textMode = false
                exitSelectionMode()
                provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
                provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
            }
        }

        provideToolbarDrawing().toolbarEraser.setOnClickListener {
            penState = false
            procrastinator = false
            textMode = false
            exitSelectionMode()
            provideToolbarDrawing().toolbarPen.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarEraser.background.setTint(Color.GRAY)
            provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
        }

        provideToolbarDrawing().toolbarProcrastinator.visibility = View.GONE
        provideToolbarDrawing().toolbarProcrastinator.setOnClickListener {
            penState = false
            procrastinator = true
            textMode = false
            exitSelectionMode()
            provideToolbarDrawing().toolbarPen.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.GRAY)
            provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
        }

        // --- Lasso selection button ---
        provideToolbarDrawing().toolbarLasso.setOnClickListener {
            if (selectionMode || hasSelection) {
                // Toggle off: exit selection mode
                exitSelectionMode()
                penState = true
                provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
                provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
                applyStrokes(strokes, true)
            } else {
                // Enter lasso mode
                selectionMode = true
                pasteMode = false
                textMode = false
                penState = true // keep pen state so stylus draws the lasso
                procrastinator = false
                provideToolbarDrawing().toolbarPen.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarLasso.background.setTint(Color.GRAY)
                provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
                syncRawInkToSelectionMenu()   // lasso-draw up → stylus paints no stray hardware ink
            }
        }

        // --- Copy button ---
        provideToolbarDrawing().toolbarCopy.setOnClickListener {
            val img = selectedImage
            when {
                img != null -> {
                    strokeClipboard.copyImage(img)
                    showMessage(R.string.calendar_drawing_toolbar_copied, provideSurfaceView())
                }
                selectedStrokes.isNotEmpty() -> {
                    strokeClipboard.copy(selectedStrokes.toList())
                    showMessage(R.string.calendar_drawing_toolbar_copied, provideSurfaceView())
                }
                else -> showMessage(R.string.calendar_drawing_toolbar_nothing_selected, provideSurfaceView())
            }
        }

        // --- Paste button ---
        provideToolbarDrawing().toolbarPaste.setOnClickListener {
            if (!strokeClipboard.hasContent) {
                showMessage(R.string.calendar_drawing_toolbar_clipboard_empty, provideSurfaceView())
                return@setOnClickListener
            }
            if (strokeClipboard.hasImage) {
                pasteClipboardImage()
                return@setOnClickListener
            }
            pasteMode = true
            selectionMode = false
            textMode = false
            hasSelection = false
            selectedStrokes.clear()
            selectionPoints.clear()
            penState = true
            procrastinator = false
            provideToolbarDrawing().toolbarPen.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
            provideToolbarDrawing().toolbarPaste.background.setTint(Color.GRAY)
            exitImageMode()
            syncRawInkToSelectionMenu()   // paste-place mode up → tap places ink, doesn't dot
            showMessage(R.string.calendar_drawing_toolbar_paste, provideSurfaceView())
        }

        // --- Text tool button ---
        provideToolbarDrawing().toolbarText.setOnClickListener {
            if (textMode) {
                // Toggle off: exit text mode, return to pen
                textMode = false
                penState = true
                provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
            } else {
                // Enter text placement mode
                textMode = true
                pasteMode = false
                selectionMode = false
                hasSelection = false
                penState = true
                procrastinator = false
                selectedStrokes.clear()
                selectionPoints.clear()
                provideToolbarDrawing().toolbarPen.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarEraser.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarProcrastinator.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
                provideToolbarDrawing().toolbarText.background.setTint(Color.GRAY)
                exitImageMode()
            }
            // Switching into/out of text clears the selection states → restore the hardware
            // pen if a prior lasso/paste had paused it (else the pen would stay dead).
            syncRawInkToSelectionMenu()
        }

        provideToolbarDrawing().toolbarHandTouch.setOnClickListener {
            singleFingerGesturesEnabled = !singleFingerGesturesEnabled
            sharedPreferences.edit().putBoolean("singleFingerGesturesEnabled", singleFingerGesturesEnabled).apply()
            if (singleFingerGesturesEnabled)
                provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_draw)
            else
                provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_touch)
            val msg = if (singleFingerGesturesEnabled) "One-finger gestures on" else "Two-finger gestures required"
            showMessage(msg, provideSurfaceView())
        }

        provideToolbarDrawing().toolbarUndo.setOnClickListener {
            if (undoStack.isNotEmpty()) {
                redoStack.add(snapshotCanvas())
                val snap = undoStack.removeAt(undoStack.size - 1)
                strokes = snap.strokes.toMutableList()
                imageElements = snap.images.map { it.copy() }.toMutableList()
                imageBitmapCache.clear()
                selectedImage = null
                applyStrokes(strokes, true)
                onStrokeChanged(strokes)
                onImageElementsChanged(imageElements)
            }
        }

        provideToolbarDrawing().toolbarRedo.setOnClickListener {
            if (redoStack.isNotEmpty()) {
                undoStack.add(snapshotCanvas())
                val snap = redoStack.removeAt(redoStack.size - 1)
                strokes = snap.strokes.toMutableList()
                imageElements = snap.images.map { it.copy() }.toMutableList()
                imageBitmapCache.clear()
                selectedImage = null
                applyStrokes(strokes, true)
                onStrokeChanged(strokes)
                onImageElementsChanged(imageElements)
            }
        }

        provideToolbarDrawing().toolbarTrash.setOnClickListener {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this.requireContext())
            builder.setTitle(R.string.calendar_drawing_toolbar_trash_dialog_title)
                .setMessage(R.string.calendar_drawing_toolbar_trash_dialog_message)
                .setPositiveButton(R.string.ok) { dialog, _ ->
                    val strokesToRemove: MutableSet<UUID> = mutableSetOf()
                    for (stroke in strokes) {
                        strokesToRemove.add(stroke.strokeId)
                    }
                    strokes.clear()
                    onStrokesDeleted(strokesToRemove.toList())

                    // Also clear text elements
                    textElements.clear()
                    onTextElementsChanged(textElements)

                    applyStrokes(strokes, true)
                    onStrokeChanged(strokes)
                    dialog.cancel()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    dialog.cancel()
                }
            builder.create().show()
        }

        // Fold "trash" into the eraser: hide the standalone trash button and clear the
        // page via a long-press on the eraser instead.
        provideToolbarDrawing().toolbarTrash.visibility = View.GONE
        provideToolbarDrawing().toolbarEraser.setOnLongClickListener {
            provideToolbarDrawing().toolbarTrash.performClick(); true
        }

        provideToolbarDrawing().toolbarSwitchSide.setOnClickListener {
            onSideSwitched()
        }

        provideToolbarDrawing().toolbarCloudSync.setOnClickListener {
            CalendarNavigator.toCloudSync(this)
        }

        provideToolbarDrawing().toolbarRotate.setOnClickListener {
            val activity = requireActivity()
            val current = activity.requestedOrientation

            // Build the cycle order from the user's preference (bitmask).
            // SCREEN_ORIENTATION_LANDSCAPE == landscape CCW (top tilts left in Android terms)
            // SCREEN_ORIENTATION_REVERSE_LANDSCAPE == landscape CW (top tilts right)
            val mask = sharedPreferences.getInt("rotationOrientationMask", 0b1111)
            val cycle = mutableListOf<Int>()
            if (mask and 0b0001 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            if (mask and 0b0010 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
            if (mask and 0b0100 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            if (mask and 0b1000 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            if (cycle.isEmpty()) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

            val idx = cycle.indexOf(current).takeIf { it >= 0 } ?: -1
            activity.requestedOrientation = cycle[(idx + 1) % cycle.size]
        }

        // Hide the cloud sync feature in case of regular users or enable it generally.
        val androidId = sharedPreferences.getString("androidId", "")
        val earlyAdopterDeviceIdsJson = sharedPreferences.getString("earlyAdopterDeviceIds", "[]")

        val earlyAdopterDeviceIdsType = Types.newParameterizedType(MutableList::class.java, String::class.java)
        val jsonAdapter = moshi.adapter<List<String>>(earlyAdopterDeviceIdsType)
        val earlyAdopterDeviceIds = jsonAdapter.fromJson(earlyAdopterDeviceIdsJson!!)

        val googleDrivePluginEnabled = sharedPreferences.getString("googleDrivePluginEnabled", "false").toBoolean()
        Timber.i("Google Drive plugin enabled: $googleDrivePluginEnabled")

        val earlyAdopter = earlyAdopterDeviceIds?.contains(androidId) ?: false
        Timber.i("Early adopter: $earlyAdopter")

        // Cloud sync moves off the strip (reachable from the Go-to panel / Settings).
        provideToolbarDrawing().toolbarCloudSync.visibility = View.GONE

        provideToolbarDrawing().toolbarSettings.setOnClickListener {
            CalendarNavigator.toSettings(this)
        }

        val toolbarCollapsed = sharedPreferences.getBoolean("toolbarCollapsed", false)
        applyToolbarCollapsedState(toolbarCollapsed)

        val toggleAction = View.OnClickListener {
            val collapsed = !sharedPreferences.getBoolean("toolbarCollapsed", false)
            sharedPreferences.edit().putBoolean("toolbarCollapsed", collapsed).apply()
            applyToolbarCollapsedState(collapsed)
        }
        provideToolbarDrawing().toolbarToggle.setOnClickListener(toggleAction)
        provideToolbarDrawing().root.setOnClickListener(toggleAction)

        templateBitmap = Bitmap.createBitmap(1404, 1872, Bitmap.Config.ARGB_8888)
        templateCanvas = Canvas(templateBitmap)

        navigatorBitmap = Bitmap.createBitmap(1404, 140, Bitmap.Config.ARGB_8888)
        navigatorCanvas = Canvas(navigatorBitmap)

        gestureListener = OnGestureListener()
        gestureDetector = GestureDetectorCompat(requireActivity(), gestureListener)

        // Opening the planner is the one moment the device is reliably awake and online,
        // so push any strokes stranded on disk from a prior session. Boox battery
        // management suppresses the 60-min periodic worker while the tablet sleeps for
        // days, and the onPause one-shot can be lost if Doze fires before it runs — so
        // without this trigger the cloud (and the downstream OCR pipeline) can sit stale
        // until the next charge. syncNow() carries the full current state, so a redundant
        // push here is harmless.
        try {
            com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker.syncNow(requireContext())
        } catch (_: Exception) {}
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        // Restore system bars and action bar so other screens (settings, etc.) behave normally.
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.show()
        requireActivity().window.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().findViewById<View>(R.id.drawerLayout)?.fitsSystemWindows = true

        // Drop pending debounced work (limit-rect apply, deferred stroke re-bake) so it can't
        // run after teardown. Strokes are already persisted on pen-up; the page re-renders on
        // return, so a missed bake is harmless.
        try {
            provideSurfaceView().removeCallbacks(applyLimitRectRunnable)
            provideSurfaceView().removeCallbacks(commitVisualRunnable)
        } catch (_: Exception) {}

        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false

        touchHelper?.closeRawDrawing()
        bitmap?.recycle()

        // Tear down Viwoods AutoDraw and return the panel to reading mode. No-op on Boox.
        viwoodsInk?.disable()

        // Release the screen-on flag so the device can enter Doze when the planner is
        // backgrounded. Without this the SoC stays awake — on e-ink the frozen frame
        // looks "off" but Wi-Fi/CPU never sleep, draining the battery overnight.
        try {
            provideSurfaceView().keepScreenOn = false
        } catch (_: Exception) {}

        try {
            com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker.syncNow(requireContext())
        } catch (_: Exception) {}
    }

    private fun applyToolbarCollapsedState(collapsed: Boolean) {
        val toolbar = provideToolbarDrawing()
        val group = toolbar.toolbarButtonGroup
        val density = resources.displayMetrics.density
        if (collapsed) {
            group.visibility = View.GONE
            toolbar.toolbarToggle.visibility = View.GONE
            toolbar.root.setBackgroundColor(Color.LTGRAY)
            toolbar.root.layoutParams?.let { lp ->
                // Slim collapsed handle, kept in the layout flow (zero-width + overlay broke the
                // page rendering). The back-gesture exclusion below makes it reliably tappable
                // without needing to be wide.
                lp.width = (16 * density).toInt()
                toolbar.root.layoutParams = lp
            }
            excludeToolbarFromBackGesture()
        } else {
            group.visibility = View.VISIBLE
            toolbar.toolbarToggle.visibility = View.VISIBLE
            toolbar.root.setBackgroundColor(Color.TRANSPARENT)
            // Fit the buttons to the available height. Portrait has room for one comfortable
            // column; landscape (short height) would force a single column below a tappable size,
            // so add columns and tile every visible button into an even, column-major grid.
            val buttonViews = listOf(
                toolbar.toolbarHandTouch, toolbar.toolbarPen, toolbar.toolbarEraser,
                toolbar.toolbarProcrastinator, toolbar.toolbarLasso, toolbar.toolbarCopy,
                toolbar.toolbarPaste, toolbar.toolbarText, toolbar.toolbarUndo,
                toolbar.toolbarRedo, toolbar.toolbarTrash, toolbar.toolbarCalendarView,
                toolbar.toolbarSwipeUp, toolbar.toolbarSwipeDown, toolbar.toolbarSwitchSide,
                toolbar.toolbarCloudSync, toolbar.toolbarRotate, toolbar.toolbarSettings
            )
            val visibleList = buttonViews.filter { it.visibility == View.VISIBLE }
            val visibleCount = visibleList.size.coerceAtLeast(1)
            val availDp = resources.configuration.screenHeightDp
            val minButtonDp = 28
            val maxButtonDp = 40
            // Below this a single column is too cramped; add a column instead of shrinking further.
            val comfortableButtonDp = 30

            val singleColButtonDp = (availDp / visibleCount).coerceIn(minButtonDp, maxButtonDp)
            val columns = if (singleColButtonDp >= comfortableButtonDp) {
                1
            } else {
                val rowsPerColumn = (availDp / maxButtonDp).coerceAtLeast(1)
                Math.ceil(visibleCount.toDouble() / rowsPerColumn).toInt().coerceAtLeast(1)
            }
            val rows = Math.ceil(visibleCount.toDouble() / columns).toInt().coerceAtLeast(1)
            val buttonPx = ((availDp / rows).coerceIn(minButtonDp, maxButtonDp) * density).toInt()

            if (columns <= 1) {
                // Single column (portrait): keep the XML layout (tools at top, nav pinned to the
                // bottom), just sized to fit. Fresh XML is restored on rotation recreation.
                resizeToolbarButtons(buttonViews, buttonPx)
                toolbar.root.layoutParams?.let { lp ->
                    lp.width = buttonPx
                    toolbar.root.layoutParams = lp
                }
                applyToolbarTwoColumnLayout(false)
            } else {
                // Multiple columns (landscape / short screens): tile every visible button into an
                // even column-major grid so the columns are uniform and none overflows the height.
                applyToolbarGridLayout(visibleList, columns, buttonPx)
                toolbar.root.layoutParams?.let { lp ->
                    lp.width = columns * buttonPx
                    toolbar.root.layoutParams = lp
                }
            }
            excludeToolbarFromBackGesture()
        }
    }

    /**
     * Claim the docked toolbar's left-edge band from the system back gesture, so swipes/taps that
     * land on the toolbar (especially the thin collapsed handle) open it instead of navigating Back.
     * No-op below Android 10, which has no edge back gesture. Posted so the view is measured first.
     */
    private fun excludeToolbarFromBackGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val root = provideToolbarDrawing().root
        root.post {
            val w = root.width
            val h = root.height
            if (w > 0 && h > 0) {
                root.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
            }
        }
    }

    /**
     * Resize every toolbar tool button to a square of [sizePx]. Some buttons declare a 0dp
     * (constraint-driven) height in XML; forcing both dimensions makes the column uniform so
     * the stack height is predictable when we scale to fit a single column.
     */
    private fun resizeToolbarButtons(buttons: List<View>, sizePx: Int) {
        for (v in buttons) {
            val lp = v.layoutParams ?: continue
            lp.width = sizePx
            lp.height = sizePx
            v.layoutParams = lp
        }
    }

    /**
     * Lay the given (visible) buttons out as an even, column-major grid: fill the first column
     * top-to-bottom, then the next, and so on. Every button becomes a uniform [sizePx] square and
     * columns tile left-to-right from the start edge. This overrides the XML constraint chains
     * while the toolbar is expanded in a multi-column (short / landscape) configuration, so the
     * columns stay uniform and no column overflows the screen height. Fresh XML is restored on the
     * activity recreation that follows a rotation back to a single-column (portrait) layout.
     */
    private fun applyToolbarGridLayout(buttons: List<View>, columns: Int, sizePx: Int) {
        val parentId = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val rows = Math.ceil(buttons.size.toDouble() / columns).toInt().coerceAtLeast(1)
        for ((i, view) in buttons.withIndex()) {
            val lp = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: continue
            val col = i / rows
            val row = i % rows
            lp.width = sizePx
            lp.height = sizePx
            // Horizontal: place each column by its index from the start edge.
            lp.startToStart = parentId
            lp.startToEnd = unset
            lp.endToEnd = unset
            lp.endToStart = unset
            lp.marginStart = col * sizePx
            // Vertical: top of a column anchors to the parent top; the rest chain under the
            // previous button in the same column (the immediately-preceding visible button).
            lp.bottomToBottom = unset
            lp.bottomToTop = unset
            if (row == 0) {
                lp.topToTop = parentId
                lp.topToBottom = unset
            } else {
                lp.topToTop = unset
                lp.topToBottom = buttons[i - 1].id
            }
            lp.topMargin = 0
            view.layoutParams = lp
        }
    }

    /**
     * Reposition the toolbar buttons into one or two vertical columns based on
     * available height. In single-column mode buttons stay as the XML defines them
     * (start+end anchored, vertically chained). In two-column mode the right-column
     * buttons are re-anchored to flow top-down from the toolbar top.
     */
    private fun applyToolbarTwoColumnLayout(twoColumns: Boolean) {
        if (!twoColumns) return  // rely on fresh XML on activity recreation (rotation)

        val toolbar = provideToolbarDrawing()
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val parentId = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        // Right column flows top-down from parent top.
        val rightChain = listOf(
            toolbar.toolbarCalendarView.id,
            toolbar.toolbarSwipeUp.id,
            toolbar.toolbarSwipeDown.id,
            toolbar.toolbarSwitchSide.id,
            toolbar.toolbarRotate.id,
            toolbar.toolbarCloudSync.id,
            toolbar.toolbarSettings.id,
        )
        for ((idx, id) in rightChain.withIndex()) {
            val view = toolbar.root.findViewById<View>(id) ?: continue
            val lp = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: continue
            lp.startToStart = unset
            lp.endToEnd = parentId
            lp.topToTop = if (idx == 0) parentId else unset
            lp.topToBottom = if (idx == 0) unset else rightChain[idx - 1]
            lp.bottomToBottom = unset
            lp.bottomToTop = unset
            lp.topMargin = 0
            view.layoutParams = lp
        }
    }

    /**
     * Exit lasso selection / paste mode and reset all selection state.
     */
    /**
     * The post-lasso selection / copy-paste menu (hasSelection / pasteMode) and the lasso-draw
     * state keep the Onyx raw-ink session live, so a stylus tap on a chip/menu paints a stray
     * hardware dot even though the app consumes the tap. Pause the raw session while any of those
     * menu states is active (exactly as the pen modal does) and restore it on exit — tracked so
     * we never redundantly cold-start the native pen (each false→true costs ~1s). MotionEvents
     * still reach the app with raw drawing off (the intake-page path), so chips/move/paste-place
     * all keep working; legit ink resumes cleanly once the selection is dismissed.
     */
    private var rawInkPausedForMenu = false
    private val resumeRawInkRunnable = Runnable { resumeRawInkNow() }
    private fun syncRawInkToSelectionMenu() {
        val menuActive = hasSelection || pasteMode || selectionMode
        if (menuActive && !rawInkPausedForMenu) {
            provideSurfaceView().removeCallbacks(resumeRawInkRunnable)   // cancel any pending resume
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.isRawDrawingRenderEnabled = false
            rawInkPausedForMenu = true
        } else if (!menuActive && rawInkPausedForMenu) {
            resumeRawInkNow()
        }
    }

    /** Re-enable the hardware pen if it's paused and no menu is active. Idempotent. */
    private fun resumeRawInkNow() {
        if (!rawInkPausedForMenu) return
        if (hasSelection || pasteMode || selectionMode) return
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = true
        rawInkPausedForMenu = false
    }

    /**
     * Resume the hardware pen AFTER the current tap has fully lifted. Used by CUT: cut deletes
     * the selection and exits it within a single stylus tap, so resuming synchronously re-enables
     * raw ink while that tap is still down — its ACTION_UP then paints a stray hardware dot on the
     * Cut chip (copy/paste/trash don't resume mid-tap, so they never had this). Deferring past the
     * release avoids the dot; the delayed runnable always fires, so the pen never stays dead.
     */
    private fun scheduleRawInkResume() {
        provideSurfaceView().removeCallbacks(resumeRawInkRunnable)
        provideSurfaceView().postDelayed(resumeRawInkRunnable, 250L)
    }

    private fun exitSelectionMode(deferRawResume: Boolean = false) {
        selectionMode = false
        hasSelection = false
        pasteMode = false
        textMode = false
        selectionPoints.clear()
        selectedStrokes.clear()
        selBox = null
        selectionDrag = SelectionDrag.NONE
        scaleOrigBox = null
        provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
        exitImageMode()
        // Restore the hardware pen. CUT defers it past the tap release so the Cut chip's own
        // ACTION_UP can't land on a re-enabled raw session and paint a dot; all other callers
        // (toolbar buttons, off-surface) resume immediately.
        if (deferRawResume) scheduleRawInkResume() else syncRawInkToSelectionMenu()
    }

    // --- Zoom / pan infrastructure ---

    fun isZoomed(): Boolean = zoomScale > 1.01f

    fun resetZoom() {
        zoomScale = 1.0f
        panX = 0.0f
        panY = 0.0f
        updateTransformMatrix()
        applyStrokes(strokes, true)
    }

    private fun updateTransformMatrix() {
        val sw = surfaceSize.width().toFloat()
        val sh = surfaceSize.height().toFloat()
        if (sw <= 0f || sh <= 0f) return

        baseScale = minOf(sw / CANVAS_WIDTH.toFloat(), sh / CANVAS_HEIGHT.toFloat())
        val totalScale = baseScale * zoomScale

        val scaledWidth = CANVAS_WIDTH * totalScale
        val scaledHeight = CANVAS_HEIGHT * totalScale
        val baseOffX = (sw - scaledWidth) / 2f
        val baseOffY = (sh - scaledHeight) / 2f

        if (scaledWidth > sw) {
            val maxPan = (scaledWidth - sw) / 2f
            panX = panX.coerceIn(-maxPan, maxPan)
        } else {
            panX = 0f
        }
        if (scaledHeight > sh) {
            val maxPan = (scaledHeight - sh) / 2f
            panY = panY.coerceIn(-maxPan, maxPan)
        } else {
            panY = 0f
        }

        viewMatrix.reset()
        viewMatrix.postScale(totalScale, totalScale)
        viewMatrix.postTranslate(baseOffX + panX, baseOffY + panY)
        viewMatrix.invert(inverseViewMatrix)

        touchHelper?.setStrokeWidth(effectivePenWidth() * totalScale)

        onTransformChanged(viewMatrix)
    }

    open fun onTransformChanged(matrix: Matrix) {}

    fun handleZoomPanTouch(motionEvent: MotionEvent): Boolean {
        if (motionEvent.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) twoFingerGesture = false
        if (motionEvent.pointerCount >= 2 || singleFingerGesturesEnabled) twoFingerGesture = true

        if (scaleGestureDetector == null) {
            scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldZoom = zoomScale
                    zoomScale = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    if (zoomScale != oldZoom) {
                        val focusX = detector.focusX
                        val focusY = detector.focusY

                        val pts = floatArrayOf(focusX, focusY)
                        inverseViewMatrix.mapPoints(pts)
                        val canvasX = pts[0]
                        val canvasY = pts[1]

                        val sw = surfaceSize.width().toFloat()
                        val sh = surfaceSize.height().toFloat()
                        val totalScale = baseScale * zoomScale
                        val scaledW = CANVAS_WIDTH * totalScale
                        val scaledH = CANVAS_HEIGHT * totalScale
                        val baseOffX = (sw - scaledW) / 2f
                        val baseOffY = (sh - scaledH) / 2f

                        val newScreenX = canvasX * totalScale + baseOffX + panX
                        val newScreenY = canvasY * totalScale + baseOffY + panY
                        panX += focusX - newScreenX
                        panY += focusY - newScreenY

                        updateTransformMatrix()
                        applyStrokes(strokes, true)
                    }
                    return true
                }
            })
        }

        if (doubleTapDetector == null) {
            doubleTapDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isZoomed()) {
                        resetZoom()
                    } else {
                        val pts = floatArrayOf(e.x, e.y)
                        inverseViewMatrix.mapPoints(pts)

                        zoomScale = 2.0f

                        val sw = surfaceSize.width().toFloat()
                        val sh = surfaceSize.height().toFloat()
                        val totalScale = baseScale * zoomScale
                        val scaledW = CANVAS_WIDTH * totalScale
                        val scaledH = CANVAS_HEIGHT * totalScale
                        val baseOffX = (sw - scaledW) / 2f
                        val baseOffY = (sh - scaledH) / 2f

                        val newScreenX = pts[0] * totalScale + baseOffX
                        val newScreenY = pts[1] * totalScale + baseOffY
                        panX = e.x - newScreenX
                        panY = e.y - newScreenY

                        updateTransformMatrix()
                        applyStrokes(strokes, true)
                    }
                    return true
                }
            })
        }

        scaleGestureDetector!!.onTouchEvent(motionEvent)
        doubleTapDetector!!.onTouchEvent(motionEvent)

        // Pan handling. By default we require two fingers (palm rejection). The user
        // can toggle the top toolbar button to allow single-finger panning while zoomed.
        // We only consume single-finger events for pan when actually zoomed in — at
        // zoom=1 single-finger events fall through to swipe-navigation.
        val allowOneFingerPan = singleFingerGesturesEnabled && isZoomed()
        if (motionEvent.pointerCount >= 2 || (allowOneFingerPan && motionEvent.pointerCount == 1)) {
            val cx: Float
            val cy: Float
            if (motionEvent.pointerCount >= 2) {
                cx = (motionEvent.getX(0) + motionEvent.getX(1)) / 2f
                cy = (motionEvent.getY(0) + motionEvent.getY(1)) / 2f
            } else {
                cx = motionEvent.x
                cy = motionEvent.y
            }
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                    lastFingerX = cx
                    lastFingerY = cy
                    isPanning = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanning && isZoomed()) {
                        panX += cx - lastFingerX
                        panY += cy - lastFingerY
                        updateTransformMatrix()
                        applyStrokes(strokes, true)
                    }
                    lastFingerX = cx
                    lastFingerY = cy
                }
            }
            return true
        }

        isPanning = false
        if (isZoomed()) return true

        return false
    }

    protected fun screenToCanvas(screenX: Float, screenY: Float): FloatArray {
        val pts = floatArrayOf(screenX, screenY)
        inverseViewMatrix.mapPoints(pts)
        return pts
    }

    // --- Selection-overlay geometry (canvas-space) ---

    private val handleSize = 36f
    private val handleHitPad = 30f
    private val chipSize = 88f
    private val chipGap = 16f

    private fun computeSelBox(s: List<Stroke>): RectF? {
        if (s.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (st in s) for (p in st.strokePoints) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    /** Y position of the chip row — above the box if there's room, otherwise below. */
    private fun chipBaseY(box: RectF): Float {
        return if (box.top > chipSize + 30f) box.top - chipSize - 20f else box.bottom + 20f
    }

    private fun cutChipRect(box: RectF): RectF {
        val top = chipBaseY(box)
        val right = box.right
        return RectF(right - chipSize, top, right, top + chipSize)
    }

    private fun copyChipRect(box: RectF): RectF {
        val top = chipBaseY(box)
        val right = box.right - chipSize - chipGap
        return RectF(right - chipSize, top, right, top + chipSize)
    }

    /** Returns which handle (if any) the canvas-space point hits. */
    private fun hitTestHandle(x: Float, y: Float, box: RectF): SelectionDrag {
        val pad = handleSize / 2f + handleHitPad
        fun near(hx: Float, hy: Float) = abs(x - hx) <= pad && abs(y - hy) <= pad
        return when {
            near(box.left, box.top) -> SelectionDrag.HANDLE_TL
            near(box.right, box.top) -> SelectionDrag.HANDLE_TR
            near(box.left, box.bottom) -> SelectionDrag.HANDLE_BL
            near(box.right, box.bottom) -> SelectionDrag.HANDLE_BR
            else -> SelectionDrag.NONE
        }
    }

    private fun anchorForHandle(handle: SelectionDrag, box: RectF): Pair<Float, Float> = when (handle) {
        SelectionDrag.HANDLE_TL -> box.right to box.bottom
        SelectionDrag.HANDLE_TR -> box.left to box.bottom
        SelectionDrag.HANDLE_BL -> box.right to box.top
        SelectionDrag.HANDLE_BR -> box.left to box.top
        SelectionDrag.NONE, SelectionDrag.MOVE -> 0f to 0f
    }

    /**
     * Redraw the current strokes with selected strokes highlighted (thicker stroke).
     * Also draws the lasso polygon if points exist, plus the selection bounding box,
     * corner handles, and Cut/Copy chips once a selection has been committed.
     */
    private fun drawWithSelection() {
        val lockCanvas = provideSurfaceView().holder.lockCanvas() ?: return

        lockCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val selectedIds = selectedStrokes.map { it.strokeId }.toSet()

        lockCanvas.save()
        lockCanvas.concat(viewMatrix)

        renderImageElements(lockCanvas)
        for (stroke in strokes) {
            val usePaint = if (stroke.strokeId in selectedIds) selectionHighlightPaint else paint
            drawStrokePath(lockCanvas, usePaint, stroke)
        }

        // Draw the freehand lasso outline only while the lasso is still being drawn.
        // Once a selection is committed the bounding box represents it; the raw polygon
        // would otherwise linger at its original spot when the strokes are moved away.
        if (!hasSelection && selectionPoints.size > 1) {
            val lassoPath = Path()
            lassoPath.moveTo(selectionPoints[0].x, selectionPoints[0].y)
            for (i in 1 until selectionPoints.size) {
                lassoPath.lineTo(selectionPoints[i].x, selectionPoints[i].y)
            }
            lockCanvas.drawPath(lassoPath, lassoPaint)
        }

        // Selection overlay: bounding box, handles, and Cut/Copy chips
        val box = selBox
        if (hasSelection && box != null) {
            // Dashed bounding box
            lockCanvas.drawRect(box, lassoPaint)

            // Corner handles (filled squares with a border)
            val h = handleSize / 2f
            val handleFill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val handleStroke = Paint().apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
            }
            val corners = listOf(
                box.left to box.top, box.right to box.top,
                box.left to box.bottom, box.right to box.bottom,
            )
            for ((cx, cy) in corners) {
                lockCanvas.drawRect(cx - h, cy - h, cx + h, cy + h, handleFill)
                lockCanvas.drawRect(cx - h, cy - h, cx + h, cy + h, handleStroke)
            }

            // Chips
            val cutR = cutChipRect(box)
            val copyR = copyChipRect(box)
            val chipBg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val chipBorder = Paint().apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
            }
            for (r in listOf(cutR, copyR)) {
                lockCanvas.drawRoundRect(r, 12f, 12f, chipBg)
                lockCanvas.drawRoundRect(r, 12f, 12f, chipBorder)
            }
            // Draw icons inside chips
            val pad = 14
            val cutIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_toolbar_cut, null)
            cutIcon?.setBounds((cutR.left + pad).toInt(), (cutR.top + pad).toInt(),
                (cutR.right - pad).toInt(), (cutR.bottom - pad).toInt())
            cutIcon?.draw(lockCanvas)
            val copyIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_toolbar_copy, null)
            copyIcon?.setBounds((copyR.left + pad).toInt(), (copyR.top + pad).toInt(),
                (copyR.right - pad).toInt(), (copyR.bottom - pad).toInt())
            copyIcon?.draw(lockCanvas)
        }

        lockCanvas.restore()

        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false
        provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = true
    }

    /**
     * Set the text elements for the current page and redraw.
     *
     * @param elements the text elements to display
     */
    fun setTextElements(elements: MutableList<TextElement>) {
        this.textElements = elements
        // Keep an active selection pointing at the reloaded element so a drag
        // in progress isn't mutating an object the list no longer contains.
        val selId = selectedTextBox?.elementId
        selectedTextBox = if (selId != null) elements.firstOrNull { it.elementId == selId } else null
    }

    /** Resolve the Atkinson Hyperlegible face once (falls back to the system default). */
    private fun textTypeface(): Typeface = try {
        ResourcesCompat.getFont(requireContext(), R.font.atkinson_hyperlegible) ?: Typeface.DEFAULT
    } catch (e: Exception) {
        Typeface.DEFAULT
    }

    /**
     * Build a word-wrapping layout for a text element at its current box width.
     * The text reflows to [TextElement.width] — resizing the box reorganises the
     * words rather than rescaling the font. Long pasted paragraphs (no explicit
     * newlines) now wrap instead of running off the right edge.
     */
    private fun buildTextLayout(element: TextElement): StaticLayout {
        textPaint.textSize = element.fontSize
        textPaint.color = element.color
        textPaint.typeface = textTypeface()
        val width = element.width.coerceAtLeast(MIN_TEXTBOX_WIDTH).toInt()
        return StaticLayout.Builder
            .obtain(element.text, 0, element.text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    /**
     * Render all text elements onto the given canvas, word-wrapped to each box's width.
     *
     * @param targetCanvas the canvas to draw on
     */
    private fun renderTextElements(targetCanvas: Canvas) {
        for (element in textElements) {
            val layout = buildTextLayout(element)
            // Keep the stored height in sync with the wrapped layout so hit-testing,
            // the selection outline and persisted JSON all match what's drawn.
            element.height = layout.height.toFloat()
            targetCanvas.save()
            targetCanvas.translate(element.x, element.y)
            layout.draw(targetCanvas)
            targetCanvas.restore()
        }
    }

    // ─── Image elements: paste/insert, render, move/resize/delete ───────────────
    // Images live on the v2 CalendarDay alongside text/strokes; bytes are inline base64
    // (downscaled) so they ride the existing JSON save + Drive sync untouched.

    fun setImageElements(elements: MutableList<ImageElement>) {
        this.imageElements = elements
        imageBitmapCache.clear()
        // Keep an active selection pointing at the reloaded element so it survives page reloads.
        val selId = selectedImage?.elementId
        selectedImage = if (selId != null) elements.firstOrNull { it.elementId == selId } else null
    }

    /** Redraw the selected-image overlay if one is active (call after a page reload repaints). */
    fun redrawImageSelectionIfActive() {
        if (imageMode && selectedImage != null) drawImageSelection()
    }

    /** Override to persist image changes (the day fragment writes them back to CalendarDay). */
    open fun onImageElementsChanged(imageElements: MutableList<ImageElement>) {}

    private fun bitmapForElement(element: ImageElement): Bitmap? {
        imageBitmapCache[element.elementId]?.let { return it }
        return try {
            val bytes = Base64.decode(element.data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { imageBitmapCache[element.elementId] = it }
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode image element")
            null
        }
    }

    /** Render all image elements (drawn under strokes/text so the user can write over them). */
    private fun renderImageElements(targetCanvas: Canvas) {
        if (imageElements.isEmpty()) return
        for (element in imageElements) {
            val bmp = bitmapForElement(element) ?: continue
            targetCanvas.drawBitmap(bmp, null, RectF(element.x, element.y, element.x + element.width, element.y + element.height), imagePaint)
        }
    }

    private fun exitImageMode() {
        imageMode = false
        selectedImage = null
        selectedTextBox = null
        textBoxDrag = false
        textBoxResize = false
        imageDrag = ImageDrag.NONE
        cropMode = false
        cropDragging = false
        cropRect = null
    }

    private fun snapshotCanvas() = CanvasSnapshot(Stroke.listDeepCopy(strokes), imageElements.map { it.copy() })

    /** Push the current strokes+images onto the undo stack (cap 50) and clear redo. */
    private fun pushUndo() {
        undoStack.add(snapshotCanvas())
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** Paste the unified clipboard's image as a new element, offset + selected, in image mode. */
    private fun pasteClipboardImage() {
        val img = strokeClipboard.image ?: return
        val stamped = strokeClipboard.stampImageAt(img.x + 40f, img.y + 40f) ?: return
        pushUndo()
        textMode = false
        pasteMode = false
        selectionMode = false
        hasSelection = false
        syncRawInkToSelectionMenu()   // selection cleared → restore hardware pen if it was paused
        procrastinator = false
        penState = false
        imageMode = true
        imageElements.add(stamped)
        onImageElementsChanged(imageElements)
        selectedImage = stamped
        applyStrokes(strokes, true)
        drawImageSelection()
    }

    /** Insert from the system clipboard if it holds an image, otherwise open the picker. */
    private fun insertImageFromClipboardOrPicker() {
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val uri = clip.getItemAt(0).uri
                if (uri != null) {
                    insertImageFromUri(uri)
                    return
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Clipboard image check failed")
        }
        launchImagePicker()
    }

    /**
     * Open an image picker robustly: try the gallery (ACTION_PICK) first — most stable on
     * Boox/AOSP — then fall back to the document picker (ACTION_GET_CONTENT). Both launches
     * are guarded so a missing/incompatible picker can never crash the app.
     */
    private fun launchImagePicker() {
        val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        try {
            imagePickLauncher.launch(pick)
            return
        } catch (e: Exception) {
            Timber.w(e, "ACTION_PICK gallery unavailable, falling back to ACTION_GET_CONTENT")
        }
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            imagePickLauncher.launch(get)
        } catch (e: Exception) {
            Timber.e(e, "No image picker available")
            Toast.makeText(requireContext(), "No image picker on this device — copy an image, then tap the image button", Toast.LENGTH_LONG).show()
            exitImageMode()
        }
    }

    /** Decode an image already downsampled near [IMAGE_MAX_DIM] so a large photo can't OOM. */
    private fun decodeDownsampledImage(uri: Uri): Bitmap? {
        val cr = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > IMAGE_MAX_DIM * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun insertImageFromUri(uri: Uri) {
        try {
            val original = decodeDownsampledImage(uri)
            if (original == null) {
                Timber.w("Image decode failed for %s", uri)
                Toast.makeText(requireContext(), "Couldn't read that image", Toast.LENGTH_SHORT).show()
                return
            }

            // Downscale the longest side so the inline base64 stays sync-friendly.
            val longest = maxOf(original.width, original.height)
            val scaled = if (longest > IMAGE_MAX_DIM) {
                val ratio = IMAGE_MAX_DIM.toFloat() / longest
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).toInt().coerceAtLeast(1),
                    (original.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else original

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // Place centered on the long-press point when one is pending, otherwise
            // centered on the page; sized to a fraction of the page width.
            val w = (CANVAS_WIDTH * IMAGE_PLACE_FRACTION).coerceAtMost(scaled.width.toFloat())
            val h = w * scaled.height / scaled.width
            val place = pendingPlacePoint
            pendingPlacePoint = null
            val px = ((place?.x ?: (CANVAS_WIDTH / 2f)) - w / 2f).coerceIn(0f, (CANVAS_WIDTH - w).coerceAtLeast(0f))
            val py = ((place?.y ?: (CANVAS_HEIGHT / 2f)) - h / 2f).coerceIn(0f, (CANVAS_HEIGHT - h).coerceAtLeast(0f))
            val element = ImageElement(
                x = px,
                y = py,
                width = w,
                height = h,
                data = base64
            )
            pushUndo()
            imageElements.add(element)
            onImageElementsChanged(imageElements)

            // Land in image mode with the new image selected for immediate move/resize.
            imageMode = true
            penState = false
            selectedImage = element
            applyStrokes(strokes, true)
            drawImageSelection()
        } catch (e: Exception) {
            Timber.e(e, "Insert image failed")
            Toast.makeText(requireContext(), "Couldn't insert that image", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Finger long-press: element creation menu + select-to-manage ────────────
    // "Pen writes, finger manages." A finger long-press on empty canvas opens a
    // small creation menu (text box / camera / upload / paste); on an existing
    // image or text box it selects it for management. Fragments detect the
    // long-press in their touch listener and call handleCanvasLongPress.

    /** True while the element-manipulation mode is active. */
    fun isImageModeActive(): Boolean = imageMode

    /** Topmost image element under a canvas point, or null. */
    private fun imageElementAt(cx: Float, cy: Float): ImageElement? =
        imageElements.lastOrNull { cx >= it.x && cx <= it.x + it.width && cy >= it.y && cy <= it.y + it.height }

    /** Canvas-space bounds of a text box (measured from its rendered lines). */
    protected fun textElementBounds(element: TextElement): RectF {
        // The box is as wide as element.width; height follows the wrapped layout.
        val layout = buildTextLayout(element)
        val boxWidth = element.width.coerceAtLeast(MIN_TEXTBOX_WIDTH)
        val boxHeight = layout.height.toFloat()
        val pad = 14f
        return RectF(
            element.x - pad,
            element.y - pad,
            element.x + boxWidth + pad,
            element.y + boxHeight + pad
        )
    }

    /** Bottom-right resize handle for a selected text box (mirrors the image handle). */
    private fun textResizeHandle(box: RectF): RectF {
        val h = IMAGE_HANDLE_SIZE
        return RectF(box.right - h / 2f, box.bottom - h / 2f, box.right + h / 2f, box.bottom + h / 2f)
    }

    /** Topmost text box under a canvas point, or null. */
    private fun textElementAt(cx: Float, cy: Float): TextElement? =
        textElements.lastOrNull { textElementBounds(it).contains(cx, cy) }

    /**
     * Entry point for a finger long-press at canvas coordinates: select the
     * element under the finger, or open the creation menu on empty canvas.
     *
     * @param cx canvas x
     * @param cy canvas y
     * @param pressX press x in the surface view's coordinates (menu anchor)
     * @param pressY press y in the surface view's coordinates (menu anchor)
     */
    fun handleCanvasLongPress(cx: Float, cy: Float, pressX: Float, pressY: Float) {
        val image = imageElementAt(cx, cy)
        if (image != null) {
            enterImageManipulation(image)
            return
        }
        val textBox = textElementAt(cx, cy)
        if (textBox != null) {
            showTextBoxMenu(textBox, pressX, pressY)
            return
        }
        showCanvasCreationMenu(cx, cy, pressX, pressY)
    }

    /** Select an image element and enter the manipulation mode (move/resize/chips). */
    private fun enterImageManipulation(element: ImageElement) {
        textMode = false
        pasteMode = false
        selectionMode = false
        hasSelection = false
        syncRawInkToSelectionMenu()   // selection cleared → restore hardware pen if it was paused
        procrastinator = false
        penState = false
        imageMode = true
        selectedImage = element
        selectedTextBox = null
        applyStrokes(strokes, true)
        drawImageSelection()
    }

    /** Select a text box and enter the manipulation mode (finger drag moves it, like an image). */
    private fun enterTextBoxManipulation(element: TextElement) {
        textMode = false
        pasteMode = false
        selectionMode = false
        hasSelection = false
        syncRawInkToSelectionMenu()   // selection cleared → restore hardware pen if it was paused
        procrastinator = false
        penState = false
        imageMode = true
        selectedImage = null
        selectedTextBox = element
        applyStrokes(strokes, true)
        drawImageSelection()
    }

    /** The creation menu shown on a long-press over empty canvas. */
    private fun showCanvasCreationMenu(cx: Float, cy: Float, pressX: Float, pressY: Float) {
        if (context == null) return
        LedgerContextMenu.show(
            provideSurfaceView(), pressX, pressY, "ADD HERE",
            listOf(
                listOf(
                    LedgerContextMenu.Item("Text box") { showTextInputDialog(cx, cy) },
                    LedgerContextMenu.Item("Image — camera") {
                        pendingPlacePoint = PointF(cx, cy)
                        launchCameraCapture()
                    },
                    LedgerContextMenu.Item("Image — upload") {
                        pendingPlacePoint = PointF(cx, cy)
                        launchImagePicker()
                    }
                ),
                listOf(
                    LedgerContextMenu.Item("Paste") { pasteUnifiedAt(cx, cy) }
                )
            )
        )
    }

    /** Long-press on a text box: management menu (edit / move / clipboard ops / delete). */
    private fun showTextBoxMenu(element: TextElement, pressX: Float, pressY: Float) {
        val ctx = context ?: return
        LedgerContextMenu.show(
            provideSurfaceView(), pressX, pressY, "TEXT BOX",
            listOf(
                listOf(
                    LedgerContextMenu.Item("Edit text") { showTextEditDialog(element) },
                    LedgerContextMenu.Item("Move — drag it") { enterTextBoxManipulation(element) }
                ),
                listOf(
                    LedgerContextMenu.Item("Duplicate") {
                        val copy = element.copy(
                            elementId = UUID.randomUUID(),
                            timestamp = System.currentTimeMillis(),
                            x = element.x + 40f, y = element.y + 40f
                        )
                        textElements.add(copy)
                        onTextElementsChanged(textElements)
                        applyStrokes(strokes, true)
                    },
                    LedgerContextMenu.Item("Cut") {
                        strokeClipboard.copyTextBox(element)
                        textElements.remove(element)
                        onTextElementsChanged(textElements)
                        applyStrokes(strokes, true)
                    },
                    LedgerContextMenu.Item("Copy") { strokeClipboard.copyTextBox(element) }
                ),
                listOf(
                    LedgerContextMenu.Item("Delete") {
                        textElements.remove(element)
                        onTextElementsChanged(textElements)
                        applyStrokes(strokes, true)
                    }
                )
            )
        )
    }

    /**
     * Paste the unified clipboard at a canvas point: internal image or text box
     * first, then the system clipboard (image URI → image element, text → text box).
     */
    private fun pasteUnifiedAt(cx: Float, cy: Float) {
        // Internal clipboard: image element.
        strokeClipboard.stampImageAt(cx, cy)?.let { stamped ->
            pushUndo()
            imageElements.add(stamped)
            onImageElementsChanged(imageElements)
            enterImageManipulation(stamped)
            return
        }
        // Internal clipboard: text box.
        strokeClipboard.stampTextBoxAt(cx, cy)?.let { stamped ->
            textElements.add(stamped)
            onTextElementsChanged(textElements)
            applyStrokes(strokes, true)
            return
        }
        // System clipboard: image URI or plain text.
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val uri = item.uri
                if (uri != null) {
                    pendingPlacePoint = PointF(cx, cy)
                    insertImageFromUri(uri)
                    return
                }
                val text = item.coerceToText(requireContext())?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    val element = TextElement(x = cx, y = cy, text = text)
                    textElements.add(element)
                    onTextElementsChanged(textElements)
                    applyStrokes(strokes, true)
                    return
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Paste from system clipboard failed")
        }
        Toast.makeText(requireContext(), "Nothing to paste", Toast.LENGTH_SHORT).show()
    }

    /** Edit an existing text box's content; emptying the text deletes the box. */
    private fun showTextEditDialog(element: TextElement) {
        val ctx = context ?: return
        val editText = EditText(ctx)
        editText.setSingleLine(false)
        editText.setLines(3)
        editText.setText(element.text)
        editText.setSelection(element.text.length)

        val container = FrameLayout(ctx)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, 0, margin, 0)
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.calendar_text_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isEmpty()) {
                    textElements.remove(element)
                } else {
                    element.text = newText
                }
                onTextElementsChanged(textElements)
                applyStrokes(strokes, true)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create().show()
        editText.requestFocus()
    }

    /** Launch a standard camera capture into a FileProvider cache file. */
    private fun launchCameraCapture() {
        try {
            val dir = File(requireContext().cacheDir, "camera").apply { mkdirs() }
            val photo = File(dir, "capture-${Instant.now().epochSecond}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", photo
            )
            pendingCameraUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            cameraCaptureLauncher.launch(intent)
        } catch (e: Exception) {
            pendingCameraUri = null
            Timber.w(e, "Camera capture unavailable")
            Toast.makeText(requireContext(), "No camera available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun imageResizeHandle(box: RectF): RectF {
        val h = IMAGE_HANDLE_SIZE
        return RectF(box.right - h / 2f, box.bottom - h / 2f, box.right + h / 2f, box.bottom + h / 2f)
    }

    /** The ✂ Cut / ⛶ Crop / ✕ Del chips along the top edge of a selected image (right-anchored, inside).
     *  Copy / Duplicate / Paste are handled by the toolbar; these are the image-specific actions. */
    private fun imageChipRects(box: RectF): List<Pair<String, RectF>> {
        val s = IMAGE_CHIP_SIZE
        val pad = 6f
        val top = box.top + pad
        val rects = mutableListOf<Pair<String, RectF>>()
        var right = box.right - pad
        for (label in listOf("Del", "Crop", "Cut")) {
            rects.add(label to RectF(right - s, top, right, top + s))
            right -= (s + pad)
        }
        return rects
    }

    /** Crop the selected image's bitmap to the given canvas-space rect, re-encode, and reposition. */
    private fun applyCropToImage(element: ImageElement, cropCanvas: RectF) {
        val bmp = bitmapForElement(element) ?: return
        val c = RectF(cropCanvas)
        if (!c.intersect(RectF(element.x, element.y, element.x + element.width, element.y + element.height))) return
        val sx = bmp.width / element.width
        val sy = bmp.height / element.height
        val bx = ((c.left - element.x) * sx).toInt().coerceIn(0, bmp.width - 1)
        val by = ((c.top - element.y) * sy).toInt().coerceIn(0, bmp.height - 1)
        val bw = (c.width() * sx).toInt().coerceIn(1, bmp.width - bx)
        val bh = (c.height() * sy).toInt().coerceIn(1, bmp.height - by)
        val cropped = Bitmap.createBitmap(bmp, bx, by, bw, bh)
        val baos = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
        element.data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        element.x = c.left
        element.y = c.top
        element.width = c.width()
        element.height = c.height()
        imageBitmapCache[element.elementId] = cropped
    }

    /** Repaint the page plus the selected image's bounding box, resize handle and delete chip. */
    private fun drawImageSelection() {
        val lockCanvas = provideSurfaceView().holder.lockCanvas() ?: return
        lockCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        lockCanvas.save()
        lockCanvas.concat(viewMatrix)
        renderImageElements(lockCanvas)
        val strokePaint = Paint(paint)
        for (stroke in strokes) drawStrokePath(lockCanvas, strokePaint, stroke)
        renderTextElements(lockCanvas)
        val sel = selectedImage
        if (sel != null) {
            val box = RectF(sel.x, sel.y, sel.x + sel.width, sel.y + sel.height)
            lockCanvas.drawRect(box, lassoPaint)
            val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
            val rh = imageResizeHandle(box)
            lockCanvas.drawRect(rh, fill)
            lockCanvas.drawRect(rh, border)
            for ((label, r) in imageChipRects(box)) {
                lockCanvas.drawRoundRect(r, 8f, 8f, fill)
                lockCanvas.drawRoundRect(r, 8f, 8f, border)
                val pad = 14f
                val il = (r.left + pad).toInt(); val it = (r.top + pad).toInt()
                val ir = (r.right - pad).toInt(); val ib = (r.bottom - pad).toInt()
                when (label) {
                    "Cut" -> ResourcesCompat.getDrawable(resources, R.drawable.ic_toolbar_cut, null)?.apply { setBounds(il, it, ir, ib); draw(lockCanvas) }
                    "Crop" -> ResourcesCompat.getDrawable(resources, R.drawable.ic_toolbar_crop, null)?.apply { setBounds(il, it, ir, ib); draw(lockCanvas) }
                    "Del" -> {
                        lockCanvas.drawLine(il.toFloat(), it.toFloat(), ir.toFloat(), ib.toFloat(), border)
                        lockCanvas.drawLine(ir.toFloat(), it.toFloat(), il.toFloat(), ib.toFloat(), border)
                    }
                }
            }
            // Crop rectangle overlay while cropping.
            val cr = cropRect
            if (cropMode && cr != null) {
                val cropPaint = Paint().apply {
                    color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f
                    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f); isAntiAlias = true
                }
                lockCanvas.drawRect(cr, cropPaint)
            }
        }
        // Selected text box: dashed outline + a bottom-right resize handle. Dragging the
        // body moves it; dragging the handle reflows the words to a new width. Edit /
        // duplicate / delete stay on the long-press menu.
        selectedTextBox?.let {
            val box = textElementBounds(it)
            lockCanvas.drawRect(box, lassoPaint)
            val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
            val rh = textResizeHandle(box)
            lockCanvas.drawRect(rh, fill)
            lockCanvas.drawRect(rh, border)
        }
        lockCanvas.restore()

        // Pause the Onyx raw-drawing renderer around the post, exactly like drawWithSelection —
        // posting to the surface while the pen renderer owns it crashes the native layer.
        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false
        provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = true
    }

    /**
     * Show a text input dialog at the given canvas coordinates.
     *
     * @param x the x coordinate on the surface
     * @param y the y coordinate on the surface
     */
    private fun showPenSettingsDialog() {
        // Last entry is the "highlighter" / transparent marker: a translucent yellow,
        // drawn with normal alpha blending like any other stroke.
        val colorValues = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.rgb(0, 128, 0), Color.argb(90, 255, 213, 0))
        val widthValues = floatArrayOf(1.0f, 3.0f, 5.0f, 8.0f)

        var selColor = colorValues.indexOfFirst { it == paint.color }.coerceAtLeast(0)
        var selWidth = widthValues.indexOfFirst { it == paint.strokeWidth }.coerceAtLeast(1)

        val dp = resources.displayMetrics.density
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }

        fun ringDrawable(selected: Boolean, shape: Int) = GradientDrawable().apply {
            this.shape = shape
            setColor(Color.TRANSPARENT)
            if (shape == GradientDrawable.RECTANGLE) cornerRadius = 8f * dp
            if (selected) setStroke((2 * dp).toInt(), Color.DKGRAY)
        }

        val itemSize = (44 * dp).toInt()
        val itemMargin = (4 * dp).toInt()

        // Two-letter labels so the swatches stay distinguishable on grayscale e-ink.
        val colorLabels = arrayOf("Bk", "Rd", "Bl", "Gn", "Hl")
        // Picks white or black text based on the swatch's perceived luminance.
        fun labelColorFor(color: Int): Int {
            val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
            return if (lum < 140) Color.WHITE else Color.BLACK
        }

        // Color row: circular swatches showing the actual pen color.
        val colorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val colorItems = colorValues.indices.map { i ->
            val swatch = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorValues[i])
                    if (Color.alpha(colorValues[i]) < 255) setStroke((1 * dp).toInt(), Color.LTGRAY)
                }
            }
            val label = TextView(ctx).apply {
                text = colorLabels.getOrElse(i) { "" }
                setTextColor(labelColorFor(colorValues[i]))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(itemSize, itemSize).apply {
                    setMargins(itemMargin, itemMargin, itemMargin, itemMargin)
                }
                background = ringDrawable(i == selColor, GradientDrawable.OVAL)
                val swatchSize = (itemSize * 0.65f).toInt()
                addView(swatch, FrameLayout.LayoutParams(swatchSize, swatchSize).apply { gravity = Gravity.CENTER })
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
                isClickable = true
            }.also { colorRow.addView(it) }
        }
        // Selections apply LIVE (no OK step) and activate on ACTION_DOWN: on e-ink
        // the click round-trip (press-state redraw + UP) reads as lag, and MJH asked
        // for no confirmation step. applyLiveSelection is assigned after the rows
        // are built; the reference lets the row handlers call it.
        var applyLiveSelection: () -> Unit = {}
        // Cheap per-tap subset (app-side pen state + toolbar tint) — no Onyx hardware calls.
        var applyLivePrefs: () -> Unit = {}

        colorItems.forEachIndexed { i, item ->
            item.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    selColor = i
                    colorItems.forEachIndexed { j, c -> c.background = ringDrawable(j == selColor, GradientDrawable.OVAL) }
                    applyLivePrefs()
                    v.performClick()
                }
                true
            }
        }
        root.addView(colorRow)

        // Width row: horizontal bars whose thickness represents the stroke width.
        val widthItemWidth = (60 * dp).toInt()
        val widthRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val widthItems = widthValues.indices.map { i ->
            val barHeight = (widthValues[i] * 2.5f * dp).toInt().coerceAtLeast((2 * dp).toInt())
            val bar = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.DKGRAY)
                    cornerRadius = barHeight / 2f
                }
            }
            FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(widthItemWidth, itemSize).apply {
                    setMargins(itemMargin, itemMargin, itemMargin, itemMargin)
                }
                background = ringDrawable(i == selWidth, GradientDrawable.RECTANGLE)
                val barWidth = (widthItemWidth * 0.7f).toInt()
                addView(bar, FrameLayout.LayoutParams(barWidth, barHeight).apply { gravity = Gravity.CENTER })
                isClickable = true
            }.also { widthRow.addView(it) }
        }
        widthItems.forEachIndexed { i, item ->
            item.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    selWidth = i
                    widthItems.forEachIndexed { j, c -> c.background = ringDrawable(j == selWidth, GradientDrawable.RECTANGLE) }
                    applyLivePrefs()
                    v.performClick()
                }
                true
            }
        }
        root.addView(widthRow)

        // Style row: normal pen vs. calligraphy (pressure-variable fountain nib).
        var selCalligraphy = calligraphyMode
        val styleLabels = arrayOf("Pen", "✒ Calligraphy")
        val styleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val styleItems = styleLabels.indices.map { i ->
            val label = TextView(ctx).apply {
                text = styleLabels[i]
                setTextColor(Color.BLACK)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, itemSize).apply {
                    setMargins(itemMargin, itemMargin, itemMargin, itemMargin)
                }
                background = ringDrawable((i == 1) == selCalligraphy, GradientDrawable.RECTANGLE)
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER })
                isClickable = true
            }.also { styleRow.addView(it) }
        }
        styleItems.forEachIndexed { i, item ->
            item.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    selCalligraphy = (i == 1)
                    styleItems.forEachIndexed { j, c -> c.background = ringDrawable((j == 1) == selCalligraphy, GradientDrawable.RECTANGLE) }
                    applyLivePrefs()
                    v.performClick()
                }
                true
            }
        }
        root.addView(styleRow)

        // Live-apply: every tapped option takes effect immediately (pen, hardware
        // preview, prefs, toolbar tint). Exactly what the old OK button did.
        // Per-tap (cheap): app-side pen state, pref, and the toolbar tint for instant visual
        // feedback. Deliberately NO touchHelper.setStroke* here — the raw session is paused
        // while the modal is up, so those hardware reconfigures (setStrokeStyle cold-starts the
        // native pen, ~1s on-device) have no visible effect yet froze the UI on every tap.
        applyLivePrefs = {
            paint.color = colorValues[selColor]
            paint.strokeWidth = widthValues[selWidth]
            calligraphyMode = selCalligraphy
            sharedPreferences.edit().putBoolean("calligraphyMode", calligraphyMode).apply()
            val opaqueColor = Color.rgb(Color.red(paint.color), Color.green(paint.color), Color.blue(paint.color))
            provideToolbarDrawing().toolbarPen.background.setTint(
                if (opaqueColor == Color.BLACK) Color.GRAY else opaqueColor
            )
        }
        // Full apply (incl. the heavy Onyx hardware calls) — run ONCE on dismiss, right before
        // the raw session is restored, so the hardware pen picks up the final selection.
        applyLiveSelection = {
            applyLivePrefs()
            touchHelper?.setStrokeWidth(effectivePenWidth() * baseScale * zoomScale)
            touchHelper?.setStrokeColor(paint.color)
            applyStrokeStyle()
        }

        // Pause the raw-ink session while the modal is up. With the session live,
        // the Onyx EPD layer keeps priority on the EMR stylus for the surface
        // below, which made stylus taps on the dialog feel sluggish. Paused, the
        // stylus dispatches to the dialog window like any other pointer. The
        // dismiss listener re-applies the final selection (persisting it exactly
        // as OK used to) and restores the hardware pen with the NEW settings.
        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            applyLiveSelection()
            touchHelper?.setRawDrawingEnabled(true)
            touchHelper?.isRawDrawingRenderEnabled = true
        }
        dialog.show()
    }

    private fun showTextInputDialog(x: Float, y: Float) {
        val context = this.requireContext()

        val editText = EditText(context)
        editText.hint = getString(R.string.calendar_text_dialog_hint)
        editText.setSingleLine(false)
        editText.setLines(3)

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, 0, margin, 0)
        editText.layoutParams = params
        container.addView(editText)

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.calendar_text_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    val element = TextElement(
                        x = x,
                        y = y,
                        text = text
                    )
                    textElements.add(element)
                    applyStrokes(strokes, true)
                    onTextElementsChanged(textElements)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }

        builder.create().show()
        editText.requestFocus()
    }

    fun exportBitmap() {
        if (!checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            showError(null, R.string.main_read_external_storage_permission_missing, provideSurfaceView())
            return
        }

        if (!checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            showError(null, R.string.main_write_external_storage_permission_missing, provideSurfaceView())
            return
        }

        val title = "export-${Instant.now().epochSecond}"
        MediaStore.Images.Media.insertImage(
            this@SurfaceFragment.requireActivity().contentResolver,
            bitmap,
            title,
            title
        )
        showMessage(getString(R.string.surface_page_export_message).format(title), provideSurfaceView())
    }

    /**
     * When true, the Onyx raw-ink session is not created for this screen and
     * the fragment uses the plain MotionEvent capture + software rendering
     * path instead (the same fallback the Viwoods flavor uses). Needed for
     * pages with tap-to-interact zones: the Onyx raw input reader grabs ALL
     * touch (pen and finger) over the surface at the system level, so finger
     * taps never reach the app while a raw session is open.
     */
    open fun provideDisableRawInkCapture(): Boolean = false

    /**
     * Initialize the surface view of drawing.
     *
     * @param first first initialization flag
     */
    fun initializeSurface(first: Boolean = false) {
        if (first) {
            if (com.toolsboox.ot.ViwoodsFastInk.isAvailable) {
                // Viwoods AiPaper: the Onyx TouchHelper.create() succeeds here but is inert
                // (no Onyx hardware) AND swallows the pen, which is why strokes only showed
                // on lift. Skip it entirely; we set the FAST e-ink waveform and render the
                // pen ourselves. Touch events reach us via the SurfaceView's onTouchListener.
                touchHelper = null
                try {
                    viwoodsInk = com.toolsboox.ot.ViwoodsFastInk().also { it.attach(requireContext()) }
                    Timber.i("Viwoods fast-ink backend active on ${Build.MODEL}")
                } catch (t: Throwable) {
                    Timber.w(t, "Viwoods attach() failed")
                    viwoodsInk = null
                }
            } else {
                // Try to create the Onyx TouchHelper on any Boox device. The SDK's
                // hasStylus() heuristic can return false on some models (e.g. Palma 2 Pro)
                // even when raw stylus drawing is supported, which forces the slow
                // MotionEvent fallback rendering path. Catch and fall back only on real
                // failure (non-Onyx device or SDK incompatibility).
                if (provideDisableRawInkCapture()) {
                    // This screen needs normal Android touch over the surface (finger
                    // taps). The Onyx raw session would grab all of it at the system
                    // level, so skip it and use MotionEvent capture + software render.
                    Timber.i("Raw ink capture disabled for this screen; MotionEvent rendering path")
                    touchHelper = null
                } else try {
                    touchHelper = TouchHelper.create(provideSurfaceView(), callback)
                    Timber.i("TouchHelper created successfully on ${Build.MODEL}")
                } catch (e: Throwable) {
                    Timber.w(e, "TouchHelper creation failed on ${Build.MODEL}; falling back to MotionEvent rendering")
                    touchHelper = null
                }
            }
            provideSurfaceView().setZOrderOnTop(true)
            provideSurfaceView().holder.setFormat(PixelFormat.TRANSPARENT)
            // Keep the screen on while the planner is showing — prevents the Onyx
            // power manager from idling the device every few seconds (which causes
            // first-stroke-after-idle latency on Palma 2 Pro).
            provideSurfaceView().keepScreenOn = true
        }

        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = 3.0f

        if (surfaceCallback == null) {
            surfaceCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Timber.i("surfaceCreated")
                    val view = provideSurfaceView()
                    // Use the SurfaceView's full dimensions for the TouchHelper limit rect.
                    // getLocalVisibleRect() can return a clipped rect when system bars or
                    // window insets overlap the view, which caused pen events near the
                    // bottom of the screen to fall through to Android's slow touch path
                    // instead of being captured by Onyx's raw drawing.
                    val limit = Rect(0, 0, view.width, view.height)
                    bitmap = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
                    bitmap!!.eraseColor(Color.TRANSPARENT)
                    canvas = Canvas(bitmap!!)

                    if (provideSurfaceView().holder == null) {
                        return
                    }

                    clearSurface()

                    touchHelper?.setLimitRect(limit, ArrayList())?.setStrokeWidth(effectivePenWidth())?.openRawDrawing()
                    // Let FINGER touch pass through to normal Android dispatch while the
                    // raw session is open. Without this the Onyx raw input reader grabs
                    // finger input over the whole limit rect at the system level, so the
                    // app never sees finger taps/swipes/long-presses on ink pages.
                    touchHelper?.enableFingerTouch(true)
                    applyStrokeStyle()
                    touchHelper?.setStrokeColor(paint.color)
                    // Record the size we just opened the reader with, so the first
                    // surfaceChanged at the same size won't needlessly cold-start it again.
                    appliedLimitWidth = view.width
                    appliedLimitHeight = view.height
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Timber.i("surfaceChanged: ${width}x${height}")
                    surfaceSize = Rect(0, 0, width, height)
                    updateTransformMatrix()
                    // Activate Viwoods T1000 AutoDraw for this surface. The hardware then
                    // renders pen strokes live; we draw nothing during the stroke. Uses
                    // full-screen metrics (not just the surface) to register the region.
                    if (viwoodsInk != null && width > 0 && height > 0) {
                        try {
                            val dm = resources.displayMetrics
                            // The Viwoods T1000 hardware-ink overlay (initWriting) only covers
                            // ~the left third of the AiPaper Mini panel — a native writing-surface
                            // width mismatch we can't size from our process. Until that's solved,
                            // force the software FAST-waveform path, which renders uniformly across
                            // the whole panel (slightly less instant than the native overlay, but
                            // consistent everywhere). Flip this back to BuildConfig.VIWOODS_FAST_INK
                            // to re-enable the native hardware path for testing.
                            //
                            // Native T1000 fast ink (initWriting → libpaintworker → WritingSurface)
                            // is the ONLY fast path on this panel; the AutoDraw binder path can't set
                            // native rects (framework bug in updateAutoDrawRegion) so it never produces
                            // fast ink. See jdkruzr's VIWOODS_APP_DEV.md ("How Fast Ink Actually Works").
                            //
                            // Prerequisite: persist.sys.focusmonitor.config=1 present AT BOOT + targetSdk
                            // 30. Both satisfied — yet hardware ink still does not render on the AiPaper
                            // Mini from a sideloaded app. PROVEN 2026-07 by building jdkruzr's own PoC
                            // (com.example.einkpoc) and running the identical full recipe (initWriting +
                            // ENoteWriting.setAutoDrawRects + T1000 arm): the PoC is slow too. The
                            // libpaintworker WritingSurface can't lock the system buffer from the
                            // untrusted_app_30 sandbox (lock error:-22); only a privileged /product app
                            // (like WiNote) can. Keep the live software FAST-waveform path until Ledger
                            // can be installed as a system/privileged app (needs root). The full hardware
                            // recipe stays in ViwoodsFastInk.enable(), gated off here.
                            // Permanently OFF. The hardware path suppresses live software drawing
                            // (native is supposed to paint), but native never paints on this sideloaded
                            // app — so it's strictly WORSE (ink only appears on pen-up). Exhaustively
                            // disproven 2026-07: prop set + reboot + targetSdk30 + full recipe + adb root
                            // + SELinux permissive + WiNote-primed native state — all still slow. Leave
                            // software FAST-waveform; hardware recipe stays in ViwoodsFastInk, gated here.
                            // 2026-07-11: hardware ink RE-ENABLED on the viwoods flavor. Diffing
                            // WiNote's working logcat against a failing sideloaded app on the same
                            // freshly-rebooted (clean) WritingProducer queue showed the sideloaded
                            // failure was self-inflicted: the AutoDraw binder path + T1000 arm made
                            // system_server spin up a WritingSurface that can't lock the producer
                            // (lock error:-22). WiNote calls NONE of that — it uses only the
                            // in-process JNI recipe (initWriting → setWritingEnabled → onWritingStart).
                            // ViwoodsFastInk.enable() now mirrors WiNote exactly. Gate on the flavor
                            // flag so the Boox (standard, targetSdk 36) build never takes this path.
                            // Hardware ink gated OFF: the app-level API surface now matches WiNote
                            // exactly (initWriting → setWritingEnabled → onWritingStart → setWriting-
                            // JavaBackgroundBitmap, all fire "ok", no lock error:-22) yet the native
                            // RjHandWriting fast-show engine still won't paint — the remaining gate is
                            // native/first-party and the /product-install test is blocked by a LOCKED
                            // bootloader (unlock = wipe). Software FAST-waveform is the daily path.
                            // Flip to BuildConfig.VIWOODS_FAST_INK to resume the hardware experiment.
                            // Daily = software ink (usable, optimized: segment-only live refresh +
                            // no frame delay). Hardware path preserved behind the flag; flip to
                            // BuildConfig.VIWOODS_FAST_INK to resume the experiment.
                            val useHardwareInk = false
                            viwoodsInk?.enable(
                                dm.widthPixels, dm.heightPixels,
                                useHardwareInk
                            )
                            // Hand the native overlay the page bitmap at setup (WiNote sets it once,
                            // full device res, before overlay-enable). Refreshed again per stroke.
                            if (viwoodsInk?.hardwareInk == true) {
                                viwoodsPageSnapshot()?.let { viwoodsInk?.setPageBitmap(it) }
                            }
                        } catch (t: Throwable) {
                            Timber.w(t, "Viwoods enable() failed")
                        }
                    }
                    // Re-apply the limit rect with the new dimensions (e.g. after rotation),
                    // but coalesce rapid/duplicate surfaceChanged callbacks so the pen reader
                    // cold-starts at most once on open. See [applyPendingLimitRect].
                    touchHelper?.let {
                        pendingLimitWidth = width
                        pendingLimitHeight = height
                        provideSurfaceView().removeCallbacks(applyLimitRectRunnable)
                        provideSurfaceView().postDelayed(applyLimitRectRunnable, LIMIT_RECT_DEBOUNCE_MS)
                    }
                    // On Android 10+, claim the whole SurfaceView area back from the
                    // system gesture-navigation handler. Without this, the bottom ~10%
                    // of the screen (the back-gesture strip) intercepts pen touches and
                    // never delivers them to the app — strokes there silently vanish.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        provideSurfaceView().systemGestureExclusionRects =
                            listOf(Rect(0, 0, width, height))
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Timber.i("surfaceDestroyed")
                    provideSurfaceView().removeCallbacks(applyLimitRectRunnable)
                    appliedLimitWidth = -1
                    appliedLimitHeight = -1
                    holder.removeCallback(surfaceCallback)
                    surfaceCallback = null
                }
            }
        }

        provideSurfaceView().holder.addCallback(surfaceCallback)
    }

    /**
     * Re-apply the Onyx raw-drawing limit rect for the latest surface size, but only when it
     * actually changed. Each setRawDrawingEnabled false->true cold-starts the native pen
     * reader (seconds to spin up on the Go 6 Gen 2), so this is coalesced via
     * [applyLimitRectRunnable] and skipped entirely when the size already matches — the first
     * stroke after opening a page no longer waits through repeated reader cold-starts.
     */
    private fun applyPendingLimitRect() {
        val th = touchHelper ?: return
        val w = pendingLimitWidth
        val h = pendingLimitHeight
        if (w <= 0 || h <= 0) return
        if (w == appliedLimitWidth && h == appliedLimitHeight) return
        th.setRawDrawingEnabled(false)
        th.setLimitRect(Rect(0, 0, w, h), ArrayList())
        th.setRawDrawingEnabled(true)
        th.isRawDrawingRenderEnabled = true
        appliedLimitWidth = w
        appliedLimitHeight = h
        Timber.i("raw limit rect applied: ${w}x${h}")
    }

    /**
     * Clear the surface and the shadow canvas.
     */
    fun clearSurface() {
        val lockerCanvas = provideSurfaceView().holder.lockCanvas() ?: return
        // EpdController is Onyx-only and relies on the SDK being initialized by
        // TouchHelper.create(). On Viwoods we never create the TouchHelper, so this would
        // throw — skip it (AutoDraw owns the panel refresh) and guard defensively.
        if (viwoodsInk == null) {
            try {
                EpdController.enablePost(provideSurfaceView(), 1)
            } catch (t: Throwable) {
                Timber.w(t, "EpdController.enablePost failed")
            }
        }
        lockerCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        provideSurfaceView().holder.unlockCanvasAndPost(lockerCanvas)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    /**
     * The pen's effective base stroke width: the shared preset, narrowed for
     * calligraphy (see CALLIGRAPHY_WIDTH_SCALE).
     */
    protected fun effectivePenWidth(): Float =
        paint.strokeWidth * (if (calligraphyMode) CALLIGRAPHY_WIDTH_SCALE else 1f)

    /** Put the Onyx hardware overlay into the live stroke style that matches the current pen. */
    private fun applyStrokeStyle() {
        touchHelper?.setStrokeStyle(
            if (calligraphyMode) TouchHelper.STROKE_STYLE_FOUNTAIN else TouchHelper.STROKE_STYLE_PENCIL
        )
    }

    /**
     * Broad-nib calligraphy width as a multiple of the base width, from the stroke's DIRECTION:
     * thick when the stroke runs across the nib edge, thin when it runs along it. This directional
     * thick/thin (not pressure) is what gives real calligraphic contrast — the Boox pen barely
     * varies pressure, so a pressure-only nib looked nearly uniform.
     */
    private fun calligraphyWidthFactor(motionAngleRad: Float): Float {
        val nibRad = CALLIGRAPHY_NIB_ANGLE_DEG * (PI.toFloat() / 180f)
        val across = abs(sin(motionAngleRad - nibRad))   // 0 (along the nib) .. 1 (across the nib)
        return CALLIGRAPHY_MIN_FACTOR + (CALLIGRAPHY_MAX_FACTOR - CALLIGRAPHY_MIN_FACTOR) * across
    }

    /**
     * Bake a calligraphy stroke: each segment's width comes from its direction (broad nib), with a
     * gentle pressure nudge on top. Round cap/join keep the segments visually continuous.
     */
    private fun drawCalligraphyPath(targetCanvas: Canvas, strokePaint: Paint, stroke: Stroke) {
        val points = stroke.strokePoints
        strokePaint.color = stroke.color
        val savedCap = strokePaint.strokeCap
        val savedJoin = strokePaint.strokeJoin
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        val base = stroke.strokeWidth
        if (points.size == 1) {
            strokePaint.strokeWidth = base
            targetCanvas.drawPoint(points[0].x, points[0].y, strokePaint)
        } else {
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val angle = atan2(b.y - a.y, b.x - a.x)
                val pAvg = ((a.p + b.p) / 2f).coerceIn(0f, 1f)
                strokePaint.strokeWidth = (base * calligraphyWidthFactor(angle) * (0.8f + 0.4f * pAvg)).coerceAtLeast(1f)
                targetCanvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
            }
        }
        strokePaint.strokeCap = savedCap
        strokePaint.strokeJoin = savedJoin
    }

    private fun drawStrokePath(targetCanvas: Canvas, strokePaint: Paint, stroke: Stroke) {
        val points = stroke.strokePoints
        if (points.isEmpty()) return
        if (stroke.inkStyle == Stroke.STYLE_CALLIGRAPHY) {
            drawCalligraphyPath(targetCanvas, strokePaint, stroke)
            return
        }
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.strokeWidth
        // Round cap/join so segment ends and direction changes read smooth, not angular.
        val savedCap = strokePaint.strokeCap
        val savedJoin = strokePaint.strokeJoin
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        val path = Path()
        if (points.size == 1) {
            path.moveTo(points[0].x - 1f, points[0].y - 1f)
            path.lineTo(points[0].x, points[0].y)
        } else {
            // Quadratic Bézier through the segment midpoints: each raw point is the
            // control handle, so the curve passes smoothly between samples instead of
            // kinking at every point. (The old quadTo(prePoint, point) put the control
            // ON the segment start, which degenerates to a straight polyline — the
            // source of the jagged look on sparsely-sampled Boox strokes.)
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                path.quadTo(points[i].x, points[i].y, midX, midY)
            }
            val last = points[points.size - 1]
            path.lineTo(last.x, last.y)
        }
        targetCanvas.drawPath(path, strokePaint)
        strokePaint.strokeCap = savedCap
        strokePaint.strokeJoin = savedJoin
    }

    /**
     * Apply strokes on the surface. Strokes are in 1404x1872 canvas space.
     *
     * When clearPage is true we wipe and re-render the shadow bitmap too (used on page
     * load, undo/redo, paste etc.). When false (e.g. routine post-stroke refresh) we
     * skip the shadow redraw entirely — the shadow stays consistent because
     * [convertStrokes] appends new strokes to it incrementally. This keeps the per-stroke
     * cost on weaker devices (Palma 2 Pro) low while still committing the surface to the
     * e-ink layer.
     *
     * @param strokes the list of strokes
     * @param clearPage the clear page flag
     */
    fun applyStrokes(strokes: List<Stroke>, clearPage: Boolean) {
        this.strokes = strokes.toMutableList()
        val lockCanvas = provideSurfaceView().holder.lockCanvas() ?: return

        lockCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val strokePaint = Paint(paint)

        // Draw to screen with zoom matrix
        lockCanvas.save()
        lockCanvas.concat(viewMatrix)
        renderImageElements(lockCanvas)
        for (stroke in strokes) {
            drawStrokePath(lockCanvas, strokePaint, stroke)
        }
        renderTextElements(lockCanvas)
        lockCanvas.restore()

        // Shadow canvas full rebuild only when caller explicitly wants it (page load,
        // undo/redo, paste — anything that may have removed or relocated strokes).
        if (clearPage) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            renderImageElements(canvas)
            for (stroke in strokes) {
                drawStrokePath(canvas, strokePaint, stroke)
            }
            renderTextElements(canvas)
        }

        // Commit the canvas to the surface WITHOUT closing the native pen reader. Previously
        // this toggled setRawDrawingEnabled(false)->(true) around the post, which closes and
        // reopens the reader on every pen-up — a multi-second cold start on the Go 6 Gen 2,
        // so each stroke lift stalled. Pausing only the render flag (isRawDrawingRenderEnabled)
        // lets us post the committed strokes while the reader stays open and warm.
        touchHelper?.isRawDrawingRenderEnabled = false
        provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        touchHelper?.isRawDrawingRenderEnabled = true
    }

    /**
     * Normalize strokes from surface dimensions to unified.
     *
     * @param strokes the strokes
     * @return the normalized strokes
     */
    fun surfaceFrom(strokes: List<Stroke>): List<Stroke> {
        return normalizeStrokes(strokes, surfaceSize.width(), surfaceSize.height(), 1404, 1872)
    }

    /**
     * Normalize strokes to surface dimensions from unified.
     *
     * @param strokes the strokes
     * @return the normalized strokes
     */
    fun surfaceTo(strokes: List<Stroke>): List<Stroke> {
        return normalizeStrokes(strokes, 1404, 1872, surfaceSize.width(), surfaceSize.height())
    }

    /**
     * Normalize strokes.
     *
     * @param strokes the strokes
     * @param fromWidth from width
     * @param fromHeight from height
     * @param toWidth to width
     * @param toHeight to height
     * @return the normalized strokes
     */
    private fun normalizeStrokes(
        strokes: List<Stroke>, fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int
    ): List<Stroke> {
        val strokesCopy = Stroke.listDeepCopy(strokes)

        val widthRatio = 1.0f * toWidth / fromWidth
        val heightRatio = 1.0f * toHeight / fromHeight
        for (stroke in strokesCopy) {
            for (point in stroke.strokePoints) {
                point.x *= widthRatio
                point.y *= heightRatio
            }
        }

        return strokesCopy
    }

    /**
     * The raw input callback of Onyx's pen library.
     */
    private val callback: RawInputCallback = object : RawInputCallback() {
        override fun onPenActive(touchPoint: TouchPoint) {
        }

        override fun onPenUpRefresh(refreshRect: RectF) {
        }

        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
        }

        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
        }

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {
        }

        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
        }

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
        }

        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {
        }
    }

    /**
     * The input callback of stylus events.
     */
    fun callback(motionEvent: MotionEvent, hover: Boolean): Boolean {
        if (hover) {
            val actionHoverEnter = motionEvent.action == MotionEvent.ACTION_HOVER_ENTER
            val actionHoverMove = motionEvent.action == MotionEvent.ACTION_HOVER_MOVE
            val actionHoverExit = motionEvent.action == MotionEvent.ACTION_HOVER_EXIT

            if (actionHoverEnter) {
                return true
            } else if (actionHoverMove) {
                return true
            } else if (actionHoverExit) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (lastPoint == null) {
                        convertStrokes()
                    }
                }, 50)
                return true
            }

            return false
        }

        val toolTypeStylus = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val toolTypeEraser = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        val toolTypeFinger = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

        // TODO: check on other devices (stylus extra button)
        val actionDown = listOf(MotionEvent.ACTION_DOWN, 211).contains(motionEvent.action)
        val actionMove = listOf(MotionEvent.ACTION_MOVE, 213).contains(motionEvent.action)
        val actionUp = listOf(MotionEvent.ACTION_UP, 212).contains(motionEvent.action)

        // "Pen writes, finger manages": while element-manipulation mode is active,
        // FINGER (and injected UNKNOWN) events route through the same handling path
        // as the pen so move/resize/chips work by finger. The imageMode branch
        // consumes them before any ink capture, so a finger can never lay strokes.
        val toolTypeUnknown = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN
        val fingerManipulating = imageMode && (toolTypeFinger || toolTypeUnknown)
        val drawing = ((toolTypeStylus || toolTypeEraser) && !touchDrawingState) ||
            (toolTypeFinger && touchDrawingState) || fingerManipulating
        val erasing = motionEvent.buttonState != 0 || toolTypeEraser

        if (drawing) {
            val canvasPts = screenToCanvas(motionEvent.x, motionEvent.y)
            val x = (10.0f * canvasPts[0]).roundToInt() / 10.0f
            val y = (10.0f * canvasPts[1]).roundToInt() / 10.0f
            val p = (10.0f * motionEvent.pressure).roundToInt() / 10.0f
            val t = Instant.now().toEpochMilli()
            val strokePoint = StrokePoint(x, y, p, t)

            // Note: we intentionally do NOT reject strokes whose coordinates fall outside
            // the 1404x1872 canvas. On devices whose aspect ratio doesn't match (Palma 2
            // Pro, Tab Mini, anything rotated), the canvas is fit-to-surface with
            // whitespace at the edges. The user can see and pen on those whitespace
            // regions — rejecting them caused the TouchHelper to paint a brief preview
            // that vanished on the next applyStrokes refresh, looking like the pen was
            // "blocked" near the edge of the screen.

            // --- Image mode: select / move / resize / crop / delete inserted images ---
            if (imageMode) {
                // Text-box drag: a selected text box follows the finger like an image.
                val selT = selectedTextBox
                if (selT != null) {
                    if (actionDown) {
                        // Resize handle (bottom-right) → drag the box wider/narrower; the
                        // words reflow to the new width, the font size is left untouched.
                        if (textResizeHandle(textElementBounds(selT)).contains(x, y)) {
                            textBoxResize = true
                            return true
                        }
                        if (textElementBounds(selT).contains(x, y)) {
                            textBoxDrag = true
                            imageDragStartX = x
                            imageDragStartY = y
                            textBoxOrigX = selT.x
                            textBoxOrigY = selT.y
                            return true
                        }
                        // Tap another element → grab it instead.
                        val hitT = textElementAt(x, y)
                        if (hitT != null) {
                            selectedTextBox = hitT
                            applyStrokes(strokes, true)
                            drawImageSelection()
                            return true
                        }
                        val hitI = imageElementAt(x, y)
                        if (hitI != null) {
                            enterImageManipulation(hitI)
                            return true
                        }
                        // Blank tap → done, back to the pen.
                        exitImageMode()
                        penState = true
                        applyStrokes(strokes, true)
                        return true
                    }
                    if (actionMove && textBoxResize) {
                        // Only the width changes; height is recomputed from the wrap on render.
                        selT.width = (x - selT.x).coerceAtLeast(MIN_TEXTBOX_WIDTH)
                        drawImageSelection()
                        return true
                    }
                    if (actionMove && textBoxDrag) {
                        selT.x = textBoxOrigX + (x - imageDragStartX)
                        selT.y = textBoxOrigY + (y - imageDragStartY)
                        drawImageSelection()
                        return true
                    }
                    if (actionUp && (textBoxDrag || textBoxResize)) {
                        textBoxDrag = false
                        textBoxResize = false
                        onTextElementsChanged(textElements)
                        onTextBoxDropped(selT)
                        applyStrokes(strokes, true)
                        drawImageSelection()
                        return true
                    }
                    return true
                }
                val sel = selectedImage
                // Crop: drag a rectangle over the image, apply on release.
                if (cropMode && sel != null) {
                    when {
                        // A fresh press starts the crop rectangle. The chip-tap that armed crop
                        // mode has cropDragging=false, so its trailing move/up are ignored.
                        actionDown -> { cropStartX = x; cropStartY = y; cropRect = RectF(x, y, x, y); cropDragging = true }
                        actionMove -> if (cropDragging) {
                            cropRect = RectF(minOf(cropStartX, x), minOf(cropStartY, y), maxOf(cropStartX, x), maxOf(cropStartY, y))
                            drawImageSelection()
                        }
                        actionUp -> if (cropDragging) {
                            cropDragging = false
                            val cr = cropRect
                            if (cr != null && cr.width() > 12f && cr.height() > 12f) {
                                pushUndo()
                                applyCropToImage(sel, cr)
                                onImageElementsChanged(imageElements)
                            }
                            cropMode = false
                            cropRect = null
                            applyStrokes(strokes, true)
                            drawImageSelection()
                        }
                    }
                    return true
                }
                if (actionDown) {
                    if (sel != null) {
                        val box = RectF(sel.x, sel.y, sel.x + sel.width, sel.y + sel.height)
                        // Chips: Cut / Copy / Dup / Del (unified clipboard via strokeClipboard)
                        for ((label, r) in imageChipRects(box)) {
                            if (r.contains(x, y)) {
                                when (label) {
                                    "Cut" -> {
                                        strokeClipboard.copyImage(sel)
                                        pushUndo()
                                        imageElements.remove(sel)
                                        imageBitmapCache.remove(sel.elementId)
                                        selectedImage = null
                                        onImageElementsChanged(imageElements)
                                        applyStrokes(strokes, true)
                                    }
                                    "Crop" -> {
                                        cropMode = true
                                        cropRect = null
                                        Toast.makeText(requireContext(), "Drag a rectangle over the image to crop", Toast.LENGTH_SHORT).show()
                                    }
                                    "Del" -> {
                                        pushUndo()
                                        imageElements.remove(sel)
                                        imageBitmapCache.remove(sel.elementId)
                                        selectedImage = null
                                        onImageElementsChanged(imageElements)
                                        applyStrokes(strokes, true)
                                    }
                                }
                                return true
                            }
                        }
                        // Resize handle (bottom-right) → uniform scale
                        if (imageResizeHandle(box).contains(x, y)) {
                            pushUndo()
                            imageDrag = ImageDrag.RESIZE
                            imageOrigRect = RectF(box)
                            return true
                        }
                        // Inside the box → move
                        if (box.contains(x, y)) {
                            pushUndo()
                            imageDrag = ImageDrag.MOVE
                            imageDragStartX = x
                            imageDragStartY = y
                            imageOrigRect = RectF(box)
                            return true
                        }
                    }
                    // Tap an image → select it; tap blank with a selection → deselect;
                    // tap blank with nothing selected → add a new image (clipboard or picker).
                    val hit = imageElements.lastOrNull {
                        x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height
                    }
                    val hitText = textElementAt(x, y)
                    when {
                        hit == null && hitText != null -> {
                            enterTextBoxManipulation(hitText)
                        }
                        hit != null -> {
                            // Select AND arm a move so the very first tap can drag the image.
                            selectedImage = hit
                            pushUndo()
                            imageDrag = ImageDrag.MOVE
                            imageDragStartX = x
                            imageDragStartY = y
                            imageOrigRect = RectF(hit.x, hit.y, hit.x + hit.width, hit.y + hit.height)
                            applyStrokes(strokes, true)
                            drawImageSelection()
                        }
                        selectedImage != null -> {
                            selectedImage = null
                            applyStrokes(strokes, true)
                        }
                        // Blank tap with nothing selected → leave manipulation mode
                        // (insertion now lives in the finger long-press menu).
                        else -> {
                            exitImageMode()
                            penState = true
                            applyStrokes(strokes, true)
                        }
                    }
                    return true
                } else if (actionMove && sel != null && imageDrag == ImageDrag.MOVE) {
                    sel.x = imageOrigRect.left + (x - imageDragStartX)
                    sel.y = imageOrigRect.top + (y - imageDragStartY)
                    drawImageSelection()
                    return true
                } else if (actionMove && sel != null && imageDrag == ImageDrag.RESIZE) {
                    val newW = (x - sel.x).coerceAtLeast(40f)
                    sel.width = newW
                    sel.height = newW * (imageOrigRect.height() / imageOrigRect.width().coerceAtLeast(1f))
                    drawImageSelection()
                    return true
                } else if (actionUp && imageDrag != ImageDrag.NONE) {
                    imageDrag = ImageDrag.NONE
                    onImageElementsChanged(imageElements)
                    applyStrokes(strokes, true)
                    drawImageSelection()
                    return true
                }
                return true
            }

            // --- Text mode: tap to place a text element ---
            if (textMode) {
                if (actionDown) {
                    showTextInputDialog(x, y)
                }
                return true
            }

            // --- Paste mode: tap to place clipboard contents ---
            if (pasteMode) {
                if (actionDown && strokeClipboard.hasContent) {
                    val pastedStrokes = strokeClipboard.stampAt(x, y)
                    strokes.addAll(pastedStrokes)
                    onStrokesAdded(pastedStrokes)
                    applyStrokes(strokes, true)
                    onStrokeChanged(strokes)
                    // Keep the freshly pasted strokes selected so the user can
                    // immediately drag-to-move or resize them via the lasso overlay.
                    selectedStrokes = pastedStrokes.toMutableList()
                    hasSelection = true
                    selectionMode = false
                    selBox = computeSelBox(selectedStrokes)
                    drawWithSelection()
                    showMessage(R.string.calendar_drawing_toolbar_pasted, provideSurfaceView())
                }
                if (actionUp) {
                    pasteMode = false
                    provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
                    provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
                }
                return true
            }

            // --- Selection committed: handle chip taps and corner-handle drags ---
            if (hasSelection && !selectionMode) {
                val box = selBox
                if (box != null) {
                    if (actionDown) {
                        // Cut chip?
                        if (cutChipRect(box).contains(x, y)) {
                            pushUndo()
                            strokeClipboard.copy(selectedStrokes.toList())
                            val removedIds = selectedStrokes.map { it.strokeId }
                            strokes.removeAll { it.strokeId in removedIds.toSet() }
                            onStrokesDeleted(removedIds)
                            exitSelectionMode(deferRawResume = true)   // resume after this tap lifts (no stray dot)
                            applyStrokes(strokes, true)
                            onStrokeChanged(strokes)
                            return true
                        }
                        // Copy chip?
                        if (copyChipRect(box).contains(x, y)) {
                            strokeClipboard.copy(selectedStrokes.toList())
                            showMessage(R.string.calendar_drawing_toolbar_copied, provideSurfaceView())
                            return true
                        }
                        // Corner handle?
                        val hit = hitTestHandle(x, y, box)
                        if (hit != SelectionDrag.NONE) {
                            pushUndo()
                            selectionDrag = hit
                            scaleOrigBox = RectF(box)
                            val (ax, ay) = anchorForHandle(hit, box)
                            scaleAnchorX = ax
                            scaleAnchorY = ay
                            scaleOrigPoints = selectedStrokes.associate { stroke ->
                                stroke.strokeId to stroke.strokePoints.map { it.x to it.y }
                            }
                            return true
                        }
                        // Inside the bbox (but not on a handle/chip)? Start a MOVE drag.
                        if (box.contains(x, y)) {
                            pushUndo()
                            selectionDrag = SelectionDrag.MOVE
                            moveStartX = x
                            moveStartY = y
                            scaleOrigPoints = selectedStrokes.associate { stroke ->
                                stroke.strokeId to stroke.strokePoints.map { it.x to it.y }
                            }
                            return true
                        }
                        // Tap outside the box / chips / handles → cancel selection.
                        exitSelectionMode()
                        applyStrokes(strokes, true)
                        return true
                    } else if (actionMove && selectionDrag == SelectionDrag.MOVE) {
                        val dx = x - moveStartX
                        val dy = y - moveStartY
                        for (stroke in selectedStrokes) {
                            val origs = scaleOrigPoints[stroke.strokeId] ?: continue
                            for ((i, pt) in stroke.strokePoints.withIndex()) {
                                if (i >= origs.size) break
                                val (ox, oy) = origs[i]
                                pt.x = ox + dx
                                pt.y = oy + dy
                            }
                        }
                        selBox = computeSelBox(selectedStrokes)
                        drawWithSelection()
                        return true
                    } else if (actionMove && selectionDrag != SelectionDrag.NONE) {
                        val orig = scaleOrigBox ?: return true
                        val origW = orig.width().coerceAtLeast(1f)
                        val origH = orig.height().coerceAtLeast(1f)
                        val newW = abs(x - scaleAnchorX).coerceAtLeast(1f)
                        val newH = abs(y - scaleAnchorY).coerceAtLeast(1f)
                        // Uniform scale based on the larger demanded factor so the
                        // bounding-box still touches the dragged corner exactly.
                        val s = maxOf(newW / origW, newH / origH).coerceIn(0.1f, 10.0f)
                        for (stroke in selectedStrokes) {
                            val origs = scaleOrigPoints[stroke.strokeId] ?: continue
                            for ((i, pt) in stroke.strokePoints.withIndex()) {
                                if (i >= origs.size) break
                                val (ox, oy) = origs[i]
                                pt.x = scaleAnchorX + (ox - scaleAnchorX) * s
                                pt.y = scaleAnchorY + (oy - scaleAnchorY) * s
                            }
                        }
                        selBox = computeSelBox(selectedStrokes)
                        drawWithSelection()
                        return true
                    } else if (actionUp && selectionDrag != SelectionDrag.NONE) {
                        selectionDrag = SelectionDrag.NONE
                        scaleOrigBox = null
                        scaleOrigPoints = emptyMap()
                        applyStrokes(strokes, true)
                        onStrokeChanged(strokes)
                        // applyStrokes wipes the bbox/handles/chips off the surface;
                        // redraw them so the selection stays visible and editable.
                        drawWithSelection()
                        return true
                    }
                }
                // Hover / unhandled — let the pen still flow through to the normal
                // drawing path so the user can write nearby; tapping outside clears.
            }

            // --- Lasso selection mode: collect polygon points ---
            if (selectionMode && !hasSelection) {
                if (actionDown) {
                    selectionPoints.clear()
                    selectionPoints.add(PointF(x, y))
                } else if (actionMove) {
                    selectionPoints.add(PointF(x, y))
                    drawWithSelection()
                } else if (actionUp) {
                    selectionPoints.add(PointF(x, y))
                    // Close polygon and test strokes
                    if (selectionPoints.size >= 3) {
                        selectedStrokes.clear()
                        for (stroke in strokes) {
                            if (StrokeClipboard.isStrokeInsidePolygon(stroke, selectionPoints)) {
                                selectedStrokes.add(stroke)
                            }
                        }
                        hasSelection = true
                        selectionMode = false
                        selBox = computeSelBox(selectedStrokes)
                        syncRawInkToSelectionMenu()   // selection→menu still active; keep hardware ink paused
                        drawWithSelection()
                        if (selectedStrokes.isEmpty()) {
                            showMessage(R.string.calendar_drawing_toolbar_nothing_selected, provideSurfaceView())
                        }
                    }
                }
                return true
            }

            // --- Normal drawing / erasing path ---
            if (actionDown) {
                onBeginDrawing(strokePoint)
            } else if (actionMove) {
                val touchPoints = mutableListOf<StrokePoint>()
                for (i in 0 until motionEvent.historySize) {
                    val hPts = screenToCanvas(motionEvent.getHistoricalX(i), motionEvent.getHistoricalY(i))
                    val hx = (10.0f * hPts[0]).roundToInt() / 10.0f
                    val hy = (10.0f * hPts[1]).roundToInt() / 10.0f
                    val hp = (10.0f * motionEvent.getHistoricalPressure(i)).roundToInt() / 10.0f
                    val ht = Instant.now().toEpochMilli() + motionEvent.getHistoricalEventTime(i) - SystemClock.uptimeMillis()
                    touchPoints.add(StrokePoint(hx, hy, hp, ht))
                }

                touchPoints.add(strokePoint)
                onMoveDrawing(touchPoints)
            } else if (actionUp) {
                onEndDrawing(strokePoint, erasing, toolTypeFinger)
            } else {
                if (!actions.contains("${motionEvent.action}")) actions.add("${motionEvent.action}")
                if (!buttons.contains("${motionEvent.buttonState}")) buttons.add("${motionEvent.buttonState}")
            }

            return true
        }

        return false
    }

    private fun epsilon(touchPoint: StrokePoint, lastPoint: StrokePoint): Boolean {
        return epsilon(touchPoint.x, touchPoint.y, lastPoint.x, lastPoint.y, 3.0f)
    }

    private fun epsilon(x1: Float, y1: Float, x2: Float, y2: Float, epsilon: Float): Boolean {
        val dx = abs(x1 - x2).toDouble()
        val dy = abs(y1 - y2).toDouble()
        val d = sqrt(dx * dx + dy * dy)
        return d <= epsilon
    }

    // ─── Always-on ink gestures: circle-to-lasso and scribble-to-erase ──────────
    // Evaluated at pen-up in plain pen mode (see onEndDrawing). A completed stroke is
    // analysed geometrically: a deliberate ring drawn around existing ink becomes a
    // lasso selection, and a dense back-and-forth scribble over existing ink erases the
    // strokes underneath. Anything ambiguous — a handwritten "O", or a loop/scribble
    // over blank space — is left alone and commits as normal ink.

    /** Geometry summary of a candidate gesture stroke. */
    private class GestureMetrics(
        val pathLength: Float,
        val bboxDiag: Float,
        val closureDist: Float,
        val netTurning: Float,
        val absTurning: Float,
    )

    private fun gestureMetrics(points: List<StrokePoint>): GestureMetrics {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var pathLength = 0f
        for (i in points.indices) {
            val p = points[i]
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (i > 0) pathLength += hypot(p.x - points[i - 1].x, p.y - points[i - 1].y)
        }
        var netTurning = 0f
        var absTurning = 0f
        for (i in 1 until points.size - 1) {
            val ax = points[i].x - points[i - 1].x
            val ay = points[i].y - points[i - 1].y
            val bx = points[i + 1].x - points[i].x
            val by = points[i + 1].y - points[i].y
            val cross = ax * by - ay * bx
            val dot = ax * bx + ay * by
            val ang = atan2(cross, dot)            // signed turn angle at this vertex
            if (!ang.isNaN()) {
                netTurning += ang
                absTurning += abs(ang)
            }
        }
        val closure = hypot(points.last().x - points.first().x, points.last().y - points.first().y)
        return GestureMetrics(pathLength, hypot(maxX - minX, maxY - minY), closure, netTurning, absTurning)
    }

    /** Count direction reversals (sign flips of the per-vertex turn) — high for a scribble. */
    private fun gestureReversals(points: List<StrokePoint>): Int {
        var reversals = 0
        var lastSign = 0
        for (i in 1 until points.size - 1) {
            val ax = points[i].x - points[i - 1].x
            val ay = points[i].y - points[i - 1].y
            val bx = points[i + 1].x - points[i].x
            val by = points[i + 1].y - points[i].y
            val cross = ax * by - ay * bx
            val sign = if (cross > 1f) 1 else if (cross < -1f) -1 else 0
            if (sign != 0) {
                if (lastSign != 0 && sign != lastSign) reversals++
                lastSign = sign
            }
        }
        return reversals
    }

    /** Result of fitting a circle to a candidate stroke — shape flags + the metrics behind them. */
    private class CircleFit(
        val shapeOk: Boolean,     // shape ALONE reads as a ring (size-floor + band + coverage + closed-ish)
        val sizeOk: Boolean,      // above the degenerate size floor
        val closedish: Boolean,   // roughly closed OR wraps most of the way round → a real ring attempt
        val bandFrac: Float,      // fraction of points within the radial band of r̄
        val coverageDeg: Float,   // angular span the points cover around the centroid
        val closureOverR: Float,  // endpoint→start distance as a multiple of r̄
        val diameterPx: Float,    // bbox diagonal — the ring's rough diameter
    )

    /**
     * Fit a circle to the stroke and decide if it's a ring. Robust to wobble/ovals: a
     * hand-drawn circle has most points at a similar radius from the centroid (radial
     * band) and its points sweep most of the way around (angular coverage) — neither of
     * which a shaky hand breaks, unlike the old turning-consistency ratio.
     */
    private fun classifyCircle(points: List<StrokePoint>): CircleFit {
        if (points.size < GESTURE_MIN_POINTS) return CircleFit(false, false, false, 0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var sumX = 0f; var sumY = 0f
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            sumX += p.x; sumY += p.y
        }
        val bboxDiag = hypot(maxX - minX, maxY - minY)
        val sizeOk = bboxDiag >= GESTURE_MIN_CIRCLE_DIAG
        val cx = sumX / points.size; val cy = sumY / points.size

        var sumR = 0f
        val radii = FloatArray(points.size)
        for (i in points.indices) {
            val r = hypot(points[i].x - cx, points[i].y - cy)
            radii[i] = r; sumR += r
        }
        val rBar = sumR / points.size
        if (rBar < 1f) return CircleFit(false, sizeOk, false, 0f, 0f, 0f, bboxDiag)

        // (a) radial band: how many points sit at ~r̄.
        val band = GESTURE_CIRCLE_BAND * rBar
        val inBand = radii.count { abs(it - rBar) <= band }
        val bandFrac = inBand.toFloat() / points.size

        // (b) angular coverage: bin the points' bearings into 24×15° buckets.
        val bins = BooleanArray(24)
        for (p in points) {
            var ang = atan2(p.y - cy, p.x - cx)                 // (−π, π]
            if (ang < 0f) ang += (2f * PI.toFloat())
            var b = (ang / (2f * PI.toFloat()) * 24f).toInt()
            if (b < 0) b = 0; if (b > 23) b = 23
            bins[b] = true
        }
        val coverageDeg = bins.count { it } * 15f

        // (c) closure.
        val closureDist = hypot(points.last().x - points.first().x, points.last().y - points.first().y)
        val closureOverR = closureDist / rBar

        val closedish = closureOverR <= GESTURE_CIRCLE_CLOSURE_R || coverageDeg >= GESTURE_CIRCLE_COVERAGE_DEG
        // Shape alone — NOT size-gated as a safeguard (floor only). Enclosure of OTHER ink
        // is applied at the call site and is what actually distinguishes a lasso from a letter.
        val shapeOk = sizeOk &&
            bandFrac >= GESTURE_CIRCLE_BAND_FRAC &&
            coverageDeg >= GESTURE_CIRCLE_COVERAGE_DEG &&
            closedish
        return CircleFit(shapeOk, sizeOk, closedish, bandFrac, coverageDeg, closureOverR, bboxDiag)
    }

    /** True if the ring described by [points] encircles any existing ink at all (≥1 point inside). */
    private fun encirclesInk(points: List<StrokePoint>): Boolean {
        val polygon = points.map { PointF(it.x, it.y) }
        if (polygon.size < 3) return false
        return strokes.any { StrokeClipboard.isStrokeInsidePolygon(it, polygon) }
    }

    /**
     * The real lasso safeguard: does the ring enclose a DIFFERENT existing stroke, counting a
     * stroke as enclosed when a MAJORITY (≥ [GESTURE_ENCLOSE_MAJORITY]) of its points fall inside
     * the ring polygon — tolerant of the ring grazing/overlapping the letter. The ring itself is
     * not yet in [strokes] at eval time, so it's naturally excluded.
     */
    private fun enclosesOtherInk(points: List<StrokePoint>): Boolean {
        val polygon = points.map { PointF(it.x, it.y) }
        if (polygon.size < 3) return false
        return strokes.any { stroke ->
            val pts = stroke.strokePoints
            if (pts.isEmpty()) return@any false
            val inside = pts.count { StrokeClipboard.isPointInPolygon(PointF(it.x, it.y), polygon) }
            inside.toFloat() / pts.size >= GESTURE_ENCLOSE_MAJORITY
        }
    }

    private fun isScribbleGesture(points: List<StrokePoint>): Boolean {
        if (points.size < 8) return false
        val m = gestureMetrics(points)
        if (m.bboxDiag < 1f) return false
        if (m.pathLength < GESTURE_SCRIBBLE_LENGTH_RATIO * m.bboxDiag) return false      // lots of length in a small area
        if (m.absTurning < GESTURE_SCRIBBLE_MIN_ABS_TURN) return false                   // lots of wiggle…
        if (m.absTurning > 0f && abs(m.netTurning) / m.absTurning > 0.4f) return false   // …that cancels out (not a loop)
        if (gestureReversals(points) < GESTURE_SCRIBBLE_MIN_REVERSALS) return false
        return true
    }

    /** Erase every committed stroke the scribble passed over. Returns false (→ commit as ink) if it hit nothing. */
    private fun performScribbleErase(points: List<StrokePoint>): Boolean {
        val toRemove: MutableSet<UUID> = mutableSetOf()
        for (ep in points) {
            for (stroke in strokes) {
                if (stroke.strokeId in toRemove) continue
                for (tp in stroke.strokePoints) {
                    if (epsilon(ep.x, ep.y, tp.x, tp.y, 25.0f)) {
                        toRemove.add(stroke.strokeId)
                        break
                    }
                }
            }
        }
        if (toRemove.isEmpty()) return false
        strokes.removeIf { it.strokeId in toRemove }
        onStrokesDeleted(toRemove.toList())
        applyStrokes(strokes, true)
        onStrokeChanged(strokes)
        return true
    }

    /**
     * Short confirmation buzz when an ink gesture (circle-to-lasso / scribble-to-erase)
     * fires — the user can't watch logcat on the tablet, so this is the "it caught" signal
     * alongside the on-screen selection/erase. No-ops silently on a device without a motor.
     */
    private fun gestureHaptic() {
        try {
            val vib = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(35)
            }
        } catch (_: Exception) { /* haptics are best-effort */ }
    }

    /** Turn the circle into a lasso selection of the enclosed ink. Returns false (→ commit as ink) if it enclosed nothing. */
    private fun performCircleSelect(points: List<StrokePoint>): Boolean {
        val polygon = points.map { PointF(it.x, it.y) }.toMutableList()
        val enclosed = strokes.filter { StrokeClipboard.isStrokeInsidePolygon(it, polygon) }
        if (enclosed.isEmpty()) return false
        selectionPoints.clear()
        selectionPoints.addAll(polygon)
        selectedStrokes = enclosed.toMutableList()
        hasSelection = true
        selectionMode = false
        selBox = computeSelBox(selectedStrokes)
        syncRawInkToSelectionMenu()   // selection up → pause hardware ink so menu taps don't dot
        applyStrokes(strokes, true)
        drawWithSelection()
        return true
    }

    private fun onBeginDrawing(touchPoint: StrokePoint) {
        Timber.i("onBeginDrawing (${touchPoint.x}/${touchPoint.y})")
        // Start the coalescer from a clean slate (drop any post left scheduled from a prior stroke).
        provideSurfaceView().removeCallbacks(viwoodsLivePostRunnable)
        viwoodsLivePostScheduled = false
        // Cancel a pending deferred re-bake so it can't fire mid-stroke (the bake toggles
        // render + posts the canvas, which would interrupt the live stroke).
        provideSurfaceView().removeCallbacks(commitVisualRunnable)
        lastPoint = touchPoint
        firstPointTimestamp = Instant.now().toEpochMilli()
        touchPoint.t = 0L
        stylusPointList.add(touchPoint)
        viwoodsLastLiveIndex = 0
        if (penState) {
            // Hardware ink: enable the native overlay FIRST, then hand it the page bitmap. The
            // native RjHandWriting engine is readied asynchronously (~0.3s after initWriting by an
            // eink-worker thread), so a single early setBackgroundBitmap hits a null engine
            // ("bufWorker is null"). WiNote succeeds because it re-pushes the bitmap repeatedly; we
            // do the same — here on stroke start and again on every move (see onMoveDrawing).
            viwoodsInk?.onStrokeStart()
            viwoodsInk?.reassertFastMode()
            if (viwoodsInk?.hardwareInk == true) {
                viwoodsCachedSnapshot = viwoodsPageSnapshot()
                viwoodsCachedSnapshot?.let { viwoodsInk?.setPageBitmap(it) }
            }
        }
    }

    /**
     * Render the current page (template + committed strokes) to a full device-resolution bitmap,
     * for the Viwoods native writing overlay's background. Uses [viewMatrix] so logical canvas
     * coordinates map to the panel exactly as on screen. Viwoods hardware-ink path only.
     */
    private fun viwoodsPageSnapshot(): android.graphics.Bitmap? {
        // Full device-panel resolution (WiNote hands the native layer a 1440×1920 bitmap).
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        if (w <= 0 || h <= 0) { Timber.w("viwoodsPageSnapshot null: dm ${w}x${h}"); return null }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        // Map logical canvas (CANVAS_WIDTH×CANVAS_HEIGHT) to fill the full panel.
        c.save()
        c.scale(w.toFloat() / CANVAS_WIDTH, h.toFloat() / CANVAS_HEIGHT)
        if (::templateBitmap.isInitialized) c.drawBitmap(templateBitmap, 0f, 0f, null)
        renderImageElements(c)
        for (stroke in strokes) drawStrokePath(c, paint, stroke)
        c.restore()
        Timber.i("viwoodsPageSnapshot ${w}x${h} strokes=${strokes.size}")
        return bmp
    }

    private fun onMoveDrawing(touchPoints: List<StrokePoint>) {
        if (stylusPointList.isEmpty()) return
        val path = Path()
        path.moveTo(stylusPointList[0].x, stylusPointList[0].y)
        stylusPointList.forEach {
            path.lineTo(it.x, it.y)
        }
        touchPoints.forEach { touchPoint ->
            path.lineTo(touchPoint.x, touchPoint.y)
            if (!epsilon(touchPoint, lastPoint!!)) {
                touchPoint.t = Instant.now().toEpochMilli() - firstPointTimestamp
                lastPoint = touchPoint
                stylusPointList.add(touchPoint)
            }
        }

        // Hardware ink: re-push the cached page bitmap on every move. The native RjHandWriting
        // engine is readied asynchronously after initWriting, so repeated pushes ensure one lands
        // once it's ready (WiNote re-pushes similarly). Cheap: reuses the stroke-start snapshot.
        if (penState && viwoodsInk?.hardwareInk == true) {
            viwoodsCachedSnapshot?.let { viwoodsInk?.setPageBitmap(it) }
            return
        }

        // Software live rendering for the no-Onyx path. Skipped when Viwoods hardware ink is
        // active (the T1000 renders the live stroke natively — drawing it ourselves too would
        // double-image). On the FAST-waveform fallback the panel is in FAST mode so these
        // partial posts refresh quickly.
        if (touchHelper == null && penState && viwoodsInk?.hardwareInk != true) {
            if (viwoodsInk != null) {
                // Viwoods: render THIS batch of points immediately. The old code coalesced to one
                // post per animation frame because each post refreshed the whole-stroke bbox and a
                // burst would back up. Now the live refresh is segment-only (constant, tiny cost —
                // see renderLivePreviewSoftware), so per-batch synchronous rendering no longer backs
                // up and removes the ~1-frame postOnAnimation delay that made the ink trail the nib.
                renderLivePreviewSoftware()
            } else {
                // Non-Onyx Boox fallback (e.g. Palma 2 Pro): render immediately, unchanged.
                renderLivePreviewSoftware()
            }
        }
    }

    /**
     * Software live-stroke preview: redraw the whole in-progress stroke and post it to the
     * SurfaceView. The full path is redrawn into a whole-stroke dirty rect every time on
     * purpose — a multi-buffered SurfaceView leaves anything outside the dirty rect undefined
     * across buffer swaps, so a smaller/incremental post makes earlier segments blink and
     * vanish. Used by the no-Onyx path; on Viwoods it is driven by the per-frame coalescer.
     */
    private fun renderLivePreviewSoftware() {
        if (stylusPointList.isEmpty()) return
        val path = Path()
        path.moveTo(stylusPointList[0].x, stylusPointList[0].y)
        for (i in 1 until stylusPointList.size) {
            path.lineTo(stylusPointList[i].x, stylusPointList[i].y)
        }

        val totalScale = baseScale * zoomScale
        val sigma = paint.strokeWidth * totalScale * 4.0f

        // Dirty rect covers ONLY the points added since the last post (bridged to the previous
        // point so the new segment joins seamlessly), NOT the whole stroke. This keeps each e-ink
        // partial refresh tiny and constant-cost even as the stroke grows long — the whole-stroke
        // bbox was making every frame refresh a bigger region, so the ink trailed the nib. We still
        // redraw the full path (vector, cheap) but clipped to this small rect, so nothing outside it
        // is touched and prior posted segments are preserved.
        val from = (viwoodsLastLiveIndex - 1).coerceAtLeast(0)
        val recent = stylusPointList.subList(from, stylusPointList.size)
        val rx = recent.map { it.x }
        val ry = recent.map { it.y }
        val minPt = floatArrayOf(rx.min() - sigma / totalScale, ry.min() - sigma / totalScale)
        val maxPt = floatArrayOf(rx.max() + sigma / totalScale, ry.max() + sigma / totalScale)
        viewMatrix.mapPoints(minPt)
        viewMatrix.mapPoints(maxPt)
        val rect = Rect(
            minPt[0].toInt().coerceAtLeast(0),
            minPt[1].toInt().coerceAtLeast(0),
            maxPt[0].toInt().coerceAtMost(surfaceSize.width()),
            maxPt[1].toInt().coerceAtMost(surfaceSize.height())
        )

        val lockCanvas = provideSurfaceView().holder.lockCanvas(rect) ?: return
        lockCanvas.save()
        lockCanvas.concat(viewMatrix)
        // Use a non-anti-aliased predraw paint: the full path is redrawn every frame, and
        // re-blending AA edge pixels each frame is what fattens the live line. On the 1-bit
        // FAST waveform crisp pixels look the same and can't accumulate.
        lockCanvas.drawPath(path, viwoodsPredrawPaint())
        lockCanvas.restore()
        provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        viwoodsLastLiveIndex = stylusPointList.size
    }

    /** Lazily-built non-AA paint for the Viwoods software live preview (matches [paint] width/color). */
    private var _viwoodsPredrawPaint: Paint? = null
    private fun viwoodsPredrawPaint(): Paint {
        val p = _viwoodsPredrawPaint ?: Paint().also { _viwoodsPredrawPaint = it }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.isAntiAlias = false
        p.color = paint.color
        p.strokeWidth = paint.strokeWidth
        return p
    }

    private fun onEndDrawing(touchPoint: StrokePoint, erasing: Boolean, finger: Boolean) {
        Timber.i("onEndDrawing (${touchPoint.x}/${touchPoint.y})")
        touchPoint.t = Instant.now().toEpochMilli() - firstPointTimestamp
        stylusPointList.add(touchPoint)

        pushUndo()

        if (!penState || erasing) {
            val strokesToRemove: MutableSet<UUID> = mutableSetOf()
            for (ep in stylusPointList) {
                for (stroke in strokes) {
                    for (tp in stroke.strokePoints) {
                        if (epsilon(ep.x, ep.y, tp.x, tp.y, 25.0f)) {
                            strokesToRemove.add(stroke.strokeId)
                        }
                    }
                }
            }
            if (procrastinator) {
                onStrokesProcrastinated(strokes.filter { it.strokeId in strokesToRemove }.toList())
            }
            strokesToRemove.forEach { strokes.removeIf { stroke -> stroke.strokeId == it } }
            onStrokesDeleted(strokesToRemove.toList())

            applyStrokes(strokes, true)
            onStrokeChanged(strokes)
        } else {
            // Always-on gestures: a deliberate circle around ink becomes a selection,
            // a scribble over ink erases it. Ambiguous marks fall through to commit as ink.
            if (autoGesturesEnabled && !hasSelection && !selectionMode && !pasteMode && !textMode) {
                val gesturePoints = stylusPointList.toList()
                if (isScribbleGesture(gesturePoints) && performScribbleErase(gesturePoints)) {
                    gestureHaptic()
                    lastPoint = null
                    stylusPointList.clear()
                    return
                }
                val fit = classifyCircle(gesturePoints)
                val enclosesOther = enclosesOtherInk(gesturePoints)   // the real lasso safeguard
                val accepted = fit.shapeOk && enclosesOther
                if (accepted && performCircleSelect(gesturePoints)) {
                    gestureHaptic()
                    lastPoint = null
                    stylusPointList.clear()
                    return
                }
                // Diagnostic: a real ring attempt (closed-ish, above the floor, at least grazing
                // other ink) that was NOT accepted — surface the deciding numbers, incl. whether
                // SIZE or ENCLOSURE was the blocker. Gated on closed-ish + encircles ink so
                // normal writing (open letters, self-enclosing "o"s) never toasts.
                if (GESTURE_DEBUG_TOAST && !accepted && fit.closedish && fit.sizeOk && encirclesInk(gesturePoints)) {
                    val msg = "circle? in-band=${(fit.bandFrac * 100).roundToInt()}% " +
                        "coverage=${fit.coverageDeg.roundToInt()}° " +
                        "closure=${((fit.closureOverR * 10).roundToInt() / 10f)}r " +
                        "diam=${fit.diameterPx.roundToInt()}px enclosesOtherInk=${if (enclosesOther) "yes" else "no"}"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }

            val stroke = Stroke(
                UUID.randomUUID(), firstPointTimestamp, stylusPointList.toList(), paint.color, effectivePenWidth(),
                if (calligraphyMode) Stroke.STYLE_CALLIGRAPHY else Stroke.STYLE_NORMAL
            )
            strokes.add(stroke)
            strokesToAdd.add(stroke)

            // Viwoods: the T1000 overlay shows the live stroke then auto-clears ~800ms
            // after pen-up. Re-render the committed strokes to the SurfaceView just after
            // that, so the permanent ink replaces the overlay with no flicker or gap.
            if (viwoodsInk != null) {
                // Drop any coalesced live post still queued, so it can't repaint the preview
                // over the committed strokes we're about to render.
                provideSurfaceView().removeCallbacks(viwoodsLivePostRunnable)
                viwoodsLivePostScheduled = false
                viwoodsInk?.onStrokeEnd()
                if (viwoodsInk?.hardwareInk == true) {
                    // Hardware ink: the native overlay shows the stroke then self-clears ~800ms
                    // after pen-up. Repaint our committed strokes just after, for a seamless
                    // hand-off from the fast (1-bit) overlay to the quality (GL16) layer.
                    val snapshot = Stroke.listDeepCopy(strokes)
                    Handler(Looper.getMainLooper()).postDelayed({ applyStrokes(snapshot, true) }, 900)
                } else {
                    // Software fallback: partial lockCanvas posts don't form a stable buffer,
                    // so repaint immediately to persist the completed mark.
                    applyStrokes(strokes, true)
                }
                onStrokeChanged(strokes)
            }

            // Commit the stroke to storage on pen-up. Previously only finger drawing
            // committed here; stylus drawing relied on the hover-exit handler firing
            // convertStrokes() later. On Boox that hover-exit event isn't reliably
            // delivered, so the stroke stayed only in memory — and a page reload
            // (renderPage → applyStrokes with clearPage) would wipe it before it was
            // ever saved. Committing here persists every stroke immediately, so it
            // survives a reload. convertStrokes() no-ops if there's nothing to add.
            convertStrokes()
        }

        if (actions.isNotEmpty() || buttons.isNotEmpty()) {
            privateFirebaseAnalytics.logEvent("actionsAndButtons") {
                param("brand", Build.BRAND.lowercase())
                param("device", Build.DEVICE.lowercase())
                param("manufacturer", Build.MANUFACTURER.lowercase())
                param("actions", actions.sortedBy { it }.joinToString(","))
                param("buttons", buttons.sortedBy { it }.joinToString(","))
            }
        }

        lastPoint = null
        stylusPointList.clear()
    }

    /**
     * Convert and add the strokes to the internal format and execute the events, like recognition.
     */
    private fun convertStrokes() {
        if (strokesToAdd.isEmpty()) return

        // Persist + notify immediately (the save is already off the main thread), but defer
        // the heavy full-canvas re-bake so it doesn't block delivery of the next pen-down.
        // The live hardware overlay keeps the stroke visible until the bake runs. On the
        // Viwoods software path there's no persistent overlay, so bake right away.
        onStrokeChanged(strokes)
        onStrokesAdded(strokesToAdd.toList())

        processStrokes(strokesToAdd)
        strokesToAdd.clear()

        if (touchHelper != null) {
            provideSurfaceView().removeCallbacks(commitVisualRunnable)
            provideSurfaceView().postDelayed(commitVisualRunnable, COMMIT_VISUAL_DEBOUNCE_MS)
        } else {
            applyStrokes(strokes, false)
        }
    }

    /**
     * Process the strokes, recognize the written text with ML Kit Digital Ink Recognition.
     */
    private fun processStrokes(strokes: List<Stroke>) {
        val inkBuilder = Ink.builder()
        strokes.forEach { stroke ->
            val strokeBuilder: Ink.Stroke.Builder = Ink.Stroke.builder()
            stroke.strokePoints.forEach { point ->
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        val ink = inkBuilder.build()

        val remoteModelManager = RemoteModelManager.getInstance()
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")?.let { mi ->
            val model = DigitalInkRecognitionModel.builder(mi).build()
            this@SurfaceFragment.lifecycleScope.launch(Dispatchers.IO) {
                if (remoteModelManager.isModelDownloaded(model).await()) {
                    val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                    recognizer.recognize(ink).addOnSuccessListener { result ->
                        Timber.i("Recognition result: ${result.candidates}")
                    }.addOnFailureListener { e ->
                        Timber.e("Recognition failed: $e")
                    }
                } else {
                    Timber.w("Model not downloaded yet")
                }
            }
        }
    }
}