package com.lagradost.cloudstream3.utils
//Credits https://github.com/ArjixWasTaken/CloudStream-3/blob/master/app/src/main/java/com/ArjixWasTaken/cloudstream3/utils/HttpSession.kt
import com.lagradost.cloudstream3.animeproviders.KrunchyProvider
import khttp.responses.Response
import khttp.structures.authorization.Authorization
import khttp.structures.cookie.Cookie
import khttp.structures.cookie.CookieJar
import khttp.structures.files.FileLike
import java.util.ArrayList

/**
 * An HTTP session manager.
 *
 * This class simply keeps cookies across requests.
 *
 * @property sessionCookies A cookie jar.
 */
class HttpSession {
    companion object {
        const val DEFAULT_TIMEOUT = 30.0

        fun mergeCookies(cookie1: CookieJar, cookie2: Map<String, String>?): Map<String, String> {
            val a = cookie1
            if (!cookie2.isNullOrEmpty()) {
                a.putAll(cookie2)
            }
            return a
        }
    }

    val sessionCookies = CookieJar()

    fun get(
        url: String,
        headers: Map<String, String?> = mapOf(),
        params: Map<String, String> = mapOf(),
        data: Any? = null,
        json: Any? = null,
        auth: Authorization? = null,
        cookies: Map<String, String>? = null,
        timeout: Double = DEFAULT_TIMEOUT,
        allowRedirects: Boolean? = null,
        stream: Boolean = false,
        files: List<FileLike> = listOf(),
    ): Response {
        val res =
            khttp.get(
                url,
                headers,
                params,
                data,
                json,
                auth,
                mergeCookies(sessionCookies, cookies),
                timeout,
                allowRedirects,
                stream,
                files
            )
        sessionCookies.putAll(res.cookies)
        sessionCookies.putAll(
            CookieJar(
                *res.headers
                    .filter { it.key.toLowerCase() == "set-cookie" }
                    .map { Cookie(it.value) }
                    .toTypedArray()
            )
        )
        return res
    }

    fun post(
        url: String,
        headers: Map<String, String?> = mapOf(),
        params: Map<String, String> = mapOf(),
        data: Any? = null,
        json: Any? = null,
        auth: Authorization? = null,
        cookies: Map<String, String>? = null,
        timeout: Double = DEFAULT_TIMEOUT,
        allowRedirects: Boolean? = null,
        stream: Boolean = false,
        files: List<FileLike> = listOf()
    ): Response {
        val res =
            khttp.post(
                url,
                headers,
                params,
                data,
                json,
                auth,
                mergeCookies(sessionCookies, cookies),
                timeout,
                allowRedirects,
                stream,
                files
            )
        sessionCookies.putAll(res.cookies)
        sessionCookies.putAll(
            CookieJar(
                *res.headers
                    .filter { it.key.toLowerCase() == "set-cookie" }
                    .map { Cookie(it.value) }
                    .toTypedArray()
            )
        )
        return res
    }
}