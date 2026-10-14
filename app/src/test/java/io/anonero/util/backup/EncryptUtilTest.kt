package io.anonero.util.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class EncryptUtilTest {
    @Test
    fun encryptedBackupFileRoundTripsWithoutChangingFormat() {
        val directory = Files.createTempDirectory("anon-encrypt-test").toFile()
        try {
            val source = File(directory, "source.zip")
            val encrypted = File(directory, "backup.anon")
            val decrypted = File(directory, "decrypted.zip")
            val expected = ByteArray(8192) { index -> ((index * 31) % 256).toByte() }
            source.writeBytes(expected)

            EncryptUtil.encryptFile("test passphrase", source, encrypted)

            assertTrue(encrypted.isFile)
            assertTrue(
                encrypted.readBytes().copyOfRange(0, EncryptUtil.SALT_PREFIX.length)
                    .contentEquals(EncryptUtil.SALT_PREFIX.toByteArray())
            )

            EncryptUtil.decryptFile("test passphrase", encrypted, decrypted)

            assertArrayEquals(expected, decrypted.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncatedEncryptedHeader() {
        val directory = Files.createTempDirectory("anon-encrypt-test").toFile()
        try {
            val encrypted = File(directory, "truncated.anon")
            val decrypted = File(directory, "decrypted.zip")
            encrypted.writeBytes(byteArrayOf(1, 2, 3, 4))

            org.junit.Assert.assertThrows(IOException::class.java) {
                EncryptUtil.decryptFile("test passphrase", encrypted, decrypted)
            }
            assertFalse(decrypted.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidEncryptedHeader() {
        val directory = Files.createTempDirectory("anon-encrypt-test").toFile()
        try {
            val encrypted = File(directory, "invalid.anon")
            val decrypted = File(directory, "decrypted.zip")
            encrypted.writeBytes(ByteArray(32) { 1 })

            org.junit.Assert.assertThrows(IOException::class.java) {
                EncryptUtil.decryptFile("test passphrase", encrypted, decrypted)
            }
            assertFalse(decrypted.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
