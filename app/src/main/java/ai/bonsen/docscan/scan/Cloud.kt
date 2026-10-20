package ai.bonsen.docscan.scan

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.bonsen.docscan.R
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Microsoft and/or Google sign-in and automatic upload of saved PDFs to SharePoint or Google Drive.
 * Each account is kept on its own: Google as a stored email, Microsoft in MSAL's cache. With both signed in,
 * scans go to one of them (the default) or to both.
 * Upload state is a marker file next to the PDF: `<name>.up` = backed up, `<name>.err` = gave up (holds the reason),
 * `<name>.up-<provider>` = that provider has its copy (so a retry only redoes the other one).
 */
object Cloud {

    private val SCOPES = listOf("Files.ReadWrite.All", "Sites.ReadWrite.All")
    private const val GRAPH = "https://graph.microsoft.com/v1.0"
    const val TAG = "upload"
    // drive.file only sees files this app created, so uploads go to an app-made folder in My Drive.
    private val GOOGLE_SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive.file"), Scope("email"))
    private const val DRIVE = "https://www.googleapis.com"
    const val DRIVE_FOLDER = "DocScan"

    // ------------------------------------------------------------------ account

    @Volatile private var msal: ISingleAccountPublicClientApplication? = null

    /** Blocking; call off the main thread. */
    @Synchronized
    private fun msal(ctx: Context): ISingleAccountPublicClientApplication =
        msal ?: PublicClientApplication.createSingleAccountPublicClientApplication(ctx.applicationContext, R.raw.msal_config)
            .also { msal = it }

    /** [key] names the provider's prefs and marker files. */
    enum class Provider(val key: String, val service: String) {
        MICROSOFT("ms", "Microsoft (OneDrive / SharePoint)"),
        GOOGLE("google", "Google Drive")
    }

    /** Signed-in email for [p], or null. Blocking. */
    fun account(ctx: Context, p: Provider): String? = when (p) {
        Provider.GOOGLE -> prefs(ctx).getString("google", null)
        Provider.MICROSOFT -> msal(ctx).currentAccount.currentAccount?.username
    }

    /** Blocking. A Microsoft client that cannot start counts as not signed in. */
    private fun signedIn(ctx: Context) = Provider.entries.filter { runCatching { account(ctx, it) }.getOrNull() != null }

