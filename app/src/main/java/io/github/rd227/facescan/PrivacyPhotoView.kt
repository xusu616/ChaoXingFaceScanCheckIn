package io.github.rd227.facescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Preview-only privacy effect. The encrypted photo and upload bytes stay unchanged. */
internal class PrivacyPhotoView(context: Context) : FrameLayout(context) {
    private val image = ImageView(context)
    private val hint = TextView(context)
    private var original: Bitmap? = null
    private var blurred: Bitmap? = null
    private var hidden = true

    init {
        background = Ui.rounded(context, Ui.BACKGROUND, 18)
        clipToOutline = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(-1, -1))
        hint.gravity = Gravity.CENTER
        hint.textSize = 13f
        hint.setTextColor(Ui.INK)
        hint.setPadding(12, 12, 12, 12)
        hint.background = Ui.rounded(context, 0xE6FFFFFF.toInt(), 12)
        val params = LayoutParams(-2, -2, Gravity.CENTER)
        addView(hint, params)
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
        render()
    }

    fun setPhoto(bitmap: Bitmap?, legacyBlur: Bitmap? = null) {
        image.setImageDrawable(null)
        original?.recycle()
        blurred?.recycle()
        original = bitmap
        blurred = legacyBlur
        hidden = true
        render()
    }

    fun hidePhoto() { hidden = true; render() }
    fun toggle() { if (original != null) { hidden = !hidden; render() } }

    private fun render() {
        image.setImageBitmap(if (hidden && Build.VERSION.SDK_INT < 31) blurred else original)
        if (Build.VERSION.SDK_INT >= 31) {
            image.setRenderEffect(if (hidden && original != null)
                RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null)
        }
        hint.text = when {
            original == null -> "尚未添加照片 · 支持拍照或相册"
            hidden -> "照片已模糊 · 点击查看"
            else -> "点击重新模糊"
        }
        contentDescription = hint.text
        hint.layoutParams = LayoutParams(-2, -2,
            if (original != null && !hidden) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        ).apply { bottomMargin = Ui.dp(context, 12) }
    }

    companion object {
        /** Small, CPU-blurred bitmap for Android 8–11; called on the photo worker. */
        fun legacyBlur(source: Bitmap): Bitmap? {
            if (Build.VERSION.SDK_INT >= 31) return null
            val ratio = 64f / maxOf(source.width, source.height)
            val width = maxOf(1, (source.width * ratio).toInt())
            val height = maxOf(1, (source.height * ratio).toInt())
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            var pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            repeat(3) {
                val output = IntArray(pixels.size)
                for (y in 0 until height) for (x in 0 until width) {
                    var red = 0; var green = 0; var blue = 0; var count = 0
                    for (dy in -2..2) for (dx in -2..2) {
                        val px = x + dx; val py = y + dy
                        if (px in 0 until width && py in 0 until height) {
                            val color = pixels[py * width + px]
                            red += color shr 16 and 255
                            green += color shr 8 and 255
                            blue += color and 255
                            count++
                        }
                    }
                    output[y * width + x] = (255 shl 24) or ((red / count) shl 16) or
                        ((green / count) shl 8) or (blue / count)
                }
                pixels = output
            }
            val result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            if (small !== source) small.recycle()
            return result
        }
    }
}
