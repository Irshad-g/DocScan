package ai.bonsen.docscan.scan

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Saved PDFs live in the app's private library; a copy is also placed in Downloads/DocScan. */
object Storage {

    const val PUBLIC_FOLDER = "Downloads/DocScan"

    private fun dir(ctx: Context): File = File(ctx.filesDir, "scans").apply { mkdirs() }

    private fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().trim('.')
        return s.ifEmpty { "Scan" }.take(80)
    }

    private fun uniqueName(ctx: Context, wanted: String, ignore: String? = null): String {
        val base = sanitize(wanted)
        var n = base
        var i = 2
        while (n != ignore && File(dir(ctx), "$n.pdf").exists()) {
            n = "$base ($i)"; i++
        }
        return n
    }

    fun list(ctx: Context): List<SavedDoc> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".pdf") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { pdf ->
            val base = pdf.name.removeSuffix(".pdf")
            val thumb = File(dir(ctx), "$base.jpg").takeIf { it.exists() }
            val pages = try {
                File(dir(ctx), "$base.meta").readText().trim().toInt()
            } catch (e: Exception) {
                1
            }
            val err = File(dir(ctx), "$base.err").takeIf { it.exists() }?.readText()
            val backup = when {
                File(dir(ctx), "$base.up").exists() -> Backup.DONE
                err != null -> Backup.FAILED
                else -> Backup.PENDING
            }
            SavedDoc(base, pdf, thumb, pages, pdf.lastModified(), backup, err)
        }
    }

    fun pdfFile(ctx: Context, name: String) = File(dir(ctx), "$name.pdf")

    /** Records the upload result: [error] null = backed up, otherwise the reason it failed. */
    fun markBackup(ctx: Context, name: String, error: String?) {
        val up = File(dir(ctx), "$name.up")
        val err = File(dir(ctx), "$name.err")
        if (error == null) {
            up.writeText("")
            err.delete()
        } else {
            err.writeText(error)
        }
    }

    /** True once [provider] (a [Cloud.Provider.key]) has this scan, while uploading to more than one. */
    fun uploadedTo(ctx: Context, name: String, provider: String) = File(dir(ctx), "$name.up-$provider").exists()

    fun markUploadedTo(ctx: Context, name: String, provider: String) = File(dir(ctx), "$name.up-$provider").writeText("")

    /** Clears a failure so the next queue pass tries again. */
    fun clearBackupError(ctx: Context, name: String) {
        File(dir(ctx), "$name.err").delete()
    }

    /** Renders and writes the PDF page by page (low memory). */
    fun save(ctx: Context, wantedName: String, count: Int, provider: (Int) -> Bitmap): SavedDoc {
        val name = uniqueName(ctx, wantedName)
        val pdf = File(dir(ctx), "$name.pdf")
        val thumb = File(dir(ctx), "$name.jpg")

        PdfWriter.write(pdf, count) { i ->
            val b = provider(i)
            if (i == 0) saveThumb(b, thumb)
            b
        }
        File(dir(ctx), "$name.meta").writeText(count.toString())
        copyToDownloads(ctx, pdf, name)
        return SavedDoc(name, pdf, thumb.takeIf { it.exists() }, count, pdf.lastModified())
    }

    private fun saveThumb(b: Bitmap, dst: File) {
        try {
            val s = 360f / maxOf(b.width, b.height)
            val t = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
            FileOutputStream(dst).use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            t.recycle()
        } catch (e: Exception) {
            // thumbnail is optional
        }
    }

    private fun copyToDownloads(ctx: Context, pdf: File, name: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocScan")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { os -> pdf.inputStream().use { it.copyTo(os) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // The in-app copy is the source of truth; a missing public copy is not fatal.
        }
    }

    /** Files kept next to each PDF. */
    private val SIDE_FILES = listOf("jpg", "meta", "up", "err") + Cloud.Provider.entries.map { "up-${it.key}" }

    fun delete(ctx: Context, doc: SavedDoc) {
        doc.pdf.delete()
        SIDE_FILES.forEach { File(dir(ctx), "${doc.name}.$it").delete() }
    }

    fun rename(ctx: Context, doc: SavedDoc, newName: String): Boolean {
        val target = uniqueName(ctx, newName, ignore = doc.name)
        if (target == doc.name) return true
        val d = dir(ctx)
        val ok = doc.pdf.renameTo(File(d, "$target.pdf"))
        // Backup state follows the local file; the cloud copy keeps its original name.
        if (ok) SIDE_FILES.forEach { File(d, "${doc.name}.$it").renameTo(File(d, "$target.$it")) }
        return ok
    }

    private fun uriFor(ctx: Context, pdf: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", pdf)

    /** Opens the Android share sheet (WhatsApp, Gmail, Drive, ...). */
    fun share(ctx: Context, doc: SavedDoc) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, doc.pdf))
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun open(ctx: Context, doc: SavedDoc) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, doc.pdf), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(view)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No PDF viewer installed. Use Share instead.", Toast.LENGTH_LONG).show()
        }
    }
}
