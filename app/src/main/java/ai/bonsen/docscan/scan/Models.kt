package ai.bonsen.docscan.scan

import java.io.File

enum class Filter(val label: String) {
    AUTO("Auto"),
    BW("B&W"),
    GRAY("Grayscale"),
    ORIGINAL("Original")
}

/**
 * One page in the scan being built.
 * [file] is an upright, normalized JPEG. [corners] are 8 floats in 0..1: tl, tr, br, bl (x,y each).
 */
class PageItem(
    val id: String,
    val file: File,
    val corners: FloatArray,
    val filter: Filter = Filter.AUTO,
    val rotation: Int = 0
) {
    fun copy(
        corners: FloatArray = this.corners,
        filter: Filter = this.filter,
        rotation: Int = this.rotation
    ) = PageItem(id, file, corners, filter, rotation)

    val signature: String
        get() = corners.joinToString(",") + "|" + filter.name + "|" + rotation
}

enum class Backup { PENDING, DONE, FAILED }

class SavedDoc(
    val name: String,
    val pdf: File,
    val thumb: File?,
    val pages: Int,
    val modified: Long,
    val backup: Backup = Backup.PENDING,
    val backupError: String? = null
)
