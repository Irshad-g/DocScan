package ai.bonsen.docscan.scan

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds the page quadrilateral in a photo with DocCornerNet (MobileNetV2 0.35, 256 px, MIT,
 * github.com/mapo80/DocCornerNet-CoordClass). Returns normalized corners (tl,tr,br,bl) or null.
 */
object DocDetector {

    private const val SIZE = 256
    private const val MIN_SCORE = 0.5f
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    private lateinit var interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val output = Array(1) { FloatArray(9) }

    fun init(context: Context) {
        val bytes = context.assets.open("doccorner256.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    fun defaultCorners(): FloatArray = floatArrayOf(0.04f, 0.04f, 0.96f, 0.04f, 0.96f, 0.96f, 0.04f, 0.96f)

    fun detectFile(file: File): FloatArray? {
        val bmp = ImageIO.decode(file, 900) ?: return null
        return try { detect(bmp) } finally { bmp.recycle() }
    }

    /** [bitmap] must be upright. The model sees it stretched to a square, so corners map back directly. */
    @Synchronized
    fun detect(bitmap: Bitmap): FloatArray? {
        val square = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (square !== bitmap) square.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) / 255f - MEAN[0]) / STD[0])
            input.putFloat(((p shr 8 and 0xFF) / 255f - MEAN[1]) / STD[1])
            input.putFloat(((p and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        input.rewind()
        interpreter.run(input, output)
        val y = output[0]
        if (y[8] < MIN_SCORE) return null
        return FloatArray(8) { y[it].coerceIn(0f, 1f) }
    }

    /** Area of a normalized quad as a fraction of the frame (shoelace). */
    fun areaFraction(c: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
        }
        return kotlin.math.abs(s) / 2f
    }
}
