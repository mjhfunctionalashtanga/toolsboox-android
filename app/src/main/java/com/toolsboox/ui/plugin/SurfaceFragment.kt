package com.toolsboox.ui.plugin

import android.Manifest
import android.content.SharedPreferences
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GestureDetectorCompat
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
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.databinding.ToolbarDrawingBinding
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.ot.StrokeClipboard
import com.toolsboox.plugin.calendar.CalendarNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.time.Instant
import java.util.*
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SurfaceView fragment of Boox pen and native stylus supports.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
abstract class SurfaceFragment : ScreenFragment() {

    companion object {
        /**
         * Touch drawing state.
         */
        private var touchDrawingState: Boolean = false

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

    /** True while in paste-placement mode (next tap places the clipboard contents). */
    private var pasteMode = false

    // --- Text tool state ---
    /** True while in text placement mode (next tap opens text input dialog). */
    private var textMode = false

    /** The list of text elements on the current page. */
    private var textElements: MutableList<TextElement> = mutableListOf()

    /** Paint used for rendering text elements on canvas. */
    private var textPaint = Paint().apply {
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

        initializeSurface()
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = true

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

        if (touchDrawingState)
            provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_draw)
        else
            provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_touch)

        provideToolbarDrawing().toolbarPen.setOnClickListener {
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
            }
        }

        // --- Copy button ---
        provideToolbarDrawing().toolbarCopy.setOnClickListener {
            if (selectedStrokes.isNotEmpty()) {
                strokeClipboard.copy(selectedStrokes.toList())
                showMessage(R.string.calendar_drawing_toolbar_copied, provideSurfaceView())
            } else {
                showMessage(R.string.calendar_drawing_toolbar_nothing_selected, provideSurfaceView())
            }
        }

        // --- Paste button ---
        provideToolbarDrawing().toolbarPaste.setOnClickListener {
            if (!strokeClipboard.hasContent) {
                showMessage(R.string.calendar_drawing_toolbar_clipboard_empty, provideSurfaceView())
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
            }
        }

        provideToolbarDrawing().toolbarHandTouch.setOnClickListener {
            touchDrawingState = !touchDrawingState
            if (touchDrawingState)
                provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_draw)
            else
                provideToolbarDrawing().toolbarHandTouch.setImageResource(R.drawable.ic_toolbar_hand_touch)
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

        provideToolbarDrawing().toolbarSwitchSide.setOnClickListener {
            onSideSwitched()
        }

        provideToolbarDrawing().toolbarCloudSync.setOnClickListener {
            CalendarNavigator.toCloudSync(this)
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

        provideToolbarDrawing().toolbarCloudSync.visibility = View.VISIBLE

        provideToolbarDrawing().toolbarSettings.setOnClickListener {
            CalendarNavigator.toSettings(this)
        }

        val toolbarCollapsed = sharedPreferences.getBoolean("toolbarCollapsed", false)
        applyToolbarCollapsedState(toolbarCollapsed)

        provideToolbarDrawing().toolbarToggle.setOnClickListener {
            val collapsed = !sharedPreferences.getBoolean("toolbarCollapsed", false)
            sharedPreferences.edit().putBoolean("toolbarCollapsed", collapsed).apply()
            applyToolbarCollapsedState(collapsed)
        }

        templateBitmap = Bitmap.createBitmap(1404, 1872, Bitmap.Config.ARGB_8888)
        templateCanvas = Canvas(templateBitmap)

        navigatorBitmap = Bitmap.createBitmap(1404, 140, Bitmap.Config.ARGB_8888)
        navigatorCanvas = Canvas(navigatorBitmap)

        gestureListener = OnGestureListener()
        gestureDetector = GestureDetectorCompat(requireActivity(), gestureListener)
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false

        touchHelper?.closeRawDrawing()
        bitmap?.recycle()
    }

    private fun applyToolbarCollapsedState(collapsed: Boolean) {
        val toolbar = provideToolbarDrawing()
        val group = toolbar.toolbarButtonGroup
        if (collapsed) {
            group.visibility = View.GONE
            toolbar.toolbarToggle.setImageResource(R.drawable.ic_toolbar_expand)
            toolbar.root.layoutParams?.let { lp ->
                lp.width = (8 * resources.displayMetrics.density).toInt()
                toolbar.root.layoutParams = lp
            }
        } else {
            group.visibility = View.VISIBLE
            toolbar.toolbarToggle.setImageResource(R.drawable.ic_toolbar_collapse)
            toolbar.root.layoutParams?.let { lp ->
                lp.width = (40 * resources.displayMetrics.density).toInt()
                toolbar.root.layoutParams = lp
            }
        }
    }

    /**
     * Exit lasso selection / paste mode and reset all selection state.
     */
    private fun exitSelectionMode() {
        selectionMode = false
        hasSelection = false
        pasteMode = false
        textMode = false
        selectionPoints.clear()
        selectedStrokes.clear()
        provideToolbarDrawing().toolbarLasso.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
        provideToolbarDrawing().toolbarText.background.setTint(Color.WHITE)
    }

    /**
     * Redraw the current strokes with selected strokes highlighted (thicker stroke).
     * Also draws the lasso polygon if points exist.
     */
    private fun drawWithSelection() {
        val lockCanvas = provideSurfaceView().holder.lockCanvas() ?: return

        val fillPaint = Paint()
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.TRANSPARENT
        val rect = Rect(0, 0, provideSurfaceView().width, provideSurfaceView().height)
        lockCanvas.drawRect(rect, fillPaint)
        lockCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val selectedIds = selectedStrokes.map { it.strokeId }.toSet()

        for (stroke in strokes) {
            val points = stroke.strokePoints
            if (points.isNotEmpty()) {
                val path = Path()
                val prePoint = PointF(points[0].x, points[0].y)
                if (points.size == 1) {
                    path.moveTo(prePoint.x - 1f, prePoint.y - 1f)
                } else {
                    path.moveTo(prePoint.x, prePoint.y)
                }
                for (point in points) {
                    path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
                    prePoint.x = point.x
                    prePoint.y = point.y
                }

                val usePaint = if (stroke.strokeId in selectedIds) selectionHighlightPaint else paint
                lockCanvas.drawPath(path, usePaint)
            }
        }

        // Draw lasso polygon
        if (selectionPoints.size > 1) {
            val lassoPath = Path()
            lassoPath.moveTo(selectionPoints[0].x, selectionPoints[0].y)
            for (i in 1 until selectionPoints.size) {
                lassoPath.lineTo(selectionPoints[i].x, selectionPoints[i].y)
            }
            if (hasSelection) {
                lassoPath.close()
            }
            lockCanvas.drawPath(lassoPath, lassoPaint)
        }

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
    }

    /**
     * Render all text elements onto the given canvas.
     *
     * @param targetCanvas the canvas to draw on
     */
    private fun renderTextElements(targetCanvas: Canvas) {
        val typeface = try {
            ResourcesCompat.getFont(requireContext(), R.font.atkinson_hyperlegible)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }

        for (element in textElements) {
            textPaint.textSize = element.fontSize
            textPaint.color = element.color
            textPaint.typeface = typeface ?: Typeface.DEFAULT

            // Draw each line of the text (split on newline)
            val lines = element.text.split("\n")
            val lineHeight = textPaint.fontSpacing
            for ((index, line) in lines.withIndex()) {
                targetCanvas.drawText(
                    line,
                    element.x,
                    element.y + lineHeight * (index + 1),
                    textPaint
                )
            }
        }
    }

    /**
     * Show a text input dialog at the given canvas coordinates.
     *
     * @param x the x coordinate on the surface
     * @param y the y coordinate on the surface
     */
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
        showMessage(getString(R.string.team_drawer_page_export_message).format(title), provideSurfaceView())
    }

    /**
     * Initialize the surface view of drawing.
     *
     * @param first first initialization flag
     */
    fun initializeSurface(first: Boolean = false) {
        val hasOnyxStylus = DeviceFeatureUtil.hasStylus(requireContext())

        if (first) {
            if (hasOnyxStylus) touchHelper = TouchHelper.create(provideSurfaceView(), callback)
            provideSurfaceView().setZOrderOnTop(true)
            provideSurfaceView().holder.setFormat(PixelFormat.TRANSPARENT)
        }

        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = 3.0f

        if (surfaceCallback == null) {
            surfaceCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Timber.i("surfaceCreated")
                    val limit = Rect()
                    provideSurfaceView().getLocalVisibleRect(limit)
                    bitmap = Bitmap.createBitmap(
                        provideSurfaceView().width,
                        provideSurfaceView().height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap!!.eraseColor(Color.TRANSPARENT)
                    canvas = Canvas(bitmap!!)

                    if (provideSurfaceView().holder == null) {
                        return
                    }

                    clearSurface()

                    touchHelper?.setLimitRect(limit, ArrayList())?.setStrokeWidth(3.0f)?.openRawDrawing()
                    touchHelper?.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Timber.i("surfaceChanged: ${width}x${height}")
                    surfaceSize = Rect(0, 0, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Timber.i("surfaceDestroyed")
                    holder.removeCallback(surfaceCallback)
                    surfaceCallback = null
                }
            }
        }

        provideSurfaceView().holder.addCallback(surfaceCallback)
    }

    /**
     * Clear the surface and the shadow canvas.
     */
    fun clearSurface() {
        val lockerCanvas = provideSurfaceView().holder.lockCanvas() ?: return
        EpdController.enablePost(provideSurfaceView(), 1)
        val paint = Paint()
        paint.style = Paint.Style.FILL
        paint.color = Color.TRANSPARENT
        val rect = Rect(0, 0, provideSurfaceView().width, provideSurfaceView().height)
        lockerCanvas.drawRect(rect, paint)
        provideSurfaceView().holder.unlockCanvasAndPost(lockerCanvas)

        canvas.drawRect(rect, paint)
    }

    /**
     * Apply strokes on the surface.
     *
     * @param strokes the list of strokes
     * @param clearPage the clear page flag
     */
    fun applyStrokes(strokes: List<Stroke>, clearPage: Boolean) {
        this.strokes = strokes.toMutableList()
        // TODO: render page when onSurfaceCreated
        val lockCanvas = provideSurfaceView().holder.lockCanvas() ?: return

        val fillPaint = Paint()
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.TRANSPARENT
        val rect = Rect(0, 0, provideSurfaceView().width, provideSurfaceView().height)
        lockCanvas.drawRect(rect, fillPaint)
        lockCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (clearPage) {
            canvas.drawRect(rect, fillPaint)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        for (stroke in strokes) {
            val points = stroke.strokePoints
            if (points.isNotEmpty()) {
                val path = Path()
                val prePoint = PointF(points[0].x, points[0].y)
                if (points.size == 1) {
                    path.moveTo(prePoint.x - 1f, prePoint.y - 1f)
                } else {
                    path.moveTo(prePoint.x, prePoint.y)
                }
                for (point in points) {
                    path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
                    prePoint.x = point.x
                    prePoint.y = point.y
                }

                lockCanvas.drawPath(path, paint)
                canvas.drawPath(path, paint)
            }
        }

        // Render text elements on top of strokes
        renderTextElements(lockCanvas)
        renderTextElements(canvas)

        touchHelper?.setRawDrawingEnabled(false)
        touchHelper?.isRawDrawingRenderEnabled = false
        provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        touchHelper?.setRawDrawingEnabled(true)
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

        val drawing = ((toolTypeStylus || toolTypeEraser) && !touchDrawingState) || (toolTypeFinger && touchDrawingState)
        val erasing = motionEvent.buttonState != 0 || toolTypeEraser

        if (drawing) {
            val x = (10.0f * motionEvent.x).roundToInt() / 10.0f
            val y = (10.0f * motionEvent.y).roundToInt() / 10.0f
            val p = (10.0f * motionEvent.pressure).roundToInt() / 10.0f
            val t = Instant.now().toEpochMilli()
            val strokePoint = StrokePoint(x, y, p, t)

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
                    showMessage(R.string.calendar_drawing_toolbar_pasted, provideSurfaceView())
                }
                if (actionUp) {
                    pasteMode = false
                    provideToolbarDrawing().toolbarPaste.background.setTint(Color.WHITE)
                    provideToolbarDrawing().toolbarPen.background.setTint(Color.GRAY)
                }
                return true
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
                    val hx = (10.0f * motionEvent.getHistoricalX(i)).roundToInt() / 10.0f
                    val hy = (10.0f * motionEvent.getHistoricalY(i)).roundToInt() / 10.0f
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

    private fun onBeginDrawing(touchPoint: StrokePoint) {
        Timber.i("onBeginDrawing (${touchPoint.x}/${touchPoint.y})")
        lastPoint = touchPoint
        firstPointTimestamp = Instant.now().toEpochMilli()
        touchPoint.t = 0L
        stylusPointList.add(touchPoint)
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

        if (touchHelper == null) {
            val sigma = paint.strokeWidth * 4.0f
            val rectLeft = (Math.min(lastPoint!!.x, touchPoints.map { it.x }.min()) - sigma).toInt()
            val rectRight = (Math.max(lastPoint!!.x, touchPoints.map { it.x }.max()) + sigma).toInt()
            val rectTop = (Math.min(lastPoint!!.y, touchPoints.map { it.y }.min()) - sigma).toInt()
            val rectBottom = (Math.max(lastPoint!!.y, touchPoints.map { it.y }.max()) + sigma).toInt()
            val rect = Rect(rectLeft, rectTop, rectRight, rectBottom)

            val lockCanvas = provideSurfaceView().holder.lockCanvas(rect)
            lockCanvas?.drawPath(path, paint)
            provideSurfaceView().holder.unlockCanvasAndPost(lockCanvas)
        }
    }

    private fun onEndDrawing(touchPoint: StrokePoint, erasing: Boolean, finger: Boolean) {
        Timber.i("onEndDrawing (${touchPoint.x}/${touchPoint.y})")
        touchPoint.t = Instant.now().toEpochMilli() - firstPointTimestamp
        stylusPointList.add(touchPoint)

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
            val stroke = Stroke(UUID.randomUUID(), firstPointTimestamp, stylusPointList.toList())
            strokes.add(stroke)
            strokesToAdd.add(stroke)

            if (finger) convertStrokes()
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

        applyStrokes(strokes, false)
        onStrokeChanged(strokes)
        onStrokesAdded(strokesToAdd.toList())

        processStrokes(strokesToAdd)
        strokesToAdd.clear()
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