package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.toolsboox.da.Stroke
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * On-device handwriting → text for a panel's strokes, via ML Kit Digital Ink Recognition.
 * The text feeds the panel-card → Notes & Annotations pipeline (which the chat corpus then
 * RAG-gathers). Returns "" when there is nothing to recognise or the model isn't ready.
 */
object PanelOcr {

    suspend fun recognize(strokes: List<Stroke>, languageTag: String = "en-US"): String {
        if (strokes.isEmpty()) return ""
        val inkBuilder = Ink.builder()
        // ML Kit ink recognition relies on per-point TIMESTAMPS (stroke order + speed). The Ledger
        // stores strokes with t=0, so recognition on real handwriting degrades to nonsense. Synthesize
        // a monotonic clock (≈12ms/point, ≈80ms between strokes) when the stored timestamps are absent.
        var clock = 0L
        for (stroke in strokes) {
            val sb = Ink.Stroke.builder()
            for (p in stroke.strokePoints) {
                val ts = if (p.t > 0L) p.t else clock
                sb.addPoint(Ink.Point.create(p.x, p.y, ts))
                clock = ts + 12
            }
            clock += 80
            inkBuilder.addStroke(sb.build())
        }
        val ink = inkBuilder.build()

        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag) ?: return ""
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()
        return try {
            if (!manager.isModelDownloaded(model).await()) {
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            val result = recognizer.recognize(ink).await()
            result.candidates.firstOrNull()?.text.orEmpty()
        } catch (e: Exception) {
            Timber.w(e, "PanelOcr: recognition failed")
            ""
        }
    }

    /** Printed/image OCR of a bitmap (ML Kit text-recognition, bundled Latin model). */
    suspend fun recognizeImage(bitmap: Bitmap): String = try {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
    } catch (e: Exception) {
        Timber.w(e, "PanelOcr: image recognition failed")
        ""
    }
}
