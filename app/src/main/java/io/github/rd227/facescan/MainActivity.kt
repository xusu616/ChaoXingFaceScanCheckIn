package io.github.rd227.facescan

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.net.Uri
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
import android.widget.ImageView
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
    private var photoPreview: ImageView? = null
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

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photos = FacePhotoStore(this)
        CookieManager.getInstance().setAcceptCookie(true)
        val scroll = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setBackgroundColor(Ui.BACKGROUND)
        val content = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        Ui.edgeInsets(this, content, 20, 20)

        content.addView(Ui.text(this, "ANDROID · 本机运行", 12, Ui.BLUE, true))
        content.addView(Ui.text(this, "FaceScan", 30, Ui.INK, true))
        content.addView(Ui.text(this, "保存本人照片，扫描课堂二维码。", 15, Ui.MUTED, false))

        val accountCard = Ui.card(this)
        accountCard.addView(Ui.text(this, "01  账号会话", 18, Ui.INK, true))
        accountStatus = Ui.text(this, "尚未检查登录状态", 14, Ui.MUTED, false)
        accountStatus!!.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
        accountCard.addView(accountStatus)
        val login = Ui.button(this, "在超星官方页面登录", true)
        login.setOnClickListener(View.OnClickListener { view: View? ->
            openOfficial(
                OfficialUrl.parse(
                    LOGIN_URL
                ), true
            )
        })
        accountCard.addView(login)
        refreshButton = Ui.button(this, "检查本机登录会话", false)
        refreshButton!!.setOnClickListener(View.OnClickListener { view: View? -> checkSession() })
        accountCard.addView(refreshButton)
        content.addView(accountCard)

        val qrCard = Ui.card(this)
        qrCard.addView(Ui.text(this, "02  二维码签到", 18, Ui.INK, true))
        qrStatus = Ui.text(this, "扫码后先确认活动，再打开官方签到页。", 14, Ui.MUTED, false)
        qrStatus!!.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
        qrCard.addView(qrStatus)
        val scan = Ui.button(this, "扫描课堂二维码", true)
        scan.setOnClickListener(View.OnClickListener { view: View? ->
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描超星官方课堂二维码")
                .setBeepEnabled(false).setOrientationLocked(false)
            scanLauncher.launch(options)
        })
        qrCard.addView(scan)
        val paste = Ui.button(this, "粘贴签到链接", false)
        paste.setOnClickListener(View.OnClickListener { view: View? -> pasteLink() })
        qrCard.addView(paste)
        openQrButton = Ui.button(this, "打开官方签到页", false)
        openQrButton!!.setEnabled(false)
        openQrButton!!.setOnClickListener(View.OnClickListener { view: View? ->
            if (pendingQr != null) openOfficial(
                pendingQr!!,
                false
            )
        })
        qrCard.addView(openQrButton)
        content.addView(qrCard)

        val photoCard = Ui.card(this)
        photoCard.addView(Ui.text(this, "03  本人人脸照片", 18, Ui.INK, true))
        photoStatus = Ui.text(this, "还没有保存照片", 14, Ui.MUTED, false)
        photoCard.addView(photoStatus)
        photoPreview = ImageView(this)
        photoPreview!!.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        photoPreview!!.setContentDescription("本机保存的人脸照片预览")
        photoPreview!!.setBackground(Ui.rounded(this, Ui.BACKGROUND, 14))
        photoCard.addView(photoPreview, LinearLayout.LayoutParams(-1, Ui.dp(this, 190)))
        val choose = Ui.button(this, "选择并保存本人照片", false)
        choose.setOnClickListener(View.OnClickListener { view: View? ->
            photoLauncher.launch(arrayOf("image/*"))
        })
        photoCard.addView(choose)
        photoCard.addView(
            Ui.text(
                this,
                "照片加密保存在本机。官方页面允许选图时，可在上传菜单中选用；本机保存不代表官方人脸验证已通过。",
                12,
                Ui.MUTED,
                false
            )
        )
        content.addView(photoCard)

        val info = Ui.card(this)
        info.addView(Ui.text(this, "0.1 实验版", 16, Ui.INK, true))
        info.addView(
            Ui.text(
                this,
                "不连接本项目服务器。账号登录、验证和签到由超星官方服务处理。官方页面要求实时拍摄时，仍需完成相应操作。",
                13,
                Ui.MUTED,
                false
            )
        )
        val clear = Ui.button(this, "清除本机照片与登录状态", false)
        clear.setOnClickListener(View.OnClickListener { view: View? -> confirmClear() })
        info.addView(clear)
        content.addView(info)
    }

    override fun onResume() {
        super.onResume()
        loadPreview()
    }

    private fun loadPreview() {
        if (!photos!!.exists()) {
            photoPreview!!.setImageDrawable(null)
            photoStatus!!.setText("还没有保存照片")
            return
        }
        worker.execute(Runnable {
            try {
                val bitmap = photos!!.preview()
                runOnUiThread(Runnable updatePreview@{
                    if (isDestroyed()) {
                        bitmap.recycle()
                        return@updatePreview
                    }
                    photoPreview!!.setImageBitmap(bitmap)
                    photoStatus!!.setText("已保存到本机 · 尚不代表官方验证通过")
                })
            } catch (exception: Exception) {
                ui(Runnable { photoStatus!!.setText("照片无法解密，请重新选择。") })
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

    private fun savePhoto(source: Uri) {
        if (photoBusy) return
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
                                photoPreview!!.setImageDrawable(null)
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
