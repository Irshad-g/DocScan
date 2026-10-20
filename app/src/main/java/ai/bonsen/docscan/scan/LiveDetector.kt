package ai.bonsen.docscan.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Runs on camera frames: finds the page, smooths the outline for the on-screen overlay,
 * and reports when the page has been held steady long enough to auto-capture.
 */
class LiveDetector(
    private val mainExecutor: Executor,
    /** [waitingForNext]: a page was just auto-captured; no new shot until the page changes. */
    private val onResult: (corners: FloatArray?, readyToCapture: Boolean, waitingForNext: Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 80L
        const val CAPTURE_STABILITY_MS = 750L
        const val STABLE_MOTION_THRESHOLD = 0.035f
        // After an auto-capture: always wait COOLDOWN_MS, then also need a new page in view, meaning the page
        // left the frame for GONE_MS, or the outline sits far (REARM_DISTANCE) from where it was captured.
        const val COOLDOWN_MS = 2500L
        const val GONE_MS = 400L
        const val REARM_DISTANCE = 0.12f
    }

    @Volatile
    var autoEnabled = true

    /** Set when the user taps the shutter, so auto-capture does not grab the same page right after. */
    @Volatile
    var manualCaptureAt = 0L

    private var lastRun = 0L
    private var smooth: FloatArray? = null
    private var stableSince = 0L
    private var needMove = false
    private var capturedAt = 0L
    private var captured: FloatArray? = null
    private var missingSince = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < ANALYSIS_INTERVAL_MS) return
            lastRun = now

            val frame = upright(image)
            val found = DocDetector.detect(frame)
            frame.recycle()

            if (manualCaptureAt > capturedAt) {
                capturedAt = manualCaptureAt
                captured = smooth?.copyOf()
                needMove = true
            }
            val cooling = now - capturedAt < COOLDOWN_MS

            if (found == null || DocDetector.areaFraction(found) < 0.08f) {
                smooth = null
                stableSince = 0
                // A one-frame detection dropout must not count as "page removed".
                if (missingSince == 0L) missingSince = now
                if (!cooling && now - missingSince >= GONE_MS) needMove = false
                val waiting = needMove
                mainExecutor.execute { onResult(null, false, waiting) }
                return
            }
            missingSince = 0L

            val prev = smooth
            val shown = if (prev == null) found else FloatArray(8) { prev[it] * 0.5f + found[it] * 0.5f }
            val moved = if (prev == null) 1f else meanCornerDelta(prev, found)
            smooth = shown

            if (prev == null || moved > STABLE_MOTION_THRESHOLD) stableSince = now
            val shot = captured
            if (!cooling && needMove && shot != null && meanCornerDelta(shot, shown) > REARM_DISTANCE) needMove = false

            val ready = autoEnabled && !cooling && !needMove && stableSince != 0L && now - stableSince >= CAPTURE_STABILITY_MS
            if (ready) {
                needMove = true          // don't capture the same page again until it changes
                capturedAt = now
                captured = shown.copyOf()
                stableSince = now
            }
            val out = shown
            val waiting = needMove
            mainExecutor.execute { onResult(out, ready, waiting) }
        } catch (e: Throwable) {
            // skip this frame
        } finally {
            image.close()
        }
    }

    /** Average per-corner movement so one noisy corner does not restart the steady timer. */
    private fun meanCornerDelta(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        for (corner in 0 until 4) {
            val i = corner * 2
            total += maxOf(abs(a[i] - b[i]), abs(a[i + 1] - b[i + 1]))
        }
        return total / 4f
    }

    /** RGBA frame rotated so it matches what the user sees (portrait). Needs OUTPUT_IMAGE_FORMAT_RGBA_8888. */
    private fun upright(image: ImageProxy): Bitmap {
        val bmp = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
