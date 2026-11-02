package ua.hramota.data

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * "Locked storage": the document memory, shelves, covers, kept copies and the recent list sealed
 * with a data key. The data key is kept wrapped by a key in the phone's secure hardware (Android
 * Keystore) that works only right after the owner's fingerprint, face or screen lock — every time.
 * While unlocked, the data key lives only in memory; it is dropped when Hramota is locked.
 */
object Vault {
    private const val ALIAS = "hramota-vault"
    private const val FILE = "vault.key"
    const val SUFFIX = ".hrv"

    @Volatile private var key: SecretKey? = null
    private val openState = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = openState.asStateFlow()

    fun isOn(context: Context) = File(context.filesDir, FILE).exists()

    // While turning it off, files are written plain although the key file is still there
    @Volatile private var unsealing = false

    /** New files are written sealed now. */
    fun sealing(context: Context) = isOn(context) && !unsealing

    /** The data key while unlocked; null when locked (or the storage is not locked at all). */
    fun key(): SecretKey? = key

    /** True when files must be read and written sealed but cannot be now. */
    fun locked(context: Context) = isOn(context) && key == null && !unsealing

    fun canUse(context: Context) = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun lock(context: Context) {
        if (key == null) return
        key = null
        openState.value = false
        Library.get(context).reload()
        // Opened copies are plain only while being read
        File(context.cacheDir, "open").deleteRecursively()
    }

    /** Asks for the fingerprint, face or screen lock and opens the storage. */
    suspend fun unlock(activity: Activity, title: String, subtitle: String): Boolean {
        if (!isOn(activity)) return true
        if (key != null) return true
        val stored = withContext(Dispatchers.IO) { AtomicFile(File(activity.filesDir, FILE)).readFully() }
        val buf = ByteBuffer.wrap(stored)
        if (stored.size < 13 || buf.get() != VERSION) return false
        val iv = ByteArray(12).also { buf.get(it) }
        val wrapped = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, kek() ?: return false, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) { return false }
        val authed = prompt(activity, cipher, title, subtitle) ?: return false
        val raw = try { authed.doFinal(wrapped) } catch (e: Exception) { return false }
        key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        openState.value = true
        withContext(Dispatchers.IO) { Library.get(activity).reload() }
        return true
    }

    /**
     * Turns locked storage on: a new hardware key (usable only after authentication), a new data key
     * wrapped by it, then every remembered file sealed. Plain files are removed once sealed.
     */
    suspend fun enable(activity: Activity, title: String, subtitle: String): Boolean {
        if (isOn(activity) || !canUse(activity)) return false
        val kek = withContext(Dispatchers.Default) { newKek() } ?: return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek) }
        val authed = prompt(activity, cipher, title, subtitle) ?: return false
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrapped = try { authed.doFinal(raw) } catch (e: Exception) { return false }
        val iv = authed.iv
        key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        withContext(Dispatchers.IO) {
            // The wrapped key first: from now on sealed files can always be opened again
            val f = AtomicFile(File(activity.filesDir, FILE))
            val out = f.startWrite()
            try { out.write(byteArrayOf(VERSION) + iv + wrapped); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
            Library.get(activity).sealAll()
        }
        openState.value = true
        return true
    }

    /** Turns locked storage off (it must be unlocked): everything back to plain files, the keys removed. */
    suspend fun disable(activity: Activity): Boolean {
        if (!isOn(activity)) return true
        if (key == null) return false
        withContext(Dispatchers.IO) {
            unsealing = true
            try { Library.get(activity).unsealAll() } finally { unsealing = false }
            // Only when nothing sealed is left is the key given up
            File(activity.filesDir, FILE).delete()
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        }
        key = null
        openState.value = false
        return true
    }

    private const val VERSION: Byte = 1

    private fun kek(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    } catch (e: Exception) { null }

    private fun newKek(): SecretKey? {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Every use needs a fresh fingerprint, face or screen lock
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // A new fingerprint added later must not make the storage unreadable
            .setInvalidatedByBiometricEnrollment(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        for (strong in listOf(true, false)) {
            try {
                return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec(strong)) }.generateKey()
            } catch (e: Exception) { /* no StrongBox on this phone: the ordinary secure hardware */ }
        }
        return null
    }

    /** The system's own prompt (fingerprint, face or screen lock); the cipher comes back ready to use. */
    private suspend fun prompt(activity: Activity, cipher: Cipher, title: String, subtitle: String): Cipher? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        val prompt = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
        try {
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(result.cryptoObject?.cipher) }
                override fun onAuthenticationError(code: Int, message: CharSequence) { if (cont.isActive) cont.resume(null) }
            })
        } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
    }

    /** Reads a small file, sealed or plain (whichever is there). */
    fun readSmall(context: Context, name: String): ByteArray? {
        val sealed = File(context.filesDir, name + SUFFIX)
        if (sealed.exists()) {
            val k = key ?: return null
            return Sealed.open(k, AtomicFile(sealed).readFully())
        }
        val plain = File(context.filesDir, name)
        return if (plain.exists()) AtomicFile(plain).readFully() else null
    }

    /** Writes a small file sealed when the storage is locked, plain otherwise; never while it cannot be opened. */
    fun writeSmall(context: Context, name: String, bytes: ByteArray) {
        val on = sealing(context)
        val k = key
        if (on && k == null) throw IOException("Locked")
        val target = AtomicFile(File(context.filesDir, if (on) name + SUFFIX else name))
        val out = target.startWrite()
        try { out.write(if (on) Sealed.seal(k!!, bytes) else bytes); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
        // Only one form of a file stays
        File(context.filesDir, if (on) name else name + SUFFIX).delete()
    }
}
