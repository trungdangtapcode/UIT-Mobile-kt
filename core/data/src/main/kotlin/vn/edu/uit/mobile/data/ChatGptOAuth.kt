package vn.edu.uit.mobile.data

import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.*

/** Fixed official origins. No credentials from Codex, ChatGPT cookies or another client. */
internal object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val JWKS = "$ISSUER/.well-known/jwks.json"
    const val REVOKE = "$ISSUER/api/accounts/oauth/revoke"
    const val RESOURCE = "https://api.openai.com/v1"
    const val PLAN = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke $PLAN"
    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun query(values: Map<String, String>): String = values.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    fun parameters(query: String): Map<String, String> {
        require(query.length <= 16384)
        val pairs = query.split('&').filter(String::isNotEmpty).map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        return pairs.toMap()
    }
    private fun bytes(value: String) = Base64.getUrlDecoder().decode(value)
    fun identity(jwt: String, jwks: JsonObject, clientId: String, nonce: String?, now: Long): JsonObject {
        require(jwt.length in 20..32768)
        val parts = jwt.split('.')
        require(parts.size == 3)
        val header = Json.parseToJsonElement(bytes(parts[0]).toString(Charsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256" && header["crit"] == null)
        val kid = header.getValue("kid").jsonPrimitive.content
        val jwk = jwks.getValue("keys").jsonArray.map { it.jsonObject }.single { it["kid"]?.jsonPrimitive?.content == kid }
        require(jwk["kty"]?.jsonPrimitive?.content == "RSA")
        require(jwk["use"]?.jsonPrimitive?.content?.let { it == "sig" } != false)
        require(jwk["alg"]?.jsonPrimitive?.content?.let { it == "RS256" } != false)
        val modulus = BigInteger(1, bytes(jwk.getValue("n").jsonPrimitive.content))
        require(modulus.bitLength() in 2048..8192)
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger(1, bytes(jwk.getValue("e").jsonPrimitive.content))))
        require(
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(bytes(parts[2]))
            }
        )
        val claims = Json.parseToJsonElement(bytes(parts[1]).toString(Charsets.UTF_8)).jsonObject
        require(claims.getValue("iss").jsonPrimitive.content == ISSUER)
        val aud = claims.getValue("aud")
        val audiences = if (aud is JsonArray) aud.map { it.jsonPrimitive.content } else listOf(aud.jsonPrimitive.content)
        require(clientId in audiences)
        if (audiences.size > 1 || claims["azp"] != null) require(claims["azp"]?.jsonPrimitive?.content == clientId)
        require(claims.getValue("exp").jsonPrimitive.long > now)
        require(claims["nbf"]?.jsonPrimitive?.long?.let { it <= now + 30 } != false)
        require(claims.getValue("iat").jsonPrimitive.long <= now + 30)
        require(claims.getValue("sub").jsonPrimitive.content.length in 1..300)
        if (nonce != null) require(same(claims.getValue("nonce").jsonPrimitive.content, nonce))
        return claims
    }
}
