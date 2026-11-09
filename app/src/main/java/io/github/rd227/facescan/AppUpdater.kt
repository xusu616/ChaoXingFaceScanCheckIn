package io.github.rd227.facescan

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One automatic check per process. Dismissing affects only the current run. */
internal class AppUpdater(
    private val activity: ComponentActivity,
    private val status: TextView,
    private val button: Button
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads = activity.getSystemService(DownloadManager::class.java)
    private var checking = false
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (activity.packageManager.canRequestPackageInstalls()) install() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == prefs.getLong("download", -2)) {
                refreshDownload()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(activity, receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    fun onResume() { refreshDownload() }

    fun check(manual: Boolean = false) {
        if (checking) return
        if (!manual && !automaticCheck.compareAndSet(false, true)) return
        if (refreshDownload()) return
        checking = true
        status.text = "正在检查更新…"
        val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        worker.execute {
            try {
                val release = fetch(current)
                val comparison = ReleaseVersion.compare(release.tag, current)
                activity.runOnUiThread {
                    if (activity.isDestroyed) return@runOnUiThread
                    checking = false
                    when {
                        comparison == null -> status.text = "无法识别版本 ${release.tag}，请联系开发者"
                        comparison <= 0 -> status.text = "已是最新版本 · $current"
                        !manual && prefs.getString("ignored_version", null) == release.tag ->
                            status.text = "已忽略 ${release.tag} · 可手动检查更新"
                        else -> {
                            status.text = "发现新版本 · ${release.tag}"
                            AlertDialog.Builder(activity).setTitle("更新到 ${release.tag}")
                                .setMessage(release.notes.ifBlank { "新版本已发布，是否下载并安装？" }.take(3000))
                                .setNegativeButton("忽略此次更新") { _, _ ->
                                    prefs.edit().putString("ignored_version", release.tag).apply()
                                    status.text = "已忽略 ${release.tag}"
                                }
                                .setNeutralButton("稍后再说", null)
                                .setPositiveButton("下载更新") { _, _ -> download(release) }.show()
                        }
                    }
                }
            } catch (error: IOException) {
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        checking = false
                        status.text = error.message ?: "更新检查失败，可点击重试"
                    }
                }
            }
        }
    }

    private data class Release(val tag: String, val notes: String, val url: String, val id: Long)

    private fun fetch(current: String): Release {
        val githubError = try {
            return fetchFromGitHub(current)
        } catch (error: IOException) {
            error
        } catch (error: Exception) {
            IOException("GitHub 检查失败", error)
        }
        return try {
            fetchFromGitLab(current)
        } catch (gitlabError: IOException) {
            gitlabError.addSuppressed(githubError)
            throw gitlabError
        } catch (gitlabError: Exception) {
            val error = IOException("更新检查失败", gitlabError)
            error.addSuppressed(githubError)
            throw error
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        return connection.inputStream.bufferedReader().use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break
                if (text.length + count > 1024 * 1024) throw IOException("Release 响应过大")
                text.append(buffer, 0, count)
            }
            text.toString()
        }
    }

    private fun fetchFromGitHub(current: String): Release {
        val connection = URL("https://api.github.com/repos/rD227/ChaoXingFaceScanCheckIn/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FaceScan/$current")
            when (connection.responseCode) {
                200 -> Unit
                404 -> throw IOException("暂无正式 Release，可稍后重试")
                403, 429 -> throw IOException("GitHub 请求受限，请稍后重试")
                else -> throw IOException("更新检查失败：HTTP ${connection.responseCode}")
            }
            val body = readResponseBody(connection)
            val json = JSONObject(body)
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) throw IOException("暂无正式 Release")
            val assets = json.getJSONArray("assets")
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.getString("name") != "app-release.apk" || asset.getString("state") != "uploaded") continue
                val url = asset.getString("browser_download_url")
                val uri = Uri.parse(url)
                if (uri.scheme != "https" || uri.host != "github.com" ||
                    !uri.path.orEmpty().startsWith("/rD227/ChaoXingFaceScanCheckIn/releases/download/")) {
                    throw IOException("Release 附件地址无效")
                }
                return Release(json.getString("tag_name"), json.optString("body"), url, asset.getLong("id"))
            }
            throw IOException("最新 Release 尚未上传 app-release.apk")
        } catch (error: JSONException) {
            throw IOException("Release 数据格式无效", error)
        } finally { connection.disconnect() }
    }

    private fun fetchFromGitLab(current: String): Release {
        val connection = URL("https://gitlab.com/api/v4/projects/xusu616%2FChaoXingFaceScanCheckIn/releases?per_page=1")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FaceScan/$current")
            when (connection.responseCode) {
                200 -> Unit
                404 -> throw IOException("暂无正式 Release，可稍后重试")
                403, 429 -> throw IOException("GitLab 请求受限，请稍后重试")
                else -> throw IOException("更新检查失败：HTTP ${connection.responseCode}")
            }
            val body = readResponseBody(connection)
            val releases = JSONArray(body)
            if (releases.length() == 0) throw IOException("暂无正式 Release，可稍后重试")
            val release = releases.getJSONObject(0)
            val tag = release.getString("tag_name")
            val notes = release.optString("description")
            val assets = release.optJSONObject("assets") ?: throw IOException("Release 缺少附件列表")
            val links = assets.optJSONArray("links") ?: throw IOException("Release 缺少附件列表")
            var apkLink: JSONObject? = null
            for (index in 0 until links.length()) {
                val link = links.getJSONObject(index)
                val name = link.optString("name")
                if (name == "app-release.apk") {
                    apkLink = link
                    break
                } else if (name.endsWith(".apk") && apkLink == null) {
                    apkLink = link
                }
            }
            val target = apkLink ?: throw IOException("最新 Release 尚未上传 app-release.apk")
            val url = target.getString("url")
            val uri = Uri.parse(url)
            val isGitLabOrHttps = url.startsWith("https://gitlab.com/") ||
                (uri.scheme == "https" && !uri.host.isNullOrBlank())
            if (!isGitLabOrHttps) {
                throw IOException("Release 附件地址无效")
            }
            val id = target.optLong("id", 1L)
            return Release(tag, notes, url, id)
        } catch (error: JSONException) {
            throw IOException("Release 数据格式无效", error)
        } finally { connection.disconnect() }
    }

    private fun download(release: Release) {
        if (refreshDownload()) return
        val filename = "update-${release.id}.apk"
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("FaceScan ${release.tag}")
            .setDescription("下载完成后返回 FaceScan 安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, filename)
        try {
            val id = downloads.enqueue(request)
            prefs.edit().putLong("download", id).putString("filename", filename).apply()
            status.text = "更新下载中 · 完成后点击安装"
        } catch (error: IllegalArgumentException) { status.text = "无法创建更新下载，请重试" }
          catch (error: IllegalStateException) { status.text = "下载服务不可用，请稍后重试" }
    }

    /** Returns true while an existing download owns the update action. */
    private fun refreshDownload(): Boolean {
        val id = prefs.getLong("download", -1)
        if (id == -1L) return false
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor.moveToFirst()) {
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        status.text = "更新已下载，可以安装"
                        button.text = "安装更新"
                        return true
                    }
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                        status.text = "更新下载中 · 可在通知栏查看进度"
                        button.text = "查看下载状态"
                        return true
                    }
                    DownloadManager.STATUS_FAILED -> status.text = "更新下载失败，点击检查更新重试"
                }
            }
        }
        downloads.remove(id)
        prefs.edit().remove("download").remove("filename").apply()
        button.text = "检查更新"
        return false
    }

    fun action() {
        val id = prefs.getLong("download", -1)
        if (id != -1L) {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL) { install(); return }
            }
        }
        check(true)
    }

    private fun install() {
        val filename = prefs.getString("filename", null) ?: return
        val folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (folder == null) { status.text = "无法访问下载目录，请检查存储状态"; return }
        val file = File(folder, filename)
        val archive = activity.packageManager.getPackageArchiveInfo(file.path, 0)
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (archive == null || archive.packageName != activity.packageName ||
            PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            status.text = "安装包无效或 versionCode 未增加，请重新检查更新"
            downloads.remove(prefs.getLong("download", -1))
            prefs.edit().remove("download").remove("filename").apply()
            button.text = "检查更新"
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("允许安装应用更新")
                .setMessage("请在系统设置中允许 FaceScan 安装更新，返回后继续安装。")
                .setNegativeButton("取消", null).setPositiveButton("去设置") { _, _ ->
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }.show()
            return
        }
        val uri = downloads.getUriForDownloadedFile(prefs.getLong("download", -1)) ?: return
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun close() {
        activity.unregisterReceiver(receiver)
        worker.shutdownNow()
        if (checking) automaticCheck.set(false)
    }

    companion object { private val automaticCheck = AtomicBoolean(false) }
}
