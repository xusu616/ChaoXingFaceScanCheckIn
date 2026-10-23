package io.github.rd227.facescan

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var photos: FacePhotoStore? = null
    private var accountStatus: TextView? = null
    private var photoStatus: TextView? = null
    private var qrStatus: TextView? = null
    private var photoPreview: PrivacyPhotoView? = null
    private lateinit var updater: AppUpdater
    private var cameraFile: File? = null
    private var previewGeneration = 0
    private var refreshButton: Button? = null
    private var openQrButton: Button? = null
    private var pendingQr: URI? = null
    private var checking = false
    private var clearingSession = false
    private var photoBusy = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) acceptQr(contents)
    }
    private val photoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) savePhoto(source)
    }
    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkSession()
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = cameraFile
        if (success && capture != null) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", capture)
            savePhoto(uri, capture)
        } else {
            capture?.delete()
        }
        cameraFile = null
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else toast("未获得相机权限，可从相册选择照片。")
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photos = FacePhotoStore(this)
        cameraFile = savedInstanceState?.getString("photoCapture")?.let { File(cacheDir, "profile-camera/$it") }
        File(cacheDir, "profile-camera").listFiles()?.forEach { file ->
            if (file != cameraFile) file.delete()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BACKGROUND)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        Ui.edgeInsets(this, content, 20, 20)
        content.addView(Ui.text(this, "FACESCAN  /  课堂助手", 11, Ui.BLUE, true))
        content.addView(Ui.text(this, "你好，准备好上课了吗", 25, Ui.INK, true))
        content.addView(Ui.text(this, "扫码进入官方页面，照片与会话留在本机。", 13, Ui.MUTED, false))

        val account = Ui.card(this)
        account.addView(Ui.text(this, "账号与会话", 17, Ui.INK, true))
        accountStatus = Ui.text(this, "登录后，可在此确认会话状态", 13, Ui.MUTED, false)
        accountStatus!!.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        account.addView(accountStatus)
        val login = Ui.button(this, "官方登录", true).apply {
            setOnClickListener { openOfficial(OfficialUrl.parse(LOGIN_URL), true) }
        }
        refreshButton = Ui.button(this, "检查会话", false).apply { setOnClickListener { checkSession() } }
        account.addView(Ui.actions(this, login, refreshButton!!))
        content.addView(account)

        val qr = Ui.card(this)
        qr.addView(Ui.text(this, "课堂签到", 17, Ui.INK, true))
        qrStatus = Ui.text(this, "先扫描二维码或粘贴链接，再确认活动。", 13, Ui.MUTED, false)
        qrStatus!!.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        qr.addView(qrStatus)
        val scan = Ui.button(this, "扫描二维码", true).apply {
            Ui.icon(this, android.R.drawable.ic_menu_camera)
            setOnClickListener {
                scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描超星官方课堂二维码").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        val paste = Ui.button(this, "粘贴链接", false).apply { setOnClickListener { pasteLink() } }
        qr.addView(Ui.actions(this, scan, paste))
        openQrButton = Ui.button(this, "确认并打开官方页面  →", false).apply {
            isEnabled = false
            setOnClickListener { pendingQr?.let { openOfficial(it, false) } }
        }
        qr.addView(openQrButton)
        content.addView(qr)

        val photo = Ui.card(this)
        photo.addView(Ui.text(this, "我的照片", 17, Ui.INK, true))
        photoStatus = Ui.text(this, "本机加密保存 · 预览默认模糊", 12, Ui.MUTED, false)
        photo.addView(photoStatus)
        photoPreview = PrivacyPhotoView(this)
        photo.addView(photoPreview, LinearLayout.LayoutParams(-1, Ui.dp(this, 180)).apply {
            topMargin = Ui.dp(this@MainActivity, 10)
        })
        val capture = Ui.button(this, "拍摄照片", true).apply {
            Ui.icon(this, android.R.drawable.ic_menu_camera)
            setOnClickListener { takePhoto() }
        }
        val choose = Ui.button(this, "相册选择", false).apply {
            Ui.icon(this, android.R.drawable.ic_menu_gallery)
            setOnClickListener { photoLauncher.launch(arrayOf("image/*")) }
        }
        photo.addView(Ui.actions(this, capture, choose))
        val privacy = Ui.button(this, "切换照片模糊", false).apply { setOnClickListener { photoPreview!!.toggle() } }
        photo.addView(privacy)
        photo.addView(Ui.text(this, "模糊仅用于预览，不改变上传照片。保存照片不代表已通过官方人脸验证。", 11, Ui.MUTED, false))
        content.addView(photo)

        val settings = Ui.card(this)
        settings.addView(Ui.text(this, "应用与本机资料", 17, Ui.INK, true))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val updateStatus = Ui.text(this, "当前版本 $version", 12, Ui.MUTED, false)
        updateStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        settings.addView(updateStatus)
        val update = Ui.button(this, "检查更新", false).apply { setOnClickListener { updater.action() } }
        updater = AppUpdater(this, updateStatus, update)
        val clear = Ui.button(this, "清除资料", false).apply { setOnClickListener { confirmClear() } }
        settings.addView(Ui.actions(this, update, clear))
        settings.addView(Ui.text(this, "登录、验证与签到由超星官方处理。", 11, Ui.MUTED, false))
        content.addView(settings)
        updater.check()
    }

    private fun takePhoto() {
        if (photoBusy || cameraFile != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val folder = File(cacheDir, "profile-camera")
        if (!folder.isDirectory && !folder.mkdirs()) { toast("无法创建拍照临时文件。"); return }
        val capture = File(folder, "${UUID.randomUUID()}.jpg")
        cameraFile = capture
        try {
            cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.files", capture))
        } catch (error: android.content.ActivityNotFoundException) {
            cameraFile = null
            capture.delete()
            toast("没有可用的相机应用，请从相册选择。")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("photoCapture", cameraFile?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        photoPreview!!.hidePhoto()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        loadPreview()
        updater.onResume()
    }

    private fun loadPreview() {
        val generation = ++previewGeneration
        if (!photos!!.exists()) {
            photoPreview!!.setPhoto(null)
            photoStatus!!.setText("还没有保存照片")
            return
        }
        worker.execute(Runnable {
            try {
                val bitmap = photos!!.preview()
                val blurred = PrivacyPhotoView.legacyBlur(bitmap)
                runOnUiThread(Runnable updatePreview@{
                    if (isDestroyed() || generation != previewGeneration) {
                        blurred?.recycle()
                        bitmap.recycle()
                        return@updatePreview
                    }
                    photoPreview!!.setPhoto(bitmap, blurred)
                    photoStatus!!.setText("已保存到本机 · 尚不代表官方验证通过")
                })
            } catch (exception: Exception) {
                ui(Runnable {
                    if (generation == previewGeneration) photoStatus!!.setText("照片无法解密，请重新选择。")
                })
            }
        })
    }

    private fun checkSession() {
        if (checking || clearingSession) return
        checking = true
        refreshButton!!.setEnabled(false)
        accountStatus!!.setText("正在通过手机网络检查官方会话…")
        val generation: Long = SESSION_EPOCH.get()
        val userAgent = WebSettings.getDefaultUserAgent(this)
        val client = OfficialHttp(
            WebSessionCookies(
                CookieManager.getInstance(),
                SESSION_EPOCH
            ), userAgent
        )
        worker.execute(Runnable {
            var result: String
            try {
                val response = client.get(ACCOUNT_URL)
                val `object` = JSONObject(response.body)
                val account = `object`.optJSONObject("msg")
                val uid = if (account == null) "" else account.optString("uid", "")
                if (response.status == 200 && uid.matches("[1-9][0-9]*".toRegex())) {
                    result = "已确认登录：" + account!!.optString("name", "当前用户")
                } else {
                    result = "尚未确认登录，请先完成官方网页登录。"
                }
            } catch (exception: Exception) {
                result = "未能读取官方会话；请确认网络与网页登录状态。"
            }
            val message = result
            ui(Runnable {
                checking = false
                refreshButton!!.setEnabled(!clearingSession)
                if (generation == SESSION_EPOCH.get()) accountStatus!!.setText(message)
            })
        })
    }

    private fun pasteLink() {
        val input = EditText(this)
        input.setHint("https://mobilelearn.chaoxing.com/…")
        input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input.setMaxLines(5)
        val padding = Ui.dp(this, 20)
        input.setPadding(padding, padding, padding, padding)
        val dialog = AlertDialog.Builder(this).setTitle("粘贴超星官方链接")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("解析", null).create()
        dialog.setOnShowListener(OnShowListener { ignored: DialogInterface? ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                View.OnClickListener { view: View? ->
                    if (acceptQr(input.getText().toString())) dialog.dismiss()
                    else input.setError("请检查超星官方链接格式")
                })
        })
        dialog.show()
    }

    private fun acceptQr(value: String?): Boolean {
        try {
            val parsed = OfficialUrl.parse(value)
            val activity = OfficialUrl.activityId(parsed)
            pendingQr = parsed
            qrStatus!!.setText(
                "来源：" + parsed.getHost() + (if (activity.isEmpty())
                    "\n未提取到活动编号，请在官方页面确认。"
                else
                    "\n活动编号：" + activity)
            )
            openQrButton!!.setEnabled(true)
            toast("链接已解析，尚未提交签到。")
            return true
        } catch (exception: IllegalArgumentException) {
            toast("这不是受支持的超星官方签到链接。")
            return false
        }
    }

    private fun openOfficial(uri: URI, login: Boolean) {
        val intent = Intent(this, OfficialWebActivity::class.java)
        intent.putExtra("url", uri.toASCIIString())
        if (login) loginLauncher.launch(intent)
        else startActivity(intent)
    }

    private fun savePhoto(source: Uri, temporary: File? = null) {
        if (photoBusy) { temporary?.delete(); return }
        photoBusy = true
        photoStatus!!.setText("正在压缩并加密保存…")
        worker.execute(Runnable {
            try {
                photos!!.save(source)
                ui(Runnable {
                    photoBusy = false
                    loadPreview()
                    toast("照片已保存到本机。")
                })
            } catch (exception: Exception) {
                ui(Runnable {
                    photoBusy = false
                    photoStatus!!.setText("照片保存失败，请换一张图片重试。")
                })
            } finally {
                temporary?.delete()
            }
        })
    }

    private fun confirmClear() {
        if (photoBusy) {
            toast("请等照片保存完成。")
            return
        }
        AlertDialog.Builder(this).setTitle("清除本机资料？")
            .setMessage("将删除本机保存的照片与登录状态。不会修改超星服务器上的资料或签到记录。")
            .setNegativeButton("取消", null).setPositiveButton(
                "清除",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    ++previewGeneration
                    clearingSession = true
                    refreshButton!!.setEnabled(false)
                    synchronized(SESSION_EPOCH) { SESSION_EPOCH.incrementAndGet() }
                    CookieManager.getInstance()
                        .removeAllCookies(ValueCallback { removed: Boolean? ->
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            clearingSession = false
                            refreshButton!!.setEnabled(!checking)
                            accountStatus!!.setText("本机登录状态已清除")
                        })
                    worker.execute(Runnable {
                        try {
                            photos!!.delete()
                            ui(Runnable {
                                photoPreview!!.setPhoto(null)
                                photoStatus!!.setText("还没有保存照片")
                            })
                        } catch (exception: Exception) {
                            ui(Runnable { toast("照片删除失败，请重试。") })
                        }
                    })
                    pendingQr = null
                    qrStatus!!.setText("扫码后先确认活动，再打开官方签到页。")
                    openQrButton!!.setEnabled(false)
                }).show()
    }

    private fun ui(action: Runnable) {
        runOnUiThread(Runnable { if (!isFinishing() && !isDestroyed()) action.run() })
    }

    private fun toast(message: String?) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        updater.close()
        photoPreview!!.setPhoto(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        val SESSION_EPOCH: AtomicLong = AtomicLong()
        private const val ACCOUNT_URL = "https://sso.chaoxing.com/apis/login/userLogin4Uname.do"
        private const val LOGIN_URL =
            "https://passport2.chaoxing.com/login?refer=https%3A%2F%2Fi.chaoxing.com"
    }
}
