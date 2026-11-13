package ua.hramota.data

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.KeyguardManager
import android.content.Context
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
 * Keystore) that works only for a few seconds right after the phone's own lock screen confirms the
 * owner (PIN, pattern, password, or the fingerprint or face it offers). No permission is needed.
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

    // Work that needs the key from start to end (turning on or off, a backup): locking waits for it
    private val busy = java.util.concurrent.atomic.AtomicInteger()

    fun <T> holding(block: () -> T): T { busy.incrementAndGet(); try { return block() } finally { busy.decrementAndGet() } }
    fun hold() { busy.incrementAndGet() }
    fun release() { busy.updateAndGet { maxOf(0, it - 1) } }

    /** One change at a time: turning on or off, a backup, a restore. */
    val work = kotlinx.coroutines.sync.Mutex()

    /** Locks now, unless work holding the key is under way; returns whether it locked. */
    fun lock(context: Context): Boolean {
        if (busy.get() > 0) return false
        // Opened copies are plain only while being read: removed even if nothing was unlocked (after a restart)
        Shelf.clearOpened(context)
        if (key == null) return true
        key = null
        Shelf.CoverMemory.clear()
        openState.value = false
        Library.get(context).reload()
        Recents.get(context).reload()
        ua.hramota.notebook.Notebook.reload(context)
        return true
    }

    enum class Outcome { OPEN, CANCELLED, NOT_CONFIRMED, KEY_LOST }

    /**
     * Gives up locked storage whose key the phone no longer has (the screen lock was removed, say):
     * the sealed files cannot be opened by anyone, so they are deleted and Hramota starts afresh.
     */
    fun reset(context: Context) {
        key = null
        Shelf.CoverMemory.clear()
        for (dir in listOf(context.filesDir, Library.coversDir(context), Library.copiesDir(context), File(context.filesDir, "rescue"), Library.notebookDir(context), Library.notebookMediaDir(context)))
            dir.listFiles().orEmpty().filter { it.name.endsWith(SUFFIX) }.forEach { it.delete() }
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, FILE).delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        openState.value = false
        Library.get(context).reload()
        Recents.get(context).reload()
        ua.hramota.notebook.Notebook.reload(context)
    }

    /** Asks for the fingerprint, face or screen lock and opens the storage. */
    suspend fun unlock(activity: ComponentActivity, title: String, subtitle: String): Outcome {
        if (!isOn(activity)) return Outcome.OPEN
        if (key != null) return Outcome.OPEN
        val stored = try { withContext(Dispatchers.IO) { AtomicFile(File(activity.filesDir, FILE)).readFully() } } catch (e: Exception) { return Outcome.KEY_LOST }
        val buf = ByteBuffer.wrap(stored)
        if (stored.size < 13 || buf.get() != VERSION) return Outcome.KEY_LOST
        val iv = ByteArray(12).also { buf.get(it) }
        val wrapped = ByteArray(buf.remaining()).also { buf.get(it) }
        // Lost only when the phone truly no longer holds the key; a passing error is just "not now"
        val kek = kek() ?: return if (hasKek() == false) Outcome.KEY_LOST else Outcome.NOT_CONFIRMED
        if (!confirm(activity, title, subtitle)) return Outcome.CANCELLED
        val raw = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv)) }.doFinal(wrapped)
        } catch (e: KeyPermanentlyInvalidatedException) { return Outcome.KEY_LOST }
        catch (e: android.security.keystore.UserNotAuthenticatedException) { return Outcome.NOT_CONFIRMED }
        catch (e: javax.crypto.AEADBadTagException) { return Outcome.KEY_LOST }
        catch (e: Exception) { return Outcome.NOT_CONFIRMED }
        // The key and the memory read with it come in together: nothing is written in between
        withContext(Dispatchers.IO) { Library.get(activity).reloadWith { key = SecretKeySpec(raw, "AES") } }
        raw.fill(0)
        Recents.get(activity).reload()
        ua.hramota.notebook.Notebook.reload(activity)
        openState.value = true
        return Outcome.OPEN
    }

    /**
     * Turns locked storage on: a new hardware key (usable only after authentication), a new data key
     * wrapped by it, then every remembered file sealed. Plain files are removed once sealed.
     */
    suspend fun enable(activity: ComponentActivity, title: String, subtitle: String): Boolean {
        if (isOn(activity) || !canUse(activity)) return false
        val kek = withContext(Dispatchers.Default) { newKek() } ?: return false
        if (!confirm(activity, title, subtitle)) return false
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek) } } catch (e: Exception) { return false }
        val wrapped = try { cipher.doFinal(raw) } catch (e: Exception) { return false }
        val iv = cipher.iv
        key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        // Open from this moment, even if sealing stops part way (it can be tried again; nothing is lost)
        openState.value = true
        withContext(Dispatchers.IO) {
            // The wrapped key first: from now on sealed files can always be opened again
            val f = AtomicFile(File(activity.filesDir, FILE))
            val out = f.startWrite()
            try { out.write(byteArrayOf(VERSION) + iv + wrapped); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
            holding { Library.get(activity).sealAll() }
        }
        openState.value = true
        return true
    }

    /** Turns locked storage off (it must be unlocked): everything back to plain files, the keys removed. */
    suspend fun disable(activity: ComponentActivity): Boolean {
        if (!isOn(activity)) return true
        if (key == null) return false
        withContext(Dispatchers.IO) {
            unsealing = true
            try { holding { Library.get(activity).unsealAll() } } finally { unsealing = false }
            // Only when nothing sealed is left is the key given up
            File(activity.filesDir, FILE).delete()
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        }
        key = null
        openState.value = false
        return true
    }

    private const val VERSION: Byte = 1

    private fun hasKek(): Boolean? = try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS) } catch (e: Exception) { null }

    private fun kek(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    } catch (e: Exception) { null }

    private fun newKek(): SecretKey? {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Usable only within seconds of the owner confirming on the phone's lock screen
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
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

    private const val AUTH_SECONDS = 15

    /** The phone's own lock screen asks for the owner (PIN, pattern, password, fingerprint or face). */
    suspend fun confirm(activity: ComponentActivity, title: String, subtitle: String): Boolean = suspendCancellableCoroutine { cont ->
        val intent: Intent? = activity.getSystemService(KeyguardManager::class.java)?.let {
            @Suppress("DEPRECATION") it.createConfirmDeviceCredentialIntent(title, subtitle)
        }
        if (intent == null) { cont.resume(false); return@suspendCancellableCoroutine }
        val self = arrayOfNulls<ActivityResultLauncher<Intent>>(1)
        val launcher = activity.activityResultRegistry.register("vault-" + System.nanoTime(), ActivityResultContracts.StartActivityForResult()) { r ->
            self[0]?.unregister()
            if (cont.isActive) cont.resume(r.resultCode == Activity.RESULT_OK)
        }
        self[0] = launcher
        cont.invokeOnCancellation { launcher.unregister() }
        try { launcher.launch(intent) } catch (e: Exception) { launcher.unregister(); if (cont.isActive) cont.resume(false) }
    }

    /** Reads a small file, sealed or plain (whichever is there). */
    fun readSmall(context: Context, name: String): ByteArray? = readIn(context.filesDir, name)

    /** As [readSmall], in a folder of the app's own (the notebook's, say). */
    fun readIn(dir: File, name: String): ByteArray? {
        val sealed = File(dir, name + SUFFIX)
        if (sealed.exists()) {
            val k = key ?: return null
            return Sealed.open(k, AtomicFile(sealed).readFully(), name)
        }
        val plain = File(dir, name)
        return if (plain.exists()) AtomicFile(plain).readFully() else null
    }

    /** Writes a small file sealed when the storage is locked, plain otherwise; never while it cannot be opened. */
    fun writeSmall(context: Context, name: String, bytes: ByteArray) = writeIn(context, context.filesDir, name, bytes)

    /** As [writeSmall], in a folder of the app's own; [name] is the file's own name (no folders in it). */
    fun writeIn(context: Context, dir: File, name: String, bytes: ByteArray) {
        val on = sealing(context)
        val k = key
        if (on && k == null) throw IOException("Locked")
        dir.mkdirs()
        val target = AtomicFile(File(dir, if (on) name + SUFFIX else name))
        val out = target.startWrite()
        try { out.write(if (on) Sealed.seal(k!!, bytes, name) else bytes); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
        // Only one form of a file stays
        File(dir, if (on) name else name + SUFFIX).delete()
    }

    /** Deletes a small file in whichever form it is. */
    fun deleteIn(dir: File, name: String) { File(dir, name).delete(); File(dir, name + SUFFIX).delete() }
}
