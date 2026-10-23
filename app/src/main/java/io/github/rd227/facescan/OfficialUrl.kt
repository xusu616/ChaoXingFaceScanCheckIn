package io.github.rd227.facescan

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** Validates untrusted scanner, clipboard and redirect input before any network access.  */
object OfficialUrl {
    @JvmStatic
    fun parse(input: String?): URI {
        if (input == null || input.length > 8192) throw invalid()
        try {
            var uri = URI(input.trim { it <= ' ' })
            val scheme = uri.getScheme()
            if (!"https".equals(scheme, ignoreCase = true) && !"http".equals(
                    scheme,
                    ignoreCase = true
                )
            ) throw invalid()
            var host = uri.getHost()
            if (host == null || uri.getRawUserInfo() != null) throw invalid()
            host = host.lowercase()
            if (host != "chaoxing.com" && !host.endsWith(".chaoxing.com")) throw invalid()
            val port = uri.getPort()
            if (port != -1 && port != (if (scheme.equals(
                        "https",
                        ignoreCase = true
                    )
                ) 443 else 80)
            ) throw invalid()
            // Legacy QR codes use HTTP. Preserve the raw query, including its signature.
            if (scheme.equals("http", ignoreCase = true)) {
                uri = URI(
                    ("https://" + host
                            + (if (uri.getRawPath() == null) "" else uri.getRawPath())
                            + (if (uri.getRawQuery() == null) "" else "?" + uri.getRawQuery())
                            + (if (uri.getRawFragment() == null) "" else "#" + uri.getRawFragment()))
                )
            }
            return uri
        } catch (exception: URISyntaxException) {
            throw invalid()
        }
    }

    @JvmStatic
    fun isAllowed(value: String?): Boolean {
        try {
            parse(value)
            return true
        } catch (exception: IllegalArgumentException) {
            return false
        }
    }

    @JvmStatic
    fun activityId(uri: URI): String {
        val query: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        val raw = uri.getRawQuery()
        if (raw == null) return ""
        for (part in raw.split("&".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            val pair: Array<String?> = part.split("=".toRegex(), limit = 2).toTypedArray()
            val key = decode(pair[0])
            if ((key != "activeId") && (key != "aid") && (key != "id")) continue
            val value = if (pair.size == 2) decode(pair[1]) else ""
            if (query.containsKey(key) && query.get(key) != value) throw invalid()
            query.put(key, value)
        }
        for (key in arrayOf<String>("activeId", "aid", "id")) {
            val value = query.get(key)
            if (value != null && value.matches("[0-9]{1,24}".toRegex())) return value
        }
        return ""
    }

    private fun invalid(): IllegalArgumentException {
        return IllegalArgumentException("请使用有效的超星官方 HTTPS 链接；不支持第三方短链接。")
    }

    private fun decode(value: String?): String {
        try {
            return URLDecoder.decode(value, "UTF-8")
        } catch (impossible: UnsupportedEncodingException) {
            throw AssertionError(impossible)
        }
    }
}
