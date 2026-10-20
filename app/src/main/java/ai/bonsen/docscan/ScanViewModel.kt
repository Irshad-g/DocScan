package ai.bonsen.docscan

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import ai.bonsen.docscan.scan.Cloud
import ai.bonsen.docscan.scan.Cloud.Provider
import ai.bonsen.docscan.scan.Backup
import ai.bonsen.docscan.scan.DocDetector
import ai.bonsen.docscan.scan.Enhancer
import ai.bonsen.docscan.scan.Filter
import ai.bonsen.docscan.scan.ImageIO
import ai.bonsen.docscan.scan.PageItem
import ai.bonsen.docscan.scan.SavedDoc
import ai.bonsen.docscan.scan.Storage
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ScanViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs get() = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

    val pages = mutableStateListOf<PageItem>()
    val previews = mutableStateMapOf<String, Bitmap>()
    private val previewSig = HashMap<String, String>()

    var docs by mutableStateOf<List<SavedDoc>>(emptyList())
        private set
    var pending by mutableStateOf(0)        // pages still being processed
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var lastSaved by mutableStateOf<SavedDoc?>(null)
    var autoCapture by mutableStateOf(true)

    /** Signed-in accounts: email per provider. */
    var accounts by mutableStateOf<Map<Provider, String>>(emptyMap())
        private set
    /** Any signed-in account, or null. */
    val account get() = accounts.values.firstOrNull()
    /** Where new scans upload: one provider, or both when both are signed in and "upload to both" is on. */
    var uploadTargets by mutableStateOf<List<Provider>>(emptyList())
        private set
    /** Chose "Skip" on the sign-in screen; remembered until they sign in. */
    var signInSkipped by mutableStateOf(prefs.getBoolean("skip_sign_in", false))
        private set
    /** False until the cached sign-in has been read, so the login screen does not flash on start. */
    var accountChecked by mutableStateOf(false)
        private set
    /** Folder per provider, e.g. "Bonsen Development › Documents › Invoices". */
    var destinations by mutableStateOf(Provider.entries.associateWith { Cloud.destination(app, it) })
        private set

    private val workDir = File(app.filesDir, "work").apply { mkdirs() }
    private val previewGate = Semaphore(2)   // keep native memory use low when many pages load at once

    init {
        workDir.listFiles()?.forEach { it.delete() }   // leftovers from a previous session
        refreshDocs()
        viewModelScope.launch(Dispatchers.IO) {
            val a = loadAccounts()
            withContext(Dispatchers.Main) { accountChecked = true }
            Cloud.enqueuePending(app)       // also picks up scans saved before sign-in or while offline
            // The cached account lets people in offline; once online, drop it if the tenant no longer accepts it.
            for (p in a.keys) if (!Cloud.stillValid(app, p)) signOut(p)
        }
        // Upload workers write marker files; refresh the list whenever one changes state.
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow(Cloud.TAG).collectLatest { refreshDocs() }
        }
    }

    // ------------------------------------------------------------------ SharePoint / Google Drive backup

    fun skipSignIn() {
        prefs.edit().putBoolean("skip_sign_in", true).apply()
        signInSkipped = true
    }

    /** Re-reads accounts, folders and the upload choice. Blocking; returns the accounts. */
    private suspend fun loadAccounts(): Map<Provider, String> {
        val a = Provider.entries.mapNotNull { p -> runCatching { Cloud.account(app, p) }.getOrNull()?.let { p to it } }.toMap()
        val d = Provider.entries.associateWith { Cloud.destination(app, it) }
        val t = Cloud.uploadTargets(app)
        withContext(Dispatchers.Main) {
            accounts = a
            destinations = d
            uploadTargets = t
        }
        return a
    }

    private fun signedIn(user: String, p: Provider) {
        prefs.edit().remove("skip_sign_in").apply()     // so an expired sign-in asks again
        signInSkipped = false
        accounts = accounts + (p to user)       // leave the sign-in screen right away
        viewModelScope.launch(Dispatchers.IO) {
            Cloud.keepUploadTarget(app, p)
            loadAccounts()
            Cloud.enqueuePending(app)
        }
    }

    /** [launch] shows Google's consent screen; its result goes to [onGoogleResult]. */
    fun signInGoogle(activity: Activity, launch: (IntentSenderRequest) -> Unit) {
        Cloud.googleAuthorize(activity)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else finishGoogle(r)
            }
            .addOnFailureListener { message = "Google sign-in is unavailable: ${it.message}" }
    }

    fun onGoogleResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return      // cancelled
        val r = try { Cloud.googleResult(app, data) } catch (e: Exception) {
            message = "Google sign-in failed: ${e.message}"
            return
        }
        finishGoogle(r)
    }

    private fun finishGoogle(r: AuthorizationResult) {
        val email = Cloud.saveGoogle(app, r)
        if (email == null) message = "Google did not share the account email" else signedIn(email, Provider.GOOGLE)
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Cloud.account(app, Provider.MICROSOFT)      // creates the client off the main thread
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Microsoft sign-in is unavailable: ${e.message}" }
                return@launch
            }
            withContext(Dispatchers.Main) {
                Cloud.signIn(activity) { user, error ->
                    if (user != null) {
                        signedIn(user, Provider.MICROSOFT)
                    } else if (error != null) {
                        message = error
                    }
                }
            }
        }
    }

    fun signOut(p: Provider) {
        viewModelScope.launch(Dispatchers.IO) {
            try { Cloud.signOut(app, p) } catch (e: Exception) { }
            if (loadAccounts().isEmpty()) withContext(Dispatchers.Main) { clearPages() }
        }
    }

    /** Only matters with both accounts signed in. Already backed-up scans are not uploaded again. */
    fun uploadToBoth(on: Boolean) = changeUpload { Cloud.setUploadBoth(app, on) }

    fun uploadOnlyTo(p: Provider) = changeUpload { Cloud.setUploadOnly(app, p) }

    private fun changeUpload(change: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            change()
            loadAccounts()
            Cloud.enqueuePending(app)
        }
    }

    /** [path] runs from the folder browser's start to the chosen folder. */
    fun chooseFolder(path: List<Cloud.Place>) {
        Cloud.setTarget(app, path)
        destinations = Provider.entries.associateWith { Cloud.destination(app, it) }
        retryFailed()
    }

    /** Clears failures (e.g. after fixing the folder or permissions) and queues them again. */
    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            Storage.list(app).filter { it.backup == Backup.FAILED }
                .forEach { Storage.clearBackupError(app, it.name) }
            Cloud.enqueuePending(app)
            withContext(Dispatchers.Main) { refreshDocs() }
        }
    }

    fun refreshDocs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = Storage.list(app)
            withContext(Dispatchers.Main) { docs = list }
        }
    }

    // ------------------------------------------------------------------ adding pages

    /** [file] is a raw camera JPEG; it is deleted once processed. */
    fun addCaptured(file: File) {
        pending++
        viewModelScope.launch(Dispatchers.Default) {
            val ok = try {
                addFile(file)
            } catch (e: Throwable) {
                false
            }
            file.delete()
            withContext(Dispatchers.Main) {
                pending--
                if (!ok) message = "Could not process that photo"
            }
        }
    }

    fun addUris(uris: List<Uri>, onDone: () -> Unit) {
        pending += uris.size
        viewModelScope.launch(Dispatchers.Default) {
            for (u in uris) {
                val tmp = File(app.cacheDir, "imp_${System.nanoTime()}.bin")
                val ok = try {
                    app.contentResolver.openInputStream(u)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    addFile(tmp)
                } catch (e: Throwable) {
                    false
                }
                tmp.delete()
                withContext(Dispatchers.Main) {
                    pending--
                    if (!ok) message = "Could not open one of the images"
                }
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun addFile(src: File): Boolean {
        val id = UUID.randomUUID().toString()
        val dst = File(workDir, "$id.jpg")
        if (!ImageIO.importNormalized(src, dst)) return false
        val corners = DocDetector.detectFile(dst) ?: DocDetector.defaultCorners()
        val item = PageItem(id, dst, corners)
        withContext(Dispatchers.Main) {
            pages.add(item)
            requestPreview(item)
        }
        return true
    }

    // ------------------------------------------------------------------ editing pages

    private fun indexOf(id: String) = pages.indexOfFirst { it.id == id }

    private fun update(id: String, change: (PageItem) -> PageItem) {
        val i = indexOf(id)
        if (i < 0) return
        val updated = change(pages[i])
        pages[i] = updated
        requestPreview(updated)
    }

    fun setFilter(id: String, f: Filter) = update(id) { it.copy(filter = f) }
    fun rotate(id: String) = update(id) { it.copy(rotation = (it.rotation + 90) % 360) }
    fun setCorners(id: String, c: FloatArray) = update(id) { it.copy(corners = c) }

    fun deletePage(id: String) {
        val i = indexOf(id)
        if (i < 0) return
        val p = pages.removeAt(i)
        previews.remove(id)
        previewSig.remove(id)
        p.file.delete()
    }

    fun clearPages() {
        pages.toList().forEach { it.file.delete() }
        pages.clear()
        previews.clear()
        previewSig.clear()
    }

    /** Re-runs auto detection on the stored photo; used by the crop screen's "Auto" button. */
    suspend fun redetect(id: String): FloatArray {
        val p = pages.firstOrNull { it.id == id } ?: return DocDetector.defaultCorners()
        return withContext(Dispatchers.Default) { DocDetector.detectFile(p.file) ?: DocDetector.defaultCorners() }
    }

    private fun requestPreview(p: PageItem) {
        val sig = p.signature
        previewSig[p.id] = sig
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = try {
                previewGate.withPermit { Enhancer.render(p.file, p.corners, p.filter, p.rotation, 1500) }
            } catch (e: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                if (bmp != null && previewSig[p.id] == sig && indexOf(p.id) >= 0) previews[p.id] = bmp
            }
        }
    }

    // ------------------------------------------------------------------ saving

    fun save(name: String, onSaved: () -> Unit) {
        if (pages.isEmpty() || saving) return
        saving = true
        val snapshot = pages.toList()
        viewModelScope.launch(Dispatchers.Default) {
            val doc = try {
                Storage.save(app, name, snapshot.size) { i ->
                    val p = snapshot[i]
                    Enhancer.render(p.file, p.corners, p.filter, p.rotation, 2200)
                }
            } catch (e: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                saving = false
                if (doc != null) {
                    launch(Dispatchers.IO) { Cloud.enqueuePending(app) }
                    lastSaved = doc
                    clearPages()
                    refreshDocs()
                    onSaved()
                } else {
                    message = "Could not create the PDF"
                }
            }
        }
    }

    fun rename(doc: SavedDoc, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Cloud.cancel(app, doc.name)
            Storage.rename(app, doc, newName)
            Cloud.enqueuePending(app)
            withContext(Dispatchers.Main) { refreshDocs() }
        }
    }

    fun delete(doc: SavedDoc) {
        viewModelScope.launch(Dispatchers.IO) {
            Cloud.cancel(app, doc.name)
            Storage.delete(app, doc)
            withContext(Dispatchers.Main) { refreshDocs() }
        }
    }
}
