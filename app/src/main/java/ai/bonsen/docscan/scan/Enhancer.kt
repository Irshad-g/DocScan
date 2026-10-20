package ai.bonsen.docscan.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The "scanner look": fix perspective, flatten uneven lighting, whiten the paper,
 * boost contrast and keep text sharp. Tuned on shadowed, skewed phone photos.
 */
object Enhancer {

    /** Full pipeline from the stored page image to a final bitmap. */
    fun render(file: File, corners: FloatArray, filter: Filter, rotation: Int, maxSide: Int): Bitmap {
        val src = ImageIO.decode(file, 3200) ?: throw IllegalStateException("Cannot read page image")
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        src.recycle()
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val warped = warp(rgb, corners, maxSide)
        rgb.release()

        val processed = when (filter) {
            Filter.AUTO -> auto(warped)
            Filter.BW -> blackWhite(warped)
            Filter.GRAY -> gray(warped)
            Filter.ORIGINAL -> warped.clone()
        }
        warped.release()

        val rotated = when (((rotation % 360) + 360) % 360) {
            90 -> Mat().also { Core.rotate(processed, it, Core.ROTATE_90_CLOCKWISE) }
            180 -> Mat().also { Core.rotate(processed, it, Core.ROTATE_180) }
            270 -> Mat().also { Core.rotate(processed, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
            else -> processed
        }
        if (rotated !== processed) processed.release()

        val bmp = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rotated, bmp)
        rotated.release()
        return bmp
    }

    // ---------------------------------------------------------------- perspective

    private fun warp(src: Mat, c: FloatArray, maxSide: Int): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val pts = Array(4) { Point(c[it * 2] * w, c[it * 2 + 1] * h) }
        val tl = pts[0]; val tr = pts[1]; val br = pts[2]; val bl = pts[3]

        val topW = hypot(tr.x - tl.x, tr.y - tl.y)
        val botW = hypot(br.x - bl.x, br.y - bl.y)
        val leftH = hypot(bl.x - tl.x, bl.y - tl.y)
        val rightH = hypot(br.x - tr.x, br.y - tr.y)
        var outW = max(topW, botW)
        var outH = max(leftH, rightH)
        val s = min(1.0, maxSide / max(outW, outH))
        outW = max(2.0, (outW * s).roundToInt().toDouble())
        outH = max(2.0, (outH * s).roundToInt().toDouble())

        val srcPts = MatOfPoint2f(*pts)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1, 0.0), Point(outW - 1, outH - 1), Point(0.0, outH - 1)
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(
            src, out, m, Size(outW, outH), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0)
        )
        m.release(); srcPts.release(); dstPts.release()
        return out
    }

    // ---------------------------------------------------------------- lighting

    /** Estimates the lighting: shrink, erase ink with a dilate, smooth, grow back. */
    private fun background(src: Mat): Mat {
        val sc = 256.0 / max(src.cols(), src.rows())
        val small = Mat()
        Imgproc.resize(src, small, Size(), sc, sc, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.dilate(small, small, k)
        Imgproc.medianBlur(small, small, 5)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 6.0)
        val bg = Mat()
        Imgproc.resize(small, bg, Size(src.cols().toDouble(), src.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release(); k.release()
        return bg
    }

    /** Divides out the lighting so the paper becomes uniformly white and shadows disappear. */
    private fun flatten(src: Mat): Mat {
        val bg = background(src)
        val bg32 = Mat()
        val src32 = Mat()
        bg.convertTo(bg32, CvType.CV_32F)
        src.convertTo(src32, CvType.CV_32F)
        bg.release()
        Core.max(bg32, Scalar.all(20.0), bg32)
        val out32 = Mat()
        Core.divide(src32, bg32, out32, 255.0)
        bg32.release(); src32.release()
        val out = Mat()
        out32.convertTo(out, src.type())
        out32.release()
        return out
    }

    private fun lut(lo: Double, hi: Double, gamma: Double): Mat {
        val bytes = ByteArray(256)
        for (i in 0..255) {
            val t = ((i - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            bytes[i] = (t.pow(gamma) * 255.0).roundToInt().toByte()
        }
        val m = Mat(1, 256, CvType.CV_8UC1)
        m.put(0, 0, bytes)
        return m
    }

    private fun applyLut(src: Mat, l: Mat): Mat {
        val out = Mat()
        Core.LUT(src, l, out)
        l.release()
        return out
    }

    private fun sharpen(src: Mat, amount: Double = 0.6): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 1.6)
        val out = Mat()
        Core.addWeighted(src, 1.0 + amount, blur, -amount, 0.0, out)
        blur.release()
        return out
    }

    // ---------------------------------------------------------------- filters

    /** Colour scan: white paper, punchy colours, crisp text. */
    private fun auto(src: Mat): Mat {
        val flat = flatten(src)
        val leveled = applyLut(flat, lut(35.0, 225.0, 1.25))
        flat.release()

        val hsv = Mat()
        Imgproc.cvtColor(leveled, hsv, Imgproc.COLOR_RGB2HSV)
        leveled.release()
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        ch[1].convertTo(ch[1], -1, 1.35, 0.0)
        Core.merge(ch, hsv)
        ch.forEach { it.release() }
        val rgb = Mat()
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()

        val out = sharpen(rgb)
        rgb.release()
        return out
    }

    private fun gray(src: Mat): Mat {
        val flat = flatten(src)
        val g = Mat()
        Imgproc.cvtColor(flat, g, Imgproc.COLOR_RGB2GRAY)
        flat.release()
        val leveled = applyLut(g, lut(35.0, 225.0, 1.25))
        g.release()
        val out = sharpen(leveled)
        leveled.release()
        return out
    }

    /** High-contrast document look. Soft edges are kept so small text stays readable. */
    private fun blackWhite(src: Mat): Mat {
        val flat = flatten(src)
        val g = Mat()
        Imgproc.cvtColor(flat, g, Imgproc.COLOR_RGB2GRAY)
        flat.release()
        val tmp = Mat()
        val t = Imgproc.threshold(g, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        tmp.release()
        val lo = t * 0.62
        val hi = max(lo + 20.0, min(t * 1.25, 250.0))
        val out = applyLut(g, lut(lo, hi, 1.0))
        g.release()
        return out
    }
}