    /** Shows the Microsoft login. Call [account] once off the main thread first, so the client already exists. */
    fun signIn(activity: Activity, done: (String?, String?) -> Unit) {
        val params = SignInParameters.builder()
            .withActivity(activity)
            .withScopes(SCOPES)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(r: IAuthenticationResult) {
                    done(r.account.username, null)
                }
                override fun onError(e: MsalException) = done(null, e.message ?: "Sign-in failed")
                override fun onCancel() = done(null, null)
            })
            .build()
        msal(activity).signIn(params)
    }

    private fun googleRequest(email: String?) = AuthorizationRequest.builder()
        .setRequestedScopes(GOOGLE_SCOPES)
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** Starts Google consent. If the result has a resolution, launch its pendingIntent and pass the reply to [googleResult]. */
    fun googleAuthorize(activity: Activity): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(activity).authorize(googleRequest(null))

    /** Throws ApiException when consent failed. */
    fun googleResult(ctx: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)

    /** Stores the Google account and returns its email, or null when Google did not share one. */
    fun saveGoogle(ctx: Context, r: AuthorizationResult): String? {
        val email = r.toGoogleSignInAccount()?.email ?: return null
        prefs(ctx).edit().putString("google", email).apply()
        return email
    }

    /** Blocking. Resets the upload choice, so the next second account starts as "only one" again. */
    fun signOut(ctx: Context, p: Provider) {
        prefs(ctx).edit().remove("target_${p.key}").remove("upload_both").remove("upload_only").apply()
        when (p) {
            Provider.GOOGLE -> prefs(ctx).edit().remove("google").apply()
            Provider.MICROSOFT -> msal(ctx).signOut()
        }
    }

    /**
     * False when the cached sign-in is no longer accepted (account disabled, removed from the tenant, password reset).
     * True when it still works or when that cannot be checked right now (offline). Blocking.
     */
    fun stillValid(ctx: Context, p: Provider): Boolean = try {
        token(ctx, p) != null
    } catch (e: IOException) {
        true
    }

    /** Access token, or null when the user has to sign in (again). Blocking. Network trouble throws IOException. */
    private fun token(ctx: Context, p: Provider): String? = when (p) {
        Provider.GOOGLE -> account(ctx, p)?.let { googleToken(ctx, it) }
        Provider.MICROSOFT -> msToken(ctx)
    }

    private fun googleToken(ctx: Context, email: String): String? = try {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(googleRequest(email)))
        if (r.hasResolution()) null else r.accessToken
    } catch (e: ExecutionException) {
        throw IOException(e.cause ?: e)
    }

    private fun msToken(ctx: Context): String? {
        val app = msal(ctx)
        val acct = app.currentAccount.currentAccount ?: return null
        val p = AcquireTokenSilentParameters.Builder()
            .forAccount(acct)
            .fromAuthority(acct.authority)
            .withScopes(SCOPES)
            .build()
        return try {
            app.acquireTokenSilent(p).accessToken
        } catch (e: MsalUiRequiredException) {
            null
        } catch (e: MsalException) {
            throw IOException(e)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE).also { p ->
        // Before two accounts, one "target" key held the folder of whichever account was signed in.
        val old = p.getString("target", null) ?: return@also
        val owner = if (old.startsWith("$GOOGLE\n")) Provider.GOOGLE else Provider.MICROSOFT
        p.edit().putString("target_${owner.key}", old).remove("target").apply()
    }

    /** Folder picked in the browser; none means the account's default (My OneDrive › DocScan or My Drive › DocScan). Its name is the full path label. */
    private fun target(ctx: Context, p: Provider): Place.Folder? =
        prefs(ctx).getString("target_${p.key}", null)?.split('\n')?.takeIf { it.size == 3 }?.let { Place.Folder(it[0], it[1], it[2]) }

    /** [path] runs from the browser's start to the chosen folder. */
    fun setTarget(ctx: Context, path: List<Place>) {
        val f = path.last() as Place.Folder
        val label = path.filter { it != Place.Sites }.joinToString(" › ") { it.name }
        prefs(ctx).edit().putString("target_${provider(f).key}", "${f.drive}\n${f.item}\n$label").apply()
    }

    /** Where [p]'s uploads go, for display. */
    fun destination(ctx: Context, p: Provider): String =
        target(ctx, p)?.name ?: if (p == Provider.GOOGLE) GOOGLE_ROOT else "My OneDrive › $DRIVE_FOLDER"

    /** Where new scans upload: every signed-in account if "both" is on, otherwise one of them. Blocking. */
    fun uploadTargets(ctx: Context): List<Provider> {
        val all = signedIn(ctx)
        if (all.size < 2 || prefs(ctx).getBoolean("upload_both", false)) return all
        val only = prefs(ctx).getString("upload_only", null)
        return listOf(all.firstOrNull { it.key == only } ?: all.first())
    }

    fun setUploadBoth(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("upload_both", on).apply()

    fun setUploadOnly(ctx: Context, p: Provider) =
        prefs(ctx).edit().putString("upload_only", p.key).putBoolean("upload_both", false).apply()

    /** When [p] signs in next to an existing account, scans keep going to the existing one until the user changes it. Blocking. */
    fun keepUploadTarget(ctx: Context, p: Provider) {
        if (prefs(ctx).contains("upload_only")) return
        signedIn(ctx).firstOrNull { it != p }?.let { setUploadOnly(ctx, it) }
    }

    // ------------------------------------------------------------------ folder browser

    sealed interface Place {
        val name: String
        /** Microsoft start: the user's OneDrive and the SharePoint sites they follow. */
        object Sites : Place { override val name = "OneDrive & SharePoint" }
        data class Site(val id: String, override val name: String) : Place
        /** [drive] is a Graph drive id, "me" for the user's OneDrive, or [GOOGLE]. */
        data class Folder(val drive: String, val item: String, override val name: String) : Place
    }

    const val GOOGLE = "google"
    private const val GOOGLE_ROOT = "My Drive › $DRIVE_FOLDER"
    private const val FOLDER_TYPE = "application/vnd.google-apps.folder"

    private fun driveBase(drive: String) = if (drive == "me") "$GRAPH/me/drive" else "$GRAPH/drives/$drive"

    private fun provider(place: Place) =
        if (place is Place.Folder && place.drive == GOOGLE) Provider.GOOGLE else Provider.MICROSOFT

    private fun authToken(ctx: Context, p: Provider) = token(ctx, p) ?: throw IOException("Sign in again to browse folders")

    private fun <T> JSONObject.items(key: String = "value", f: (JSONObject) -> T?): List<T> =
        getJSONArray(key).let { a -> (0 until a.length()).mapNotNull { f(a.getJSONObject(it)) } }

    private fun site(o: JSONObject) = Place.Site(o.getString("id"), o.optString("displayName", o.optString("name")))

    /** Where browsing starts. Blocking. */
    fun browseRoot(ctx: Context, p: Provider): Place =
        if (p == Provider.GOOGLE) Place.Folder(GOOGLE, googleRoot(authToken(ctx, p)), GOOGLE_ROOT) else Place.Sites

    /** What can be opened inside [place]. Blocking. */
    // ponytail: first page only (up to 999 / Drive's 100); add paging if someone has more subfolders than that.
    fun browse(ctx: Context, place: Place): List<Place> {
        val t = authToken(ctx, provider(place))
        return when (place) {
            Place.Sites -> listOf(Place.Folder("me", "root", "My OneDrive")) +
                request(t, "GET", "$GRAPH/me/followedSites?\$select=id,displayName").items { site(it) }
            is Place.Site -> request(t, "GET", "$GRAPH/sites/${place.id}/drives?\$select=id,name")
                .items { Place.Folder(it.getString("id"), "root", it.getString("name")) }
            is Place.Folder -> if (place.drive == GOOGLE) {
                val q = enc("'${place.item}' in parents and mimeType='$FOLDER_TYPE' and trashed=false")
                request(t, "GET", "$DRIVE/drive/v3/files?q=$q&orderBy=name&fields=files(id,name)")
                    .items("files") { Place.Folder(GOOGLE, it.getString("id"), it.getString("name")) }
            } else {
                request(t, "GET", "${driveBase(place.drive)}/items/${place.item}/children?\$select=id,name,folder&\$top=999")
                    .items { if (it.has("folder")) Place.Folder(place.drive, it.getString("id"), it.getString("name")) else null }
                    .sortedBy { it.name.lowercase() }
            }
        }
    }

    /** SharePoint sites matching [query], for sites the user does not follow. Blocking. */
    fun searchSites(ctx: Context, query: String): List<Place> =
        request(authToken(ctx, Provider.MICROSOFT), "GET", "$GRAPH/sites?search=${enc(query)}&\$select=id,displayName").items { site(it) }

    /** Blocking. */
    fun createFolder(ctx: Context, parent: Place.Folder, name: String): Place.Folder {
        val t = authToken(ctx, provider(parent))
        return if (parent.drive == GOOGLE) {
            val body = JSONObject().put("name", name).put("mimeType", FOLDER_TYPE).put("parents", org.json.JSONArray().put(parent.item))
            Place.Folder(GOOGLE, postJson(t, "$DRIVE/drive/v3/files?fields=id", body).getString("id"), name)
        } else {
            val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "rename")
            val r = postJson(t, "${driveBase(parent.drive)}/items/${parent.item}/children", body)
            Place.Folder(parent.drive, r.getString("id"), r.getString("name"))
        }
    }

    // ------------------------------------------------------------------ queue

    /** Queues every saved PDF that is not backed up yet. Safe to call often: already-queued work is kept. */
    fun enqueuePending(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        for (doc in Storage.list(ctx)) {
            if (doc.backup != Backup.PENDING) continue
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("name" to doc.name))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork("upload:${doc.name}", ExistingWorkPolicy.KEEP, req)
        }
    }

    fun cancel(ctx: Context, name: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork("upload:$name")
    }

    // ------------------------------------------------------------------ Graph

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    private fun encPath(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    private fun request(
        token: String, method: String, url: String,
        type: String? = null, length: Long = 0, write: ((OutputStream) -> Unit)? = null
    ): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (write != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(length)
                c.outputStream.use(write)
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw HttpError(code, msg ?: "HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun postJson(token: String, url: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray()
        return request(token, "POST", url, "application/json", bytes.size.toLong()) { it.write(bytes) }
    }

    /** Uploads one PDF to OneDrive/SharePoint. Returns normally on success. */
    private fun uploadSharePoint(ctx: Context, token: String, pdf: File) {
        val t = target(ctx, Provider.MICROSOFT)
        val dest = if (t != null) "${driveBase(t.drive)}/items/${t.item}:/${enc(pdf.name)}"
            else "$GRAPH/me/drive/root:/${encPath("$DRIVE_FOLDER/${pdf.name}")}"
        // ponytail: simple upload caps at 250 MB; switch to an upload session if scans ever get that big.
        // "rename" never overwrites a colleague's file of the same name; the cost is a rare duplicate
        // when a retry follows an upload whose response was lost.
        request(token, "PUT", "$dest:/content?@microsoft.graph.conflictBehavior=rename",
            "application/pdf", pdf.length()) { os -> pdf.inputStream().use { it.copyTo(os) } }
    }

    /** The app's own folder in My Drive (drive.file only sees what the app created), made on first use. */
    private fun googleRoot(token: String): String {
        // ponytail: one lookup per call instead of caching the id, so a deleted folder is simply recreated.
        val q = enc("name='$DRIVE_FOLDER' and mimeType='$FOLDER_TYPE' and 'root' in parents and trashed=false")
        val found = request(token, "GET", "$DRIVE/drive/v3/files?q=$q&fields=files(id)").getJSONArray("files")
        return if (found.length() > 0) found.getJSONObject(0).getString("id")
        else postJson(token, "$DRIVE/drive/v3/files?fields=id", JSONObject().put("name", DRIVE_FOLDER).put("mimeType", FOLDER_TYPE)).getString("id")
    }

    /** Uploads one PDF to the chosen Drive folder, or the app's root folder. */
    private fun uploadDrive(ctx: Context, token: String, pdf: File) {
        val folder = target(ctx, Provider.GOOGLE)?.item ?: googleRoot(token)
        val b = "docscan-" + UUID.randomUUID()
        val meta = JSONObject().put("name", pdf.name).put("parents", org.json.JSONArray().put(folder))
        val head = "--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: application/pdf\r\n\r\n".toByteArray()
        val tail = "\r\n--$b--\r\n".toByteArray()
        // ponytail: Google recommends resumable uploads above 5 MB; switch to uploadType=resumable if large scans fail.
        request(token, "POST", "$DRIVE/upload/drive/v3/files?uploadType=multipart&fields=id",
            "multipart/related; boundary=$b", head.size + pdf.length() + tail.size) { os ->
            os.write(head)
            pdf.inputStream().use { it.copyTo(os) }
            os.write(tail)
        }
    }

    class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val name = inputData.getString("name") ?: return Result.failure()
            val pdf = Storage.pdfFile(ctx, name)
            if (!pdf.exists()) return Result.success()      // deleted or renamed meanwhile
            return try {
                // Not signed in: stop quietly; signing in re-queues everything pending.
                val targets = uploadTargets(ctx).ifEmpty { return Result.failure() }
                for (p in targets) {
                    if (Storage.uploadedTo(ctx, name, p.key)) continue
                    val token = token(ctx, p) ?: return Result.failure()
                    if (p == Provider.GOOGLE) uploadDrive(ctx, token, pdf) else uploadSharePoint(ctx, token, pdf)
                    Storage.markUploadedTo(ctx, name, p.key)
                }
                Storage.markBackup(ctx, name, null)
                Result.success()
            } catch (e: IOException) {
                Result.retry()
            } catch (e: HttpError) {
                when {
                    e.code == 401 || e.code == 429 || e.code >= 500 -> Result.retry()
                    else -> {
                        Storage.markBackup(ctx, name, e.message ?: "Upload failed")
                        Result.failure()
                    }
                }
            } catch (e: Exception) {
                Storage.markBackup(ctx, name, e.message ?: "Upload failed")
                Result.failure()
            }
        }
    }
}
