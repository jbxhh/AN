package io.anonero.util.backup

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.util.encoders.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptUtil {

    const val DEFAULT_ITR = 11000
    const val SALT_PREFIX = "Salted__"
    const val BUFFER_SIZE = 4096


    fun encrypt(
        password: String,
        toEncrypt: String
    ): String {
        val salt = SecureRandom().generateSeed(8)
        // Derive 48 byte key
        var keySpec: SecretKeySpec?
        var ivSpec: IvParameterSpec?
        PKCS5S2ParametersGenerator(SHA256Digest()).let { generator ->
            generator.init(password.toByteArray(), salt, DEFAULT_ITR)
            (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key.let { secretKey ->
                val bytes = secretKey.copyOfRange(0, 32)
                keySpec = SecretKeySpec(bytes, "AES")
                ivSpec = IvParameterSpec(secretKey.copyOfRange(32, secretKey.size))
                generator.password.fill('*'.code.toByte())
                secretKey.fill('*'.code.toByte())
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val cipherText = cipher.doFinal(toEncrypt.toByteArray())
        return Base64.encode(SALT_PREFIX.toByteArray() + salt + cipherText)
            .decodeToString()
            .replace("(.{64})".toRegex(), "$1\n")

    }

    fun decrypt(
        password: String,
        toDecrypt: String
    ): String {
        val encryptedBytes = Base64.decode(toDecrypt.lines().joinToString(""))
        val salt = encryptedBytes.copyOfRange(8, 16)
        // Derive 48 byte key
        var keySpec: SecretKeySpec?
        var ivSpec: IvParameterSpec?
        PKCS5S2ParametersGenerator(SHA256Digest()).let { generator ->
            generator.init(password.toByteArray(), salt, DEFAULT_ITR)
            (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key.let { secretKey ->
                // Decryption Key is bytes 0 - 31 of the derived secret key
                val bytes = secretKey.copyOfRange(0, 32)
                keySpec = SecretKeySpec(bytes, "AES")
                // Input Vector is bytes 32 - 47 of the derived secret key
                ivSpec = IvParameterSpec(secretKey.copyOfRange(32, secretKey.size))
                generator.password.fill('*'.code.toByte())
                secretKey.fill('*'.code.toByte())
            }
        }
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        // Decrypt the Cipher Text and manually remove padding after
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(cipherText)
        // Last byte of the decrypted text is the number of padding bytes needed to remove
        val plaintext = decrypted.copyOfRange(0, decrypted.size - decrypted.last().toInt())
        return plaintext.toString(Charsets.UTF_8)

    }

    fun encryptFile(key: String, inputFile: File, outputFile: File) {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val keyMaterial = deriveKeyMaterial(key, salt)
        val keySpec = SecretKeySpec(keyMaterial.copyOfRange(0, 32), "AES")
        val ivSpec = IvParameterSpec(keyMaterial.copyOfRange(32, 48))
        keyMaterial.fill(0)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        try {
            FileInputStream(inputFile).use { input ->
                FileOutputStream(outputFile).use { rawOutput ->
                    // Preserve the existing OpenSSL-compatible file format.
                    rawOutput.write(SALT_PREFIX.toByteArray() + salt)
                    CipherOutputStream(BufferedOutputStream(rawOutput), cipher).use { encryptedOutput ->
                        input.copyTo(encryptedOutput, BUFFER_SIZE)
                    }
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }
    }

    fun decryptFile(key: String, inputFile: File, outputFile: File) {
        val prefix = ByteArray(16)
        FileInputStream(inputFile).use { input ->
            var offset = 0
            while (offset < prefix.size) {
                val count = input.read(prefix, offset, prefix.size - offset)
                if (count < 0) {
                    throw java.io.IOException("Encrypted backup header is truncated")
                }
                offset += count
            }
        }

        if (!prefix.copyOfRange(0, 8).contentEquals(SALT_PREFIX.toByteArray())) {
            throw java.io.IOException("Encrypted backup header is invalid")
        }
        val encryptedLength = inputFile.length() - prefix.size
        if (encryptedLength < 16L || encryptedLength % 16L != 0L) {
            throw java.io.IOException("Encrypted backup payload is invalid")
        }

        val salt = prefix.copyOfRange(8, 16)
        val keyMaterial = deriveKeyMaterial(key, salt)
        val keySpec = SecretKeySpec(keyMaterial.copyOfRange(0, 32), "AES")
        val ivSpec = IvParameterSpec(keyMaterial.copyOfRange(32, 48))
        keyMaterial.fill(0)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        try {
            FileInputStream(inputFile).use { input ->
                var remaining = prefix.size.toLong()
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0L) {
                        remaining -= skipped
                    } else {
                        if (input.read() < 0) {
                            throw java.io.IOException("Encrypted backup header is truncated")
                        }
                        remaining--
                    }
                }
                CipherInputStream(BufferedInputStream(input), cipher).use { decryptedInput ->
                    FileOutputStream(outputFile).use { output ->
                        decryptedInput.copyTo(output, BUFFER_SIZE)
                    }
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }
    }

    private fun deriveKeyMaterial(password: String, salt: ByteArray): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password.toByteArray(), salt, DEFAULT_ITR)
        val keyMaterial = (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key
        generator.password.fill(0)
        return keyMaterial
    }

}