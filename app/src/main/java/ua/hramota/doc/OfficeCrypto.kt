package ua.hramota.doc

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The password is wrong. */
class WrongPasswordException : IOException("Wrong password")

/** Protected in a way Hramota cannot open (old Word/Excel/PowerPoint protection, or an unknown cipher). */
class UnsupportedProtectionException : IOException("Unsupported protection")

/**
 * Word, Excel and PowerPoint files protected with a password (ECMA-376 encryption, as Office 2007 and
 * later and LibreOffice write it): the package sits encrypted inside a compound file. "Agile"
 * (Office 2010 and later: AES with SHA-512 and 100 000 rounds) and "Standard" (Office 2007, AES-128 ECB
 * with SHA-1). The password and the key stay in memory only while opening; the decrypted file is kept
 * in memory, never written anywhere.
 */
object OfficeCrypto {
    private const val MAX = 200L * 1024 * 1024

    /** Whether [head] (the first bytes of a file) starts a compound file: perhaps a protected Office file. */
    fun mayBeProtected(head: ByteArray) = head.size >= 8 &&
        head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()

    /** Reads the whole file (it must be in memory to be decrypted) within [MAX]. */
    fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; if (total > MAX) throw IOException("Too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Whether the compound file [file] is a protected Office package. */
    fun isProtected(file: ByteArray): Boolean = try {
        mayBeProtected(file) && OleFile(file, MAX).let { it.names.contains("EncryptionInfo") && it.names.contains("EncryptedPackage") }
    } catch (e: Exception) { false }

    /** The package (a ZIP: DOCX, XLSX or PPTX) inside protected [file]. Throws [WrongPasswordException]. */
    fun decrypt(file: ByteArray, password: String): ByteArray {
        val ole = OleFile(file, MAX)
        val info = ole.stream("EncryptionInfo") ?: throw IOException("Not protected")
        val pkg = ole.stream("EncryptedPackage") ?: throw IOException("Not protected")
        return decrypt(info, pkg, password)
    }

    internal fun decrypt(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        if (info.size < 8 || pkg.size < 8) throw IOException("Too short")
        val major = u16(info, 0); val minor = u16(info, 2)
        val out = when {
            major == 4 && minor == 4 -> agile(info, pkg, password)
            (major == 2 || major == 3 || major == 4) && minor == 2 -> standard(info, pkg, password)
            else -> throw UnsupportedProtectionException()
        }
        // What was decrypted must be a package
        if (out.size < 4 || out[0] != 'P'.code.toByte() || out[1] != 'K'.code.toByte()) throw WrongPasswordException()
        return out
    }

    // ---- Agile ----

    private val BLOCK_VERIFIER_INPUT = hex("fea7d2763b4b9e79")
    private val BLOCK_VERIFIER_VALUE = hex("d7aa0f6d3061344e")
    private val BLOCK_KEY = hex("146e0be7abacd0d6")

    private fun agile(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val xml = String(info, 8, info.size - 8, Charsets.UTF_8)
        // The XML is small and fixed in form: attributes of two elements
        val keyData = attrs(xml, "keyData") ?: throw IOException("No keyData")
        // The key kept for the password (a file may also have one for a certificate, which comes first)
        val passwordPart = Regex("<(?:[\\w-]+:)?keyEncryptor\\s+uri\\s*=\\s*\"http://schemas.microsoft.com/office/2006/keyEncryptor/password\"\\s*>(.*?)</(?:[\\w-]+:)?keyEncryptor>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: throw UnsupportedProtectionException()
        val enc = attrs(passwordPart, "encryptedKey") ?: throw IOException("No encryptedKey")
        if (keyData["cipherAlgorithm"] != "AES" || enc["cipherAlgorithm"] != "AES") throw UnsupportedProtectionException()
        if (keyData["cipherChaining"] != "ChainingModeCBC" || enc["cipherChaining"] != "ChainingModeCBC") throw UnsupportedProtectionException()
        val hash = enc["hashAlgorithm"] ?: throw IOException("No hash")
        val spin = enc["spinCount"]?.toIntOrNull()?.takeIf { it in 0..10_000_000 } ?: throw IOException("Bad spinCount")
        val salt = b64(enc["saltValue"])
        val keyBytes = (enc["keyBits"]?.toIntOrNull() ?: 0) / 8
        val blockSize = enc["blockSize"]?.toIntOrNull() ?: 16
        if (keyBytes !in setOf(16, 24, 32) || blockSize != 16) throw UnsupportedProtectionException()
        val iv = fit(salt, blockSize)
        var h = digest(hash, salt, password.toByteArray(Charsets.UTF_16LE))
        val md = md(hash)
        val counter = ByteArray(4)
        for (i in 0 until spin) {
            le32(counter, i)
            md.update(counter); md.update(h); h = md.digest()
        }
        fun keyFor(block: ByteArray) = fit(digest(hash, h, block), keyBytes)
        val verifierInput = cbc(keyFor(BLOCK_VERIFIER_INPUT), iv, b64(enc["encryptedVerifierHashInput"]))
        val verifierValue = cbc(keyFor(BLOCK_VERIFIER_VALUE), iv, b64(enc["encryptedVerifierHashValue"]))
        val saltSize = enc["saltSize"]?.toIntOrNull() ?: salt.size
        val hashSize = enc["hashSize"]?.toIntOrNull() ?: md.digestLength
        val expected = digest(hash, verifierInput.copyOf(saltSize.coerceAtMost(verifierInput.size)))
        if (!MessageDigest.isEqual(expected.copyOf(hashSize), verifierValue.copyOf(hashSize))) throw WrongPasswordException()
        val dataKeyBytes = (keyData["keyBits"]?.toIntOrNull() ?: 0) / 8
        if (dataKeyBytes !in setOf(16, 24, 32)) throw UnsupportedProtectionException()
        val key = cbc(keyFor(BLOCK_KEY), iv, b64(enc["encryptedKeyValue"])).copyOf(dataKeyBytes)
        val dataHash = keyData["hashAlgorithm"] ?: hash
        val dataSalt = b64(keyData["saltValue"])
        val dataBlock = keyData["blockSize"]?.toIntOrNull() ?: 16
        if (dataBlock != 16) throw UnsupportedProtectionException()
        // The package: 4096-byte segments, each with its own starting vector
        val size = le64(pkg, 0)
        if (size < 0 || size > MAX || size > pkg.size - 8) throw IOException("Bad size")
        val out = ByteArray(size.toInt())
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val k = SecretKeySpec(key, "AES")
        var at = 8; var segment = 0; var written = 0
        val index = ByteArray(4)
        while (written < out.size) {
            val n = minOf(4096, pkg.size - at)
            val usable = n - n % 16
            if (usable <= 0) throw IOException("Truncated")
            le32(index, segment)
            cipher.init(Cipher.DECRYPT_MODE, k, IvParameterSpec(fit(digest(dataHash, dataSalt, index), dataBlock)))
            val plain = cipher.doFinal(pkg, at, usable)
            val take = minOf(plain.size, out.size - written)
            System.arraycopy(plain, 0, out, written, take)
            written += take; at += n; segment++
        }
        return out
    }

    // ---- Standard ----

    private fun standard(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val headerSize = i32(info, 8)
        if (headerSize < 32 || 12 + headerSize > info.size) throw IOException("Bad header")
        val alg = i32(info, 12 + 8)
        val keyBits = i32(info, 12 + 16)
        // AES-128, 192 or 256 (RC4 "CryptoAPI" files are older and not read here)
        if (alg !in setOf(0x660E, 0x660F, 0x6610) || keyBits !in setOf(128, 192, 256)) throw UnsupportedProtectionException()
        val v = 12 + headerSize
        val saltSize = i32(info, v)
        if (saltSize != 16 || v + 4 + 16 + 16 + 4 + 32 > info.size) throw IOException("Bad verifier")
        val salt = info.copyOfRange(v + 4, v + 20)
        val encVerifier = info.copyOfRange(v + 20, v + 36)
        val verifierHashSize = i32(info, v + 36)
        val encVerifierHash = info.copyOfRange(v + 40, v + 72)
        val sha = MessageDigest.getInstance("SHA-1")
        var h = digest("SHA1", salt, password.toByteArray(Charsets.UTF_16LE))
        val counter = ByteArray(4)
        for (i in 0 until 50_000) { le32(counter, i); sha.update(counter); sha.update(h); h = sha.digest() }
        val final = digest("SHA1", h, ByteArray(4))
        val b1 = ByteArray(64) { 0x36 }; val b2 = ByteArray(64) { 0x5C }
        for (i in final.indices) { b1[i] = (b1[i].toInt() xor final[i].toInt()).toByte(); b2[i] = (b2[i].toInt() xor final[i].toInt()).toByte() }
        val key = (digest("SHA1", b1) + digest("SHA1", b2)).copyOf(keyBits / 8)
        val verifier = ecb(key, encVerifier, 0, 16)
        val verifierHash = ecb(key, encVerifierHash, 0, 32)
        if (verifierHashSize !in 1..32 || !MessageDigest.isEqual(digest("SHA1", verifier).copyOf(verifierHashSize), verifierHash.copyOf(verifierHashSize))) throw WrongPasswordException()
        val size = le64(pkg, 0)
        if (size < 0 || size > MAX || size > pkg.size - 8) throw IOException("Bad size")
        val usable = (pkg.size - 8) - (pkg.size - 8) % 16
        return ecb(key, pkg, 8, usable).copyOf(size.toInt())
    }

    // ---- Helpers ----

    private fun md(name: String): MessageDigest = MessageDigest.getInstance(
        when (name) { "SHA1", "SHA-1" -> "SHA-1"; "SHA256", "SHA-256" -> "SHA-256"; "SHA384", "SHA-384" -> "SHA-384"; "SHA512", "SHA-512" -> "SHA-512"; else -> throw UnsupportedProtectionException() },
    )

    private fun digest(name: String, vararg parts: ByteArray): ByteArray = md(name).apply { for (p in parts) update(p) }.digest()

    /** [b] cut or filled with 0x36 to [n] bytes, as the format asks. */
    private fun fit(b: ByteArray, n: Int): ByteArray = if (b.size >= n) b.copyOf(n) else b + ByteArray(n - b.size) { 0x36 }

    private fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % 16 != 0) throw IOException("Bad block")
        return Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)
    }

    private fun ecb(key: ByteArray, data: ByteArray, from: Int, length: Int): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data, from, length)

    private fun attrs(xml: String, element: String): Map<String, String>? {
        val m = Regex("<(?:[\\w-]+:)?$element\\b([^>]*)/?>").find(xml) ?: return null
        return Regex("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun b64(s: String?): ByteArray = try { java.util.Base64.getDecoder().decode(s ?: throw IOException("Missing value")) } catch (e: IllegalArgumentException) { throw IOException("Bad value") }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun i32(b: ByteArray, at: Int): Int { if (at < 0 || at + 4 > b.size) throw IOException("Short"); return DocReader.i32(b, at) }
    private fun le64(b: ByteArray, at: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF); return v }
    private fun le32(out: ByteArray, v: Int) { out[0] = v.toByte(); out[1] = (v shr 8).toByte(); out[2] = (v shr 16).toByte(); out[3] = (v shr 24).toByte() }
}
