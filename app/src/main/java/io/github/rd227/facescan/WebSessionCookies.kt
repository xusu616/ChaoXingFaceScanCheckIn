package io.github.rd227.facescan

import android.webkit.CookieManager
import io.github.rd227.facescan.OfficialHttp.Cookies
import java.util.concurrent.atomic.AtomicLong

/** Invalidates in-flight cookie writes when the user signs out.  */
internal class WebSessionCookies(
    private val manager: CookieManager,
    private val epoch: AtomicLong
) : Cookies {
    private val generation: Long

    init {
        generation = epoch.get()
    }

    override fun get(url: String?): String? {
        return if (epoch.get() == generation) manager.getCookie(url) else ""
    }

    override fun set(url: String?, value: String?) {
        synchronized(epoch) {
            if (epoch.get() == generation) manager.setCookie(url, value)
        }
    }

    override fun flush() {
        manager.flush()
    }
}
