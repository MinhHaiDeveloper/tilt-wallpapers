package com.tilt.wallpapers.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** A font the user added (.ttf / .otf), kept in files/fonts/<id>.ttf. */
@Serializable
data class CustomFont(
    val id: String,
    val name: String,
    /** The 'wght' axis range of a variable font; equal = one fixed weight. */
    val minWeight: Int = 400,
    val maxWeight: Int = 400,
    /** Has the Vietnamese letters (else the date falls back to a font that does). */
    val vietnamese: Boolean = false,
) {
    val variable get() = minWeight != maxWeight
}

/** "+ Font của bạn": import, look up and delete the user's fonts. */
object Fonts {
    fun dir(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }
    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.ttf")
    fun find(id: String?): CustomFont? = id?.let { i -> Repo.state.value.fonts.find { it.id == i } }

    sealed interface Result { data class Ok(val font: CustomFont) : Result; data object Bad : Result }

    /** Copies the picked file in, checks it is a real font and reads its weight range. Off the main thread. */
    fun import(ctx: Context, uri: Uri, displayName: String?): Result {
        val id = UUID.randomUUID().toString()
        val dst = file(ctx, id)
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { inp -> dst.outputStream().use { inp.copyTo(it) } } ?: return Result.Bad
            // Throws on anything that is not a font.
            android.graphics.fonts.Font.Builder(dst).build()
            val (lo, hi) = weightAxis(dst) ?: (400 to 400)
            val face = Typeface.Builder(dst).build()
            val vi = Paint().apply { typeface = face }.hasGlyph("ể") && Paint().apply { typeface = face }.hasGlyph("Ặ")
            val name = (displayName ?: "Font").substringBeforeLast('.').take(24)
            Result.Ok(CustomFont(id, name, lo, hi, vi))
        }.getOrElse { dst.delete(); Result.Bad }
    }

    /** Min and max of a variable font's 'wght' axis, from its 'fvar' table; null = not variable. */
    private fun weightAxis(f: File): Pair<Int, Int>? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            r.seek(4)
            val tables = r.readUnsignedShort()
            r.seek(12)
            var fvar = -1L
            repeat(tables) {
                val tag = ByteArray(4).also { r.readFully(it) }.decodeToString()
                r.readInt()                       // checksum
                val off = r.readInt().toLong() and 0xFFFFFFFFL
                r.readInt()                       // length
                if (tag == "fvar") fvar = off
            }
            if (fvar < 0) return null
            r.seek(fvar + 4)
            val axesOff = r.readUnsignedShort()
            r.readUnsignedShort()                 // reserved
            val axes = r.readUnsignedShort()
            val axisSize = r.readUnsignedShort()
            for (i in 0 until axes) {
                r.seek(fvar + axesOff + i.toLong() * axisSize)
                val tag = ByteArray(4).also { r.readFully(it) }.decodeToString()
                val min = r.readInt() / 65536f; r.readInt(); val max = r.readInt() / 65536f
                if (tag == "wght") return (min.toInt().coerceIn(1, 1000) to max.toInt().coerceIn(1, 1000)).takeIf { it.first < it.second }
            }
            null
        }
    }.getOrNull()
}
