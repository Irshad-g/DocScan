package ai.bonsen.docscan.scan

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimal PDF writer: one JPEG per page, page size matches the scan's aspect ratio.
 * Writes pages one at a time so memory stays low, and keeps files small (JPEG, not raw pixels).
 */
object PdfWriter {

    private class Out(private val os: OutputStream) {
        var pos = 0L
        fun bytes(b: ByteArray) {
            os.write(b); pos += b.size
        }

        fun text(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
        fun close() {
            os.flush(); os.close()
        }
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    /** [provider] returns the bitmap for page i; it is recycled right after being written. */
    fun write(file: File, count: Int, jpegQuality: Int = 85, provider: (Int) -> Bitmap) {
        require(count > 0)
        val out = Out(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
        // object numbers: 1 catalog, 2 pages, then per page i: 3+3i page, 4+3i contents, 5+3i image
        val offsets = LongArray(3 + 3 * count)

        try {
            out.bytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A)) // %PDF-1.4\n
            out.bytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

            offsets[1] = out.pos
            out.text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val kids = (0 until count).joinToString(" ") { "${3 + 3 * it} 0 R" }
            offsets[2] = out.pos
            out.text("2 0 obj\n<< /Type /Pages /Count $count /Kids [$kids] >>\nendobj\n")

            for (i in 0 until count) {
                val bmp = provider(i)
                val jpg = ByteArrayOutputStream(256 * 1024)
                bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpg)
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                val data = jpg.toByteArray()

                val short = 595.28
                val pw: Double
                val ph: Double
                if (w <= h) {
                    pw = short; ph = short * h / w
                } else {
                    ph = short; pw = short * w / h
                }

                val pageObj = 3 + 3 * i
                val contObj = pageObj + 1
                val imgObj = pageObj + 2

                offsets[pageObj] = out.pos
                out.text(
                    "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                        "/Resources << /XObject << /Im0 $imgObj 0 R >> >> /Contents $contObj 0 R >>\nendobj\n"
                )

                val content = "q ${f(pw)} 0 0 ${f(ph)} 0 0 cm /Im0 Do Q\n"
                offsets[contObj] = out.pos
                out.text("$contObj 0 obj\n<< /Length ${content.length} >>\nstream\n$content" + "endstream\nendobj\n")

                offsets[imgObj] = out.pos
                out.text(
                    "$imgObj 0 obj\n<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${data.size} >>\nstream\n"
                )
                out.bytes(data)
                out.text("\nendstream\nendobj\n")
            }

            val xrefPos = out.pos
            val total = offsets.size
            val sb = StringBuilder()
            sb.append("xref\n0 $total\n")
            sb.append("0000000000 65535 f \n")
            for (n in 1 until total) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
            sb.append("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
            out.text(sb.toString())
        } finally {
            out.close()
        }
    }
}
