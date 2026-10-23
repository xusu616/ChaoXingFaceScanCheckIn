package io.github.rd227.facescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max

/** One private, encrypted photo. An upload copy is created only on an explicit chooser action.  */
class FacePhotoStore(context: Context) {
    private val context: Context
    private val photo: File

    init {
        this.context = context.getApplicationContext()
        photo = File(this.context.getFilesDir(), "face-photo.enc")
    }

    fun exists(): Boolean {
        return photo.isFile()
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun save(source: Uri) {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        open(source).use { input -> BitmapFactory.decodeStream(input, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("无法读取这张图片。")
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1440) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        val bitmap: Bitmap?
        open(source).use { input -> bitmap = BitmapFactory.decodeStream(input, null, options) }
        if (bitmap == null) throw IOException("图片解码失败。")
        var oriented: Bitmap = bitmap
        try {
            val transform = Matrix()
            try {
                open(source).use { input ->
                    val orientation = ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            transform.setRotate(90f)
                            transform.postScale(-1f, 1f)
                        }

                        ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            transform.setRotate(-90f)
                            transform.postScale(-1f, 1f)
                        }

                        ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90f)
                        else -> {}
                    }
                }
            } catch (ignored: IOException) { /* PNG and other images may not contain EXIF. */
            }
            if (!transform.isIdentity()) oriented = Bitmap.createBitmap(
                bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), transform, true
            )
            val jpeg: ByteArray?
            ByteArrayOutputStream().use { output ->
                if (!oriented.compress(
                        Bitmap.CompressFormat.JPEG,
                        90,
                        output
                    )
                ) throw IOException("图片压缩失败。")
                jpeg = output.toByteArray()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(jpeg)
            val file = AtomicFile(photo)
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.write(cipher.getIV())
                output.writeInt(encrypted.size)
                output.write(encrypted)
                output.flush()
                file.finishWrite(stream)
            } catch (exception: IOException) {
                file.failWrite(stream)
                throw exception
            }
        } finally {
            if (oriented != bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun preview(): Bitmap {
        val jpeg = decrypt()
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) throw IOException("本机照片无法读取，请重新选择。")
        return bitmap
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun exportForUpload(): Uri? {
        val jpeg = decrypt()
        val folder = File(context.getCacheDir(), "photo-share")
        if (!folder.isDirectory() && !folder.mkdirs()) throw IOException("无法创建临时图片。")
        val copy = File(folder, "saved-face.jpg")
        FileOutputStream(copy).use { output -> output.write(jpeg) }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", copy)
    }

    fun clearTemporaryFiles() {
        val folder = File(context.getCacheDir(), "photo-share")
        val files = folder.listFiles()
        if (files != null) for (file in files) if (!file.delete()) file.deleteOnExit()
    }

    @Throws(IOException::class)
    fun delete() {
        AtomicFile(photo).delete()
        clearTemporaryFiles()
        if (photo.exists()) throw IOException("照片删除失败，请重试。")
    }

    @Throws(IOException::class)
    private fun open(source: Uri): InputStream {
        val input = context.getContentResolver().openInputStream(source)
        if (input == null) throw IOException("无法打开这张照片。")
        return input
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun decrypt(): ByteArray {
        DataInputStream(AtomicFile(photo).openRead()).use { input ->
            if (input.readInt() != MAGIC) throw IOException("本机照片格式无效。")
            val iv = ByteArray(12)
            input.readFully(iv)
            val size = input.readInt()
            if (size < 16 || size > 8 * 1024 * 1024) throw IOException("本机照片大小无效。")
            val encrypted = ByteArray(size)
            input.readFully(encrypted)
            if (input.read() != -1) throw IOException("本机照片格式无效。")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted)
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun key(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(KEY_ALIAS)) return store.getKey(KEY_ALIAS, null) as SecretKey?
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "facescan.photo.v1"
        private const val MAGIC = 0x46535031
    }
}
