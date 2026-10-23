package vn.edu.uit.mobile.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.SessionStore
import vn.edu.uit.mobile.model.*

private val Context.sessionDataStore by preferencesDataStore("uit_sessions")
class EncryptedSessionVault(context: Context) : SessionStore {
    private val store = context.applicationContext.sessionDataStore
    private val entry = stringPreferencesKey("encrypted_sessions_v1")
    override val sessions: Flow<List<PortalSession>> = store.data.map { prefs -> readSessions(prefs[entry]) }
    override suspend fun save(sessions: List<PortalSession>) {
        store.edit { prefs ->
            val old = readSessions(prefs[entry])
            prefs[entry] = encrypt(encode((old + sessions).associateBy { it.account.id }.values.toList()))
        }
    }
    override suspend fun remove(accountId: String) {
        store.edit { prefs -> prefs[entry] = encrypt(encode(readSessions(prefs[entry]).filterNot { it.account.id == accountId })) }
    }
    private fun readSessions(value: String?): List<PortalSession> = try {
        value?.let { decode(decrypt(it)) }.orEmpty()
    } catch (e: java.security.GeneralSecurityException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    } catch (e: kotlinx.serialization.SerializationException) {
        emptyList()
    }
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keyStore.getKey("uit.session.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder("uit.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
            )
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    private fun encode(sessions: List<PortalSession>): String = JsonArray(
        sessions.map { session ->
            buildJsonObject {
                put("id", session.account.id)
                put("baseUrl", session.account.baseUrl)
                put("userId", session.account.userId)
                put("displayName", session.account.displayName)
                put("portal", session.account.portal.name)
                put("cookie", session.cookie)
                put("sesskey", session.sesskey)
            }
        }
    ).toString()
    private fun decode(value: String): List<PortalSession> = Json.parseToJsonElement(value).jsonArray.map { element ->
        val o = element.jsonObject
        fun s(key: String) = o.getValue(key).jsonPrimitive.content
        PortalSession(Account(s("id"), s("baseUrl"), s("userId").toLong(), s("displayName"), PortalType.valueOf(s("portal"))), s("cookie"), s("sesskey"))
    }
}
