package app.rescale.engine.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val param = "7767517\n1 1\nInput in0 0 1 in0\n".toByteArray()
    private val bin = ByteArray(5000) { (it * 31).toByte() }

    private fun setup(zipBytes: ByteArray, files: Map<String, String>): Pair<PackInstaller, File> {
        val root = tmp.newFolder("packs")
        val manifest = mapOf("demo" to PackEntry("demo", "https://x/demo.zip", zipBytes.size.toLong(), sha(zipBytes), files))
        return PackInstaller(root, tmp.newFolder("cache"), manifest) to root
    }

    @Test fun installsVerifiedPack() {
        val z = zip("pack/m.param" to param, "pack/m.bin" to bin, "LICENSE.txt" to "BSD".toByteArray())
        val (inst, root) = setup(z, mapOf("m.param" to sha(param), "m.bin" to sha(bin)))
        assertEquals("demo", inst.install(z.inputStream()))
        val dir = File(root, "demo")
        assertTrue(File(dir, "m.param").readBytes().contentEquals(param))
        assertTrue(File(dir, "m.bin").readBytes().contentEquals(bin))
        assertEquals(sha(z), File(dir, ".verified").readText())
        assertFalse(File(dir, "LICENSE.txt").exists()) // only manifest files are installed
        assertFalse(File(root, "demo.staging").exists())
    }

    @Test fun rejectsUnknownZip() {
        val z = zip("m.param" to param)
        val (inst, _) = setup(zip("other" to bin), mapOf())
        try { inst.install(z.inputStream()); fail() } catch (e: PackIntegrityException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test fun rejectsTamperedFileEvenIfZipHashMatches() {
        // Manifest pins the zip, but a file hash inside is wrong (e.g. a bad manifest entry).
        val z = zip("m.param" to param, "m.bin" to bin)
        val (inst, root) = setup(z, mapOf("m.param" to sha(param), "m.bin" to "00".repeat(32)))
        try { inst.install(z.inputStream()); fail() } catch (e: PackIntegrityException) {
            assertTrue(e.message!!.contains("m.bin"))
        }
        assertFalse(File(root, "demo").exists())
    }

    @Test fun rejectsIncompletePack() {
        val z = zip("m.param" to param)
        val (inst, root) = setup(z, mapOf("m.param" to sha(param), "m.bin" to sha(bin)))
        try { inst.install(z.inputStream()); fail() } catch (e: PackIntegrityException) {
            assertTrue(e.message!!.contains("missing"))
        }
        assertFalse(File(root, "demo").exists())
    }

    @Test fun pathTraversalIsFlattened() {
        val z = zip("../../evil/m.param" to param, "m.bin" to bin)
        val (inst, root) = setup(z, mapOf("m.param" to sha(param), "m.bin" to sha(bin)))
        inst.install(z.inputStream())
        assertTrue(File(root, "demo/m.param").exists())
        assertFalse(File(root.parentFile.parentFile, "evil").exists())
    }

    @Test fun installsDownloadedFileInPlace() {
        val z = zip("m.param" to param, "m.bin" to bin)
        val (inst, root) = setup(z, mapOf("m.param" to sha(param), "m.bin" to sha(bin)))
        val part = File(tmp.root, "demo.zip.part").apply { writeBytes(z) }
        assertEquals("demo", inst.installFile(part, expectedId = "demo"))
        assertTrue(File(root, "demo/m.bin").readBytes().contentEquals(bin))
        assertTrue(part.exists()) // the caller owns (and deletes) the download
        part.writeBytes(z.copyOf(z.size - 1))
        try {
            inst.installFile(part)
            fail("truncated download accepted")
        } catch (e: PackIntegrityException) {
            // expected
        }
    }
}
