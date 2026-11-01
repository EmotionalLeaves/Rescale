package app.rescale.engine.pipeline

import android.content.Context
import app.rescale.core.model.ModelSpec
import app.rescale.core.model.Packs
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** One entry of assets/models/packs.json (written by the models workflow). */
data class PackEntry(
    val id: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val files: Map<String, String>, // file name -> sha256
)

class PackIntegrityException(message: String) : Exception(message)

/**
 * Downloadable model packs. Each pack is a zip whose SHA-256 is pinned in the bundled
 * manifest; every file inside is checked again before the pack is activated. Files are
 * unpacked into a temp dir and moved into filesDir/packs/<id> only after verification.
 */
class PackStore(private val context: Context) {
    private val root = File(context.filesDir, "packs").apply { mkdirs() }

    val manifest: Map<String, PackEntry> by lazy { loadManifest() }

    private fun loadManifest(): Map<String, PackEntry> = runCatching {
        val json = context.assets.open("models/packs.json").bufferedReader().use { it.readText() }
        val packs = JSONObject(json).getJSONArray("packs")
        (0 until packs.length()).associate { i ->
            val p = packs.getJSONObject(i)
            val files = p.getJSONObject("files")
            p.getString("id") to PackEntry(
                id = p.getString("id"),
                url = p.getString("url"),
                size = p.getLong("size"),
                sha256 = p.getString("sha256").lowercase(),
                files = files.keys().asSequence().associateWith { files.getString(it).lowercase() },
            )
        }
    }.getOrDefault(emptyMap())

    fun dir(id: String) = File(root, id)
    private fun marker(id: String) = File(dir(id), ".verified")

    fun isInstalled(id: String): Boolean {
        val entry = manifest[id] ?: return false
        return marker(id).takeIf { it.exists() }?.readText()?.trim() == entry.sha256
    }

    fun installed(): Set<String> = Packs.all.map { it.id }.filter(::isInstalled).toSet()

    fun installedBytes(id: String): Long = dir(id).listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(id: String) {
        dir(id).deleteRecursively()
        partial(id).delete()
    }

    fun files(spec: ModelSpec): Pair<File, File> {
        val d = dir(spec.packId ?: error("${spec.id} is bundled"))
        return File(d, spec.paramFile) to File(d, spec.binFile)
    }

    private fun partial(id: String) = File(root, "$id.zip.part")

    /**
     * Downloads [id] with HTTP Range resume. [onProgress] gets (bytes, total). Blocking; checks
     * [isCancelled] between chunks. Throws on network or integrity errors.
     */
    fun download(id: String, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        val entry = manifest[id] ?: throw PackIntegrityException("Unknown pack $id")
        val part = partial(id)
        var url = URL(entry.url)
        var redirects = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (have >= entry.size) break
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw PackIntegrityException("Bad redirect")
                conn.disconnect()
                if (++redirects > 5) throw PackIntegrityException("Too many redirects")
                url = URL(url, loc)
                continue
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !append) {
                conn.disconnect()
                throw PackIntegrityException("Download failed (HTTP $code)")
            }
            if (!append) part.delete() // server ignored Range: start over
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = part.length()
                    while (true) {
                        if (isCancelled()) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, entry.size)
                    }
                }
            }
            conn.disconnect()
            break
        }
        if (isCancelled()) return
        try {
            part.inputStream().use { installZip(it, expectedId = id) }
        } finally {
            part.delete()
        }
    }

    /**
     * Installs a pack zip from any stream (download or a file the user picked). The zip's
     * SHA-256 must match a pack in the manifest. Returns the pack id.
     */
    fun installZip(input: InputStream, expectedId: String? = null): String =
        PackInstaller(root, context.cacheDir, manifest).install(input, expectedId)

}

/** Pure-JVM part of pack installation (unit-tested): verify zip + files, then activate atomically. */
class PackInstaller(private val root: File, private val tmpDir: File, private val manifest: Map<String, PackEntry>) {
    fun install(input: InputStream, expectedId: String? = null): String {
        val tmpZip = File(tmpDir, "pack-import-${System.nanoTime()}.zip")
        val sha = MessageDigest.getInstance("SHA-256")
        tmpZip.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        try {
            val digest = sha.digest().toHex()
            val entry = manifest.values.firstOrNull { it.sha256 == digest }
                ?: throw PackIntegrityException("This file isn't a Rescale model pack (checksum doesn't match)")
            if (expectedId != null && entry.id != expectedId) throw PackIntegrityException("Wrong pack")

            val staging = File(root, "${entry.id}.staging").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(tmpZip.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = File(e.name).name // flatten: no path traversal
                    if (name !in entry.files) continue
                    val f = File(staging, name)
                    val fsha = MessageDigest.getInstance("SHA-256")
                    f.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            fsha.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    if (fsha.digest().toHex() != entry.files[name]) {
                        staging.deleteRecursively()
                        throw PackIntegrityException("Corrupt file in pack: $name")
                    }
                }
            }
            val missing = entry.files.keys.filterNot { File(staging, it).exists() }
            if (missing.isNotEmpty()) {
                staging.deleteRecursively()
                throw PackIntegrityException("Pack is missing ${missing.joinToString()}")
            }
            File(staging, ".verified").writeText(entry.sha256)
            val target = File(root, entry.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw PackIntegrityException("Couldn't activate pack")
            return entry.id
        } finally {
            tmpZip.delete()
        }
    }
}

internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
