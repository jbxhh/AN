package io.anonero.util.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files

class BackupHelperTest {
    @Test
    fun extractsNormalBackupEntry() {
        val root = Files.createTempDirectory("anon-backup-test").toFile()
        try {
            val archive = File(root, "safe.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("anon.json"))
                zip.write("{\"version\":2}".toByteArray())
                zip.closeEntry()
            }

            val destination = File(root, "extract")
            BackupHelper.unZip(archive, destination)

            assertTrue(File(destination, "anon.json").isFile)
            assertEquals("{\"version\":2}", File(destination, "anon.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipEntry() {
        val root = Files.createTempDirectory("anon-backup-test").toFile()
        try {
            val archive = File(root, "malicious.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write("not allowed".toByteArray())
                zip.closeEntry()
            }

            val destination = File(root, "extract")
            assertThrows(IOException::class.java) {
                BackupHelper.unZip(archive, destination)
            }
            assertFalse(File(root, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
