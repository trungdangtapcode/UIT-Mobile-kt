package vn.edu.uit.mobile.common

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object MoodleUrlResolver {
    fun resolve(baseUrl: String, path: String): String = preserve(baseUrl, URI(baseUrl.trimEnd('/') + "/").resolve(path))
    fun redirect(baseUrl: String, current: String, location: String): String = preserve(baseUrl, URI(current).resolve(location))
    private fun preserve(baseUrl: String, target: URI): String {
        val base = URI(baseUrl)
        val prefix = base.path.orEmpty().trimEnd('/')
        return if (origin(base) == origin(target) && prefix.isNotEmpty() && target.path != prefix && !target.path.startsWith("$prefix/")) {
            URI(target.scheme, target.userInfo, target.host, target.port, prefix + target.path, target.query, target.fragment).toASCIIString()
        } else {
            target.toASCIIString()
        }
    }
    fun origin(uri: URI): String = "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}:${if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port}"
}
object UrlSanitizer {
    private val secret = Regex("token|sesskey|password|auth|secret|credential|^(?:key|signature|sig|ticket|session)$", RegexOption.IGNORE_CASE)
    fun credentialFreeUrl(value: String): String? = runCatching {
        val url = URI(value)
        require(url.scheme in setOf("http", "https") && url.host != null)
        var path = url.rawPath
        if (path.contains("/tokenpluginfile.php")) {
            val suffix = path.substringAfter("/tokenpluginfile.php")
            require(suffix.isEmpty() || Regex("^/[a-zA-Z0-9]+/\\d+/.*").matches(suffix))
            path = path.substringBefore("/tokenpluginfile.php") + "/pluginfile.php" + suffix.replaceFirst(Regex("^/[^/]+"), "")
        }
        val query = url.rawQuery?.split('&')?.filterNot { secret.containsMatchIn(URLDecoder.decode(it.substringBefore('='), "UTF-8")) }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        val authority = if (url.port < 0) url.host else "${url.host}:${url.port}"
        "${url.scheme}://$authority$path" + (query?.let { "?$it" } ?: "")
    }.getOrNull()
}
fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
