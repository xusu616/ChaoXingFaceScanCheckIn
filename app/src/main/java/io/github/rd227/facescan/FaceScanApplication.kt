package io.github.rd227.facescan

import android.app.Application
import java.io.File

class FaceScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FacePhotoStore(this).clearTemporaryFiles()
        val captures = File(getCacheDir(), "camera-capture").listFiles()
        if (captures != null) for (file in captures) if (!file.delete()) file.deleteOnExit()
    }
}
