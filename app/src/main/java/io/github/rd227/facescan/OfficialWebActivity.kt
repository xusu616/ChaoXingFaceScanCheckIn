package io.github.rd227.facescan

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Official pages run in a separate WebView with no JavaScript-to-native bridge.  */
class OfficialWebActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var web: WebView? = null
    private var status: TextView? = null
    private var photos: FacePhotoStore? = null
    private var fileCallback: ValueCallback<Array<Uri?>?>? = null
    private var pendingCamera: Runnable? = null
    private var pendingCameraDenied: Runnable? = null
    private var browserPermission: PermissionRequest? = null
    private var cameraUri: Uri? = null
    private var cameraFile: File? = null

    private val browserBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            web!!.goBack()
        }
    }
    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var selected = FileChooserParams.parseResult(result.resultCode, result.data)
        if (selected != null) for (uri in selected) {
            if (uri == null || "content" != uri.scheme) {
                selected = null
                break
            }
        }
        finishFile(selected)
    }
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = cameraUri
        val photo = cameraFile
        finishFile(
            if (result.resultCode == RESULT_OK && uri != null && photo != null && photo.length() > 0)
                arrayOf<Uri?>(uri)
            else
                null
        )
        cameraUri = null
    }
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = if (granted) pendingCamera else pendingCameraDenied
        pendingCamera = null
        pendingCameraDenied = null
        action?.run()
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target: URI
        try {
            target = OfficialUrl.parse(getIntent().getStringExtra("url"))
        } catch (exception: IllegalArgumentException) {
            finish()
            return
        }
        photos = FacePhotoStore(this)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(Ui.BACKGROUND)
        setContentView(root)
        Ui.edgeInsets(this, root, 0, 0)
        val toolbar = LinearLayout(this)
        toolbar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8))
        toolbar.setOrientation(LinearLayout.HORIZONTAL)
        status = Ui.text(this, "超星官方页面", 13, Ui.INK, true)
        toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        val done = Ui.button(this, "完成", false)
        toolbar.addView(done, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 46)))
        done.setOnClickListener(View.OnClickListener { view: View? -> finish() })
        root.addView(toolbar)

        web = WebView(this)
        onBackPressedDispatcher.addCallback(this, browserBackCallback)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val settings = web!!.getSettings()
        settings.setJavaScriptEnabled(true)
        settings.setDomStorageEnabled(true)
        settings.setAllowFileAccess(false)
        settings.setAllowContentAccess(true)
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        settings.setMediaPlaybackRequiresUserGesture(true)
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web!!.setWebViewClient(object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                browserBackCallback.isEnabled = view.canGoBack()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!OfficialUrl.isAllowed(url)) {
                    view.stopLoading()
                    status!!.setText("此页面暂不支持在 App 内打开")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val raw = request.getUrl().toString()
                try {
                    val safe = OfficialUrl.parse(raw).toASCIIString()
                    if (safe != raw) {
                        view.loadUrl(safe)
                        return true
                    }
                    return false
                } catch (exception: IllegalArgumentException) {
                    toast("App 内仅打开超星官方网页；此跳转暂不支持。")
                    return true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (OfficialUrl.isAllowed(url)) status!!.setText(Uri.parse(url).getHost())
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError?
            ) {
                if (request.isForMainFrame()) status!!.setText("页面未加载，请检查网络后重试")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError?
            ) {
                handler.cancel()
                status!!.setText("证书验证失败，已停止加载")
            }
        })
        web!!.setWebChromeClient(object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri?>?>,
                params: FileChooserParams
            ): Boolean {
                finishFile(null)
                if (!OfficialUrl.isAllowed(view.getUrl())) {
                    callback.onReceiveValue(null)
                    return true
                }
                fileCallback = callback
                var image = params.getAcceptTypes().size == 0
                for (type in params.getAcceptTypes()) {
                    if (type.isEmpty() || type.startsWith("image/")) image = true
                }
                if (!image) {
                    launchPicker(params)
                    return true
                }
                if (params.isCaptureEnabled()) {
                    capturePhoto()
                    return true
                }
                val labels: MutableList<String?> = ArrayList<String?>()
                val actions: MutableList<Runnable?> = ArrayList<Runnable?>()
                if (photos!!.exists()) {
                    labels.add("使用本机保存的本人照片")
                    actions.add(Runnable {
                        worker.execute(Runnable {
                            try {
                                val photo = photos!!.exportForUpload()
                                runOnUiThread(Runnable {
                                    if (!isDestroyed() && OfficialUrl.isAllowed(web!!.getUrl())) finishFile(
                                        arrayOf<Uri?>(photo)
                                    )
                                    else finishFile(null)
                                })
                            } catch (exception: Exception) {
                                runOnUiThread(Runnable {
                                    finishFile(null)
                                    if (!isDestroyed()) toast("无法读取本机照片，请重新选择。")
                                })
                            }
                        })
                    })
                }
                labels.add("拍摄照片")
                actions.add(Runnable { capturePhoto() })
                labels.add("从相册选择")
                actions.add(Runnable { launchPicker(params) })
                AlertDialog.Builder(this@OfficialWebActivity).setTitle("选择上传图片")
                    .setItems(
                        labels.toTypedArray<String?>(),
                        DialogInterface.OnClickListener { dialog: DialogInterface?, index: Int ->
                            actions.get(index)!!.run()
                        })
                    .setOnCancelListener(DialogInterface.OnCancelListener { dialog: DialogInterface? ->
                        finishFile(
                            null
                        )
                    }).show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread(Runnable handlePermission@{
                    var video = false
                    for (resource in request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) video = true
                    }
                    if (!video || !OfficialUrl.isAllowed(
                            request.getOrigin().toString()
                        ) || !OfficialUrl.isAllowed(web!!.getUrl())
                    ) {
                        request.deny()
                        return@handlePermission
                    }
                    if (browserPermission != null) {
                        request.deny()
                        return@handlePermission
                    }
                    browserPermission = request
                    withCamera(Runnable {
                        if (browserPermission === request) {
                            browserPermission = null
                            request.grant(arrayOf<String>(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        }
                    }, Runnable {
                        if (browserPermission === request) {
                            browserPermission = null
                            request.deny()
                        }
                    })
                })
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (browserPermission === request) {
                    browserPermission = null
                    pendingCamera = null
                    pendingCameraDenied = null
                }
            }
        })
        if (savedInstanceState == null || web!!.restoreState(savedInstanceState) == null) {
            web!!.loadUrl(target.toASCIIString())
        }
        browserBackCallback.isEnabled = web!!.canGoBack()
    }

    private fun launchPicker(params: FileChooserParams) {
        try {
            pickerLauncher.launch(params.createIntent())
        } catch (exception: Exception) {
            toast("没有可用的文件选择器。")
            finishFile(null)
        }
    }

    private fun capturePhoto() {
        withCamera(Runnable {
            try {
                val folder = File(getCacheDir(), "camera-capture")
                check(!(!folder.isDirectory() && !folder.mkdirs()))
                cameraFile = File(folder, UUID.randomUUID().toString() + ".jpg")
                cameraUri =
                    FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile!!)
                val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                capture.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                captureLauncher.launch(capture)
            } catch (exception: Exception) {
                toast("无法打开相机。")
                finishFile(null)
            }
        }, Runnable {
            toast("未获得相机权限。")
            finishFile(null)
        })
    }

    private fun withCamera(granted: Runnable, denied: Runnable) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted.run()
        } else if (pendingCamera != null) {
            denied.run()
        } else {
            pendingCamera = granted
            pendingCameraDenied = denied
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun finishFile(value: Array<Uri?>?) {
        val callback = fileCallback
        fileCallback = null
        if (callback != null) callback.onReceiveValue(value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (web != null) web!!.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (web != null) web!!.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (web != null) web!!.onResume()
    }

    override fun onDestroy() {
        finishFile(null)
        if (browserPermission != null) browserPermission!!.deny()
        pendingCamera = null
        pendingCameraDenied = null
        worker.shutdownNow()
        if (web != null) {
            web!!.stopLoading()
            web!!.destroy()
        }
        if (photos != null) photos!!.clearTemporaryFiles()
        val captures = File(getCacheDir(), "camera-capture").listFiles()
        if (captures != null) for (file in captures) if (!file.delete()) file.deleteOnExit()
        super.onDestroy()
    }

    private fun toast(message: String?) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
