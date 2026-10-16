package com.tilt.wallpapers.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * "Sao lưu tất cả": every set with its edits, masks and subjects, plus settings, schedule and theme, in
 * one `.tilt` file (a zip). Restore either merges the sets in or replaces everything.
 */
object Backup {
    private const val MANIFEST = "tilt-backup.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private class Manifest(val format: Int = 1, val createdAt: Long = 0, val state: AppState = AppState())

    data class Summary(val sets: Int, val images: Int, val createdAt: Long)

    /** Pictures in sets (the trash is not backed up). */
    private fun pictures(state: AppState) = state.sets.flatMap { it.images }.map { it.id }.distinct()

    /** Rough size of a backup right now, in bytes. */
    fun estimate(): Long = pictures(Repo.state.value).sumOf { id -> Repo.pictureFiles(id).sumOf { (_, f) -> if (f.exists()) f.length() else 0L } }

    suspend fun export(context: Context, uri: Uri, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val state = Repo.state.value.let {
                // Device-only bits stay behind: what's-new marker, the applied schedule slot, the trash.
                it.copy(trash = emptyList(), settings = it.settings.copy(seenVersion = 0, scheduleSlot = ""))
            }
            val files = pictures(state).flatMap { id -> Repo.pictureFiles(id).filter { it.second.exists() }.map { (dir, f) -> "$dir/${f.name}" to f } } +
                state.fonts.map { Fonts.file(context, it.id) }.filter { it.exists() }.map { "fonts/${it.name}" to it }
            val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
            var done = 0L
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(json.encodeToString(Manifest.serializer(), Manifest(1, System.currentTimeMillis(), state)).toByteArray())
                    zip.closeEntry()
                    files.forEach { (name, f) ->
                        zip.putNextEntry(ZipEntry("files/$name"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        done += f.length()
                        onProgress(done.toFloat() / total)
                    }
                }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /** Reads only the manifest (written first), to show what a backup holds before restoring. */
    suspend fun summary(context: Context, uri: Uri): Summary? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                ZipInputStream(inp.buffered()).use { zip ->
                    val e = zip.nextEntry ?: return@runCatching null
                    if (e.name != MANIFEST) return@runCatching null
                    val m = json.decodeFromString(Manifest.serializer(), zip.readBytes().decodeToString())
                    Summary(m.state.sets.size, m.state.sets.sumOf { it.images.size }, m.createdAt)
                }
            }
        }.getOrNull()
    }

    /** Restores a backup. [replace] = wipe current sets and settings first; otherwise add its sets as new ones. */
    suspend fun restore(context: Context, uri: Uri, replace: Boolean, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            runCatching {
                val root = temp.canonicalPath + File.separator
                var manifest: Manifest? = null
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    ZipInputStream(inp.buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            if (e.name == MANIFEST) manifest = json.decodeFromString(Manifest.serializer(), zip.readBytes().decodeToString())
                            else if (!e.isDirectory) {
                                val out = File(temp, e.name)
                                require(out.canonicalPath.startsWith(root)) { "bad entry ${e.name}" }   // zip slip
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            e = zip.nextEntry
                        }
                    }
                } ?: return@runCatching false
                val m = manifest ?: return@runCatching false
                onProgress(0.4f)

                // Merging gives every set and picture a fresh id so nothing collides with what is here.
                val idMap = HashMap<String, String>()
                val sets = m.state.sets.map { set ->
                    if (replace) set else set.copy(
                        id = UUID.randomUUID().toString(),
                        images = set.images.map { img -> img.copy(id = UUID.randomUUID().toString().also { idMap[img.id] = it }) },
                    )
                }
                if (replace) {
                    // Old pictures and the trash go for good, synchronously, before the copies land.
                    val cur = Repo.state.value
                    val old = (pictures(cur) + cur.trash.flatMap { it.images }.map { it.id }).distinct()
                    old.forEach { id -> Repo.pictureFiles(id).forEach { it.second.delete() }; Repo.thumbFile(id).delete() }
                }
                // User fonts keep their ids (unique), so they never collide with fonts already here.
                m.state.fonts.forEach { f -> File(temp, "files/fonts/${f.id}.ttf").takeIf { it.exists() }?.let { Repo.copyDurable(it, Fonts.file(context, f.id)) } }
                val restored = sets.flatMap { it.images }
                restored.forEachIndexed { i, img ->
                    val src = idMap.entries.firstOrNull { it.value == img.id }?.key ?: img.id
                    Repo.pictureFiles(img.id).forEach { (dir, dst) ->
                        val f = File(temp, "files/$dir/${src}.${dst.extension}")
                        if (f.exists()) Repo.copyDurable(f, dst)
                    }
                    Repo.rebuildThumb(img.id)
                    onProgress(0.4f + 0.6f * (i + 1) / restored.size.coerceAtLeast(1))
                }
                Repo.mutate { s ->
                    if (replace) m.state.copy(
                        trash = emptyList(),
                        settings = m.state.settings.copy(seenVersion = s.settings.seenVersion, scheduleSlot = ""),
                    ) else s.copy(sets = s.sets + sets, activeSetId = s.activeSetId ?: sets.firstOrNull()?.id,
                        fonts = s.fonts + m.state.fonts.filter { f -> s.fonts.none { it.id == f.id } })
                }
                if (Repo.state.value.settings.depth) Repo.ensureMasks()
                true
            }.getOrDefault(false)
        } finally {
            temp.deleteRecursively()
        }
    }
}
