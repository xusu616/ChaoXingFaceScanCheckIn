package io.github.rd227.facescan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** Native, bounded HTTP transport. Cookies never pass through JavaScript.  */
class OfficialHttp internal constructor(
    private val cookies: Cookies,
    private val userAgent: String?,
    private val connections: Connections
) {
    interface Cookies {
        fun get(url: String?): String?
        fun set(url: String?, value: String?)
        fun flush()
    }

    internal fun interface Connections {
        @Throws(IOException::class)
        fun open(uri: URI): HttpURLConnection
    }

    class Response internal constructor(@JvmField val status: Int, @JvmField val body: String)

    constructor(cookies: Cookies, userAgent: String?) : this(
        cookies,
        userAgent,
        OfficialHttp.Connections { uri ->
            uri.toURL().openConnection() as HttpURLConnection
        })

    @Throws(IOException::class)
    fun get(url: String?): Response {
        var target = OfficialUrl.parse(url)
        for (redirects in 0..5) {
            val connection = connections.open(target)
            try {
                connection.setInstanceFollowRedirects(false)
                connection.setConnectTimeout(15000)
                connection.setReadTimeout(20000)
                connection.setRequestMethod("GET")
                connection.setRequestProperty("Accept", "application/json, text/html;q=0.8")
                connection.setRequestProperty("User-Agent", userAgent)
                val cookie = cookies.get(target.toString())
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty(
                    "Cookie",
                    cookie
                )
                val status = connection.getResponseCode()
                for (header in connection.getHeaderFields().entries) {
                    if (header.key != null && header.key.equals("Set-Cookie", ignoreCase = true)) {
                        for (value in header.value) cookies.set(target.toString(), value)
                    }
                }
                cookies.flush()
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    val location = connection.getHeaderField("Location")
                    if (location == null) throw IOException("官方接口返回了无效跳转。")
                    val next: URI
                    try {
                        val redirect = target.resolve(location)
                        if (!"https".equals(redirect.getScheme(), ignoreCase = true)) {
                            throw IOException("已停止不安全的网络跳转。")
                        }
                        next = OfficialUrl.parse(redirect.toString())
                    } catch (exception: IllegalArgumentException) {
                        throw IOException("已停止访问非超星的跳转地址。")
                    }
                    target = next
                    continue
                }
                val stream =
                    if (status >= 400) connection.getErrorStream() else connection.getInputStream()
                if (stream == null) return Response(status, "")
                stream.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while ((input.read(buffer).also { count = it }) != -1) {
                            if (output.size() + count > MAX_BYTES) throw IOException("官方接口响应过大。")
                            output.write(buffer, 0, count)
                        }
                        return Response(
                            status,
                            String(output.toByteArray(), StandardCharsets.UTF_8)
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("官方接口跳转过多，请重新登录。")
    }

    companion object {
        private val MAX_BYTES = 2 * 1024 * 1024
    }
}
