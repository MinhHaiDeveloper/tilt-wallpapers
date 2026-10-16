package com.tilt.wallpapers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface DepthStatus {
    data object Idle : DepthStatus
    data object Downloading : DepthStatus
    data class Processing(val done: Int, val total: Int) : DepthStatus
    data class Failed(val message: String) : DepthStatus
}

/**
 * Single source of truth for sets, images and settings. Lives in the app process, which the
 * wallpaper service shares, so edits made in the UI reach the live wallpaper immediately.
 */
object Repo {
    private const val TAG = "Repo"
    private const val MAX_IMAGE = 2560
    private const val THUMB = 480
    private const val MAX_SUBJECT = 1600
    private const val MASK_EDIT_MAX = 1024

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** One-off requests for the live wallpaper ("Ảnh tiếp" from the tile, shortcuts). */
    private val _commands = kotlinx.coroutines.flow.MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands: kotlinx.coroutines.flow.SharedFlow<Command> = _commands
    enum class Command { NEXT_IMAGE, PREVIEW_UNLOCK }
    fun nextImage() { _commands.tryEmit(Command.NEXT_IMAGE) }
    /** Plays the unlock motion on the in-app previews (after picking a style). */
    fun previewUnlock() { _commands.tryEmit(Command.PREVIEW_UNLOCK) }

    fun setTiltLock(on: Boolean) = updateSettings { it.copy(tiltLock = on) }

    private val _depth = MutableStateFlow<DepthStatus>(DepthStatus.Idle)
    val depthStatus: StateFlow<DepthStatus> = _depth.asStateFlow()
    private var maskJob: Job? = null

    private val stateFile get() = File(app.filesDir, "state.json")
    private fun dir(name: String) = File(app.filesDir, name).apply { mkdirs() }
    fun imageFile(id: String) = File(dir("images"), "$id.jpg")
    fun thumbFile(id: String) = File(dir("thumbs"), "$id.jpg")
    fun maskFile(id: String) = File(dir("masks"), "$id.png")
    fun manualMaskFile(id: String) = File(dir("manual-masks"), "$id.png")
    fun subjectFile(id: String) = File(dir("subjects"), "$id.png")
    /** Source picture of an external subject, its editable alpha mask, and the first mask (for reset). */
    fun subjectSrcFile(id: String) = File(dir("subjects-src"), "$id.png")
    fun subjectMaskFile(id: String) = File(dir("subjects-mask"), "$id.png")
    fun subjectMaskOrigFile(id: String) = File(dir("subjects-mask-orig"), "$id.png")
    private fun subjectFiles(id: String) = listOf(subjectFile(id), subjectSrcFile(id), subjectMaskFile(id), subjectMaskOrigFile(id))

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        runCatching { if (stateFile.exists()) _state.value = json.decodeFromString(stateFile.readText()) }
            .onFailure { Log.e(TAG, "state load failed", it) }
        emptyExpiredTrash()
        refreshLanguage()
        scope.launch { healThumbnails() }
        scope.launch {
            _state.drop(1).debounce(250).collect { s ->
                runCatching {
                    val tmp = File(app.filesDir, "state.json.tmp")
                    // Synced before the rename, so a sudden shutdown leaves either the old or the new state, never a lost one.
                    java.io.FileOutputStream(tmp).use { out ->
                        out.write(json.encodeToString(AppState.serializer(), s).toByteArray())
                        out.fd.sync()
                    }
                    check(tmp.renameTo(stateFile))
                }.onFailure { Log.e(TAG, "state save failed", it) }
            }
        }
        if (_state.value.settings.depth) ensureMasks()
        updateScheduledRotation()
        applySchedule()
        scope.launch {
            // Theme colours follow the active set's middle picture (as edited).
            _state.map { centerImage(it) }.distinctUntilChanged().collect { img ->
                if (img != null && (img.palette.isEmpty() || img.paletteKey != img.edit.toString().hashCode())) computePalette(img)
            }
        }
        scope.launch {
            // Sets with a clock: place it on every picture that needs it (new picture, new subject, new shape).
            _state.debounce(300).collect { fitClocks(it) }
        }
    }

    // ---- clock ----------------------------------------------------------------------------------

    val context: Context get() = app

    fun updateClock(setId: String, f: (ClockStyle) -> ClockStyle) = updateSet(setId) { it.copy(clock = f(it.clock)) }

    /** A hand-placed clock for one picture (kept until "Tự đặt"). */
    fun setClockPlacement(imageId: String, p: ClockPlacement?) = updateImage(imageId) { it.copy(clock = p) }

    /** "Tự đặt" for every picture of a set: hand placements are dropped and refitted. */
    fun refitClock(setId: String) = updateSet(setId) { s -> s.copy(images = s.images.map { it.copy(clock = it.clock?.copy(auto = true, key = 0)) }) }

    /** What decides where the clock fits a picture. */
    fun clockFitKey(img: WallImage, style: ClockStyle, depth: Boolean, aspect: Float = screenAspect()) = java.util.Objects.hash(
        style.shapeKey(), img.subjectRev, img.hasMask && depth, img.hasManualMask && depth, img.hasSubject,
        img.edit.cropX, img.edit.cropY, img.edit.cropW, img.edit.cropH, img.edit.subject, aspect,
    )

    /** What decides where the free texts go on a picture (its hand placements reset their key themselves). */
    private fun textFitKey(set: WallSet, img: WallImage, depth: Boolean, aspect: Float) = java.util.Objects.hash(
        set.texts, set.clock.enabled && set.texts.avoidClock, if (set.texts.avoidClock) img.clock?.copy(key = 0, cover = 0f) else null,
        set.clock.shapeKey(), img.subjectRev, img.hasMask && depth, img.hasManualMask && depth, img.hasSubject,
        img.edit.cropX, img.edit.cropY, img.edit.cropW, img.edit.cropH, img.edit.subject, aspect,
        img.texts.mapValues { (_, p) -> if (p.auto) null else p.copy(sq = emptyList(), key = 0, forcedTop = false) },
    )

    private fun fitClocks(s: AppState) {
        val aspect = screenAspect()
        val textShapes = HashMap<TextItem, Pair<ClockText.Shape, Float>?>()
        for (set in s.sets) {
            if (!set.clock.enabled && set.texts.items.isEmpty()) continue
            val shapes = HashMap<ClockLayout, ClockText.Shape>()
            // "Theo ảnh" gradient: every picture needs its own colours.
            val styles = listOf(set.clock.takeIf { it.enabled }) + set.texts.items.map { it.style }
            if (styles.any { it != null && it.gradient.isNotEmpty() && it.gradientAuto }) for (img in set.images) {
                if (img.palette.isEmpty() || img.paletteKey != img.edit.toString().hashCode()) computePalette(img)
            }
            for (img in set.images) {
                // The subject's grid is read at most once per picture, for the clock and the texts.
                var cov: ClockPlace.Coverage? = null; var covRead = false
                fun cov(): ClockPlace.Coverage? { if (!covRead) { cov = ClockPlace.coverage(img, s.settings.depth, aspect); covRead = true }; return cov }
                val p = img.clock
                val style = set.clock.copy(layout = p?.layout ?: set.clock.layout,
                    hug = (p?.hug ?: set.clock.hug) && !(p?.layer == 2 || (p?.layer == 0 && set.clock.onTop)))
                fun shape() = shapes.getOrPut(style.layout) { ClockText.shape(app, style) }
                var clockNow = p
                if (set.clock.enabled) {
                    val key = clockFitKey(img, style, s.settings.depth, aspect)
                    if (p == null || p.key != key) {
                        // Measured only for a picture that needs fitting (this runs after every change).
                        val shape = shape()
                        val fit = if (p != null && !p.auto) {
                            // Hand-placed: keep it, refresh how its digits shrink around the (new) subject.
                            val q = ClockPlace.onScreen(p, shape, aspect)
                            if (style.hug) ClockPlace.hug(cov(), q, shape, aspect).copy(key = key) else q.copy(key = key, sq = emptyList(), hx = 1f)
                        } else ClockPlace.fit(cov(), style, shape, aspect).copy(auto = true, key = key, layout = p?.layout, layer = p?.layer ?: 0, hug = p?.hug)
                        updateImage(img.id) { if (it.clock == p) it.copy(clock = fit) else it }
                        clockNow = fit
                    }
                }
                if (set.texts.items.isEmpty()) continue
                val withClock = img.copy(clock = clockNow)
                val tkey = textFitKey(set, withClock, s.settings.depth, aspect)
                if (set.texts.items.all { img.texts[it.id]?.key == tkey } && img.texts.size == set.texts.items.size) continue
                val placed = TextPlace.fitImage(set, withClock, cov(), aspect, tkey,
                    { item -> textShapes.getOrPut(item) { TextArt.shape(app, item) } }, { if (clockNow != null) shape() else null })
                updateImage(img.id) { if (it.texts == img.texts) it.copy(texts = placed) else it }
            }
        }
    }

    // ---- free texts -------------------------------------------------------------------------------

    fun updateTexts(setId: String, f: (TextLayer) -> TextLayer) = updateSet(setId) { it.copy(texts = f(it.texts)) }

    fun updateText(setId: String, textId: String, f: (TextItem) -> TextItem) =
        updateTexts(setId) { l -> l.copy(items = l.items.map { if (it.id == textId) f(it) else it }) }

    /** A new text, looking like the set's clock when it has one. Returns its id (null when there are two already). */
    fun addText(setId: String): String? {
        val set = _state.value.sets.find { it.id == setId } ?: return null
        if (set.texts.items.size >= 2) return null
        val id = UUID.randomUUID().toString()
        val look = if (set.clock.enabled) TextItem(id).style.lookOf(set.clock) else TextItem(id).style
        updateTexts(setId) { it.copy(items = it.items + TextItem(id, "Text", style = look)) }
        return id
    }

    /** "Tự đặt tất cả" for the texts: hand placements dropped, every picture placed again. */
    fun refitTexts(setId: String) = updateSet(setId) { s ->
        s.copy(images = s.images.map { img -> img.copy(texts = img.texts.mapValues { (_, p) -> p.copy(auto = true, key = 0, sx = 1f) }) })
    }

    fun removeText(setId: String, textId: String) = updateTexts(setId) { it.copy(items = it.items.filterNot { t -> t.id == textId }) }

    /** A hand placement of one text on one picture (null = back to automatic). */
    fun setTextPlacement(imageId: String, textId: String, p: ClockPlacement?) = updateImage(imageId) { img ->
        img.copy(texts = if (p == null) img.texts - textId else img.texts + (textId to p.copy(key = 0)))
    }

    // ---- user fonts ---------------------------------------------------------------------------------

    fun addFont(f: CustomFont) = _state.update { it.copy(fonts = it.fonts + f) }

    /** Deletes a user font; clocks and texts that used it go back to Smooch Sans. */
    fun deleteFont(id: String) {
        fun fix(st: ClockStyle) = if (st.customFont == id) st.copy(customFont = null, font = ClockFont.SMOOCH) else st
        _state.update { s ->
            s.copy(fonts = s.fonts.filterNot { it.id == id }, sets = s.sets.map { set ->
                set.copy(clock = fix(set.clock), texts = set.texts.copy(items = set.texts.items.map { it.copy(style = fix(it.style)) }))
            })
        }
        Fonts.file(app, id).delete()
    }

    /** Fonts a set uses. */
    fun fontsOf(set: WallSet): List<CustomFont> =
        (listOf(set.clock) + set.texts.items.map { it.style }).mapNotNull { Fonts.find(it.customFont) }.distinct()

    /** The picture shown when the phone is held level, from the active set. */
    fun centerImage(s: AppState): WallImage? {
        val (seq, neutral) = s.activeSet?.tiltSequence() ?: return null
        return seq.getOrNull(neutral)
    }

    private fun computePalette(img: WallImage) = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeFile(thumbFile(img.id).path) ?: return@runCatching
        // Look at what the wallpaper shows: the crop, with the colour grade applied.
        val e = img.edit
        val src = android.graphics.Rect(
            (e.cropX * bmp.width).toInt(), (e.cropY * bmp.height).toInt(),
            ((e.cropX + e.cropW) * bmp.width).toInt().coerceAtLeast(1), ((e.cropY + e.cropH) * bmp.height).toInt().coerceAtLeast(1),
        )
        val small = Bitmap.createBitmap(96, (96f * src.height() / src.width()).roundToInt().coerceIn(16, 256), Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(small).drawBitmap(bmp, src, android.graphics.Rect(0, 0, small.width, small.height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(Grading.matrix(e))
            })
        bmp.recycle()
        val colors = ColorExtract.swatches(small, 6)
        small.recycle()
        updateImage(img.id) { it.copy(palette = colors, paletteKey = it.edit.toString().hashCode()) }
    }.onFailure { Log.e(TAG, "palette failed", it) }

    // ---- sets -------------------------------------------------------------------------------

    fun createSet(name: String): String {
        val id = UUID.randomUUID().toString()
        _state.update { s ->
            s.copy(
                sets = s.sets + WallSet(id, name.ifBlank { t("Bộ ảnh %1\$s", s.sets.size + 1) }, createdAt = System.currentTimeMillis()),
                activeSetId = s.activeSetId ?: id,
            )
        }
        return id
    }

    fun renameSet(id: String, name: String) = updateSet(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun setActive(id: String) = _state.update { it.copy(activeSetId = id) }

    /** Moves sets to the trash (restorable for 7 days). */
    fun deleteSets(ids: Set<String>) {
        val now = System.currentTimeMillis()
        val cur = _state.value
        val items = cur.sets.withIndex().filter { it.value.id in ids }.map { (i, set) ->
            TrashItem(UUID.randomUUID().toString(), now, set = set, position = i, wasActive = set.id == cur.activeSet?.id)
        }
        if (items.isEmpty()) return
        _state.update { s ->
            val left = s.sets.filterNot { it.id in ids }
            s.copy(
                sets = left,
                activeSetId = s.activeSetId.takeIf { a -> left.any { it.id == a } } ?: left.firstOrNull()?.id,
                // Schedule entries for deleted sets go too.
                settings = s.settings.copy(schedules = s.settings.schedules.filterNot { it.setId in ids }),
                trash = items + s.trash,
            )
        }
        announceDeleted(items, if (items.size == 1) t("Đã xóa \"%1\$s\"", items[0].set!!.name) else t("Đã xóa %1\$s bộ ảnh", items.size))
    }

    /** Exports set as ZIP to given output stream. Returns success. */
    fun exportSet(setId: String, outputStream: java.io.OutputStream): Boolean = runCatching {
        val set = _state.value.sets.find { it.id == setId } ?: return false
        java.util.zip.ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            val manifest = buildManifestJson(set)
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            
            set.images.forEach { img ->
                exportImage(zip, img)
            }
            fontsOf(set).forEach { f ->
                Fonts.file(app, f.id).takeIf { it.exists() }?.let { file ->
                    zip.putNextEntry(java.util.zip.ZipEntry("fonts/${f.id}.ttf"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    }.getOrElse { Log.e(TAG, "Export failed", it); false }

    /** Imports set from ZIP input stream. Returns new set ID or null. */
    fun importSet(inputStream: java.io.InputStream): String? = runCatching {
        val tempDir = File(app.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val root = tempDir.canonicalPath + File.separator
            java.util.zip.ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(tempDir, entry.name)
                    // Reject entries that would escape the temp folder (zip slip).
                    require(outFile.canonicalPath.startsWith(root)) { "bad zip entry ${entry.name}" }
                    outFile.parentFile?.mkdirs()
                    if (!entry.isDirectory) outFile.outputStream().use { zip.copyTo(it) }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            parseAndImportSet(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }.getOrElse { Log.e(TAG, "Import failed", it); null }

    private fun buildManifestJson(set: WallSet): String =
        json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("version", kotlinx.serialization.json.JsonPrimitive(1))
                put("setName", kotlinx.serialization.json.JsonPrimitive(set.name))
                // Clock and texts of the set, and the user fonts they use (the files go in fonts/).
                put("clock", json.encodeToJsonElement(ClockStyle.serializer(), set.clock))
                put("texts", json.encodeToJsonElement(TextLayer.serializer(), set.texts))
                put("fonts", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CustomFont.serializer()), fontsOf(set)))
                put("images", kotlinx.serialization.json.JsonArray(set.images.map { img ->
                    json.encodeToJsonElement(WallImage.serializer(), img)
                }))
            }
        )

    private fun exportImage(zip: java.util.zip.ZipOutputStream, img: WallImage) {
        val imgFile = imageFile(img.id)
        if (imgFile.exists()) {
            zip.putNextEntry(java.util.zip.ZipEntry("images/${img.id}.jpg"))
            imgFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        if (img.hasMask) {
            val maskFile = maskFile(img.id)
            if (maskFile.exists()) {
                zip.putNextEntry(java.util.zip.ZipEntry("masks/${img.id}.png"))
                maskFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (img.hasManualMask) {
            val manualFile = manualMaskFile(img.id)
            if (manualFile.exists()) {
                zip.putNextEntry(java.util.zip.ZipEntry("manual-masks/${img.id}.png"))
                manualFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (img.hasSubject) {
            listOf("subjects" to subjectFile(img.id), "subjects-src" to subjectSrcFile(img.id),
                "subjects-mask" to subjectMaskFile(img.id), "subjects-mask-orig" to subjectMaskOrigFile(img.id),
            ).forEach { (folder, f) ->
                if (f.exists()) {
                    zip.putNextEntry(java.util.zip.ZipEntry("$folder/${img.id}.png"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun parseAndImportSet(tempDir: File): String? {
        val manifestFile = File(tempDir, "manifest.json")
        if (!manifestFile.exists()) return null
        
        val manifestJson = json.parseToJsonElement(manifestFile.readText())
        val manifestObj = manifestJson as? kotlinx.serialization.json.JsonObject ?: return null
        val setName = manifestObj["setName"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: t("Bộ nhập")
        val imagesJson = manifestObj["images"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return null
        
        val newId = UUID.randomUUID().toString()
        val newImages = mutableListOf<WallImage>()
        
        imagesJson.forEach { imgJson ->
            val oldImg = json.decodeFromJsonElement(WallImage.serializer(), imgJson)
            val newImgId = UUID.randomUUID().toString()
            
            val srcImg = File(tempDir, "images/${oldImg.id}.jpg")
            if (srcImg.exists()) {
                copyDurable(srcImg, imageFile(newImgId))
                fun take(folder: String, dst: File) = File(tempDir, "$folder/${oldImg.id}.png").let { f ->
                    f.exists().also { if (it) copyDurable(f, dst) }
                }
                // Trust the files, not the flags: older exports may lack some of them.
                newImages.add(oldImg.copy(
                    id = newImgId,
                    hasMask = take("masks", maskFile(newImgId)),
                    hasManualMask = take("manual-masks", manualMaskFile(newImgId)),
                    hasSubject = take("subjects", subjectFile(newImgId)).also {
                        take("subjects-src", subjectSrcFile(newImgId))
                        take("subjects-mask", subjectMaskFile(newImgId))
                        take("subjects-mask-orig", subjectMaskOrigFile(newImgId))
                    },
                ))
            }
        }

        if (newImages.isEmpty()) return null

        // Thumbnails are not exported. Build them before the set appears so grids never cache a missing file.
        newImages.forEach { img ->
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(imageFile(img.id).absolutePath) ?: return@runCatching
                writeThumb(img.id, bmp)
                bmp.recycle()
            }
        }

        // Clock, texts and their fonts (older files have none: the clock stays off as before).
        val clock = manifestObj["clock"]?.let { runCatching { json.decodeFromJsonElement(ClockStyle.serializer(), it) }.getOrNull() } ?: ClockStyle()
        val texts = manifestObj["texts"]?.let { runCatching { json.decodeFromJsonElement(TextLayer.serializer(), it) }.getOrNull() } ?: TextLayer()
        val fonts = manifestObj["fonts"]?.let { runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(CustomFont.serializer()), it) }.getOrNull() }.orEmpty()
            .filter { f -> File(tempDir, "fonts/${f.id}.ttf").let { src -> src.exists() && (Fonts.file(app, f.id).exists() || runCatching { copyDurable(src, Fonts.file(app, f.id)) }.isSuccess) } }
        // Text placements stay with each picture, keyed by the texts' ids (kept).
        _state.update { s ->
            s.copy(fonts = s.fonts + fonts.filter { f -> s.fonts.none { it.id == f.id } }, sets = s.sets + WallSet(
                id = newId,
                name = t("%1\$s (nhập)", setName),
                images = newImages,
                createdAt = System.currentTimeMillis(),
                clock = clock,
                texts = texts,
            ))
        }
        if (_state.value.settings.depth) ensureMasks()

        return newId
    }

    // ---- images -----------------------------------------------------------------------------

    /** Copies the picked images into private storage. [side] null = balance between the two sides. */
    suspend fun importImages(setId: String, uris: List<Uri>, side: Side?, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        uris.forEachIndexed { i, uri ->
            runCatching { decodeAndStore(uri) }
                .onSuccess { (id, w, h) ->
                    updateSet(setId) { set ->
                        val target = side ?: autoSide(set)
                        addTo(set, WallImage(id, w, h, target, autoCropPending = _state.value.settings.autoCropSubject))
                    }
                }
                .onFailure { Log.e(TAG, "import failed $uri", it) }
            onProgress(i + 1)
        }
        if (_state.value.settings.depth) ensureMasks()
    }

    private fun decodeAndStore(uri: Uri): Triple<String, Int, Int> {
        val src = ImageDecoder.createSource(app.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val s = info.size
            val scale = MAX_IMAGE.toFloat() / max(s.width, s.height)
            if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val id = UUID.randomUUID().toString()
        writeSynced(imageFile(id)) { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        writeThumb(id, bmp)
        val result = Triple(id, bmp.width, bmp.height)
        bmp.recycle()
        return result
    }

    /** For backup restore: replaces the whole state in one step. */
    internal fun mutate(f: (AppState) -> AppState) = _state.update(f)

    /** Rebuilds a picture's grid thumbnail from its stored image. */
    internal fun rebuildThumb(id: String) = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeFile(imageFile(id).absolutePath) ?: return@runCatching
        writeThumb(id, bmp)
        bmp.recycle()
    }

    /** Every file belonging to picture [id] (except its thumbnail), with the folder it lives in. */
    internal fun pictureFiles(id: String): List<Pair<String, File>> = listOf(
        "images" to imageFile(id), "masks" to maskFile(id), "manual-masks" to manualMaskFile(id),
        "subjects" to subjectFile(id), "subjects-src" to subjectSrcFile(id),
        "subjects-mask" to subjectMaskFile(id), "subjects-mask-orig" to subjectMaskOrigFile(id),
    )

    private fun writeThumb(id: String, bmp: Bitmap) {
        val ts = THUMB.toFloat() / max(bmp.width, bmp.height)
        val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width * ts).roundToInt().coerceAtLeast(1), (bmp.height * ts).roundToInt().coerceAtLeast(1), true)
        writeSynced(thumbFile(id)) { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb !== bmp) thumb.recycle()
    }

    private fun autoSide(set: WallSet): Side = when {
        set.images.none { it.side == Side.CENTER } -> Side.CENTER
        set.side(Side.RIGHT).size <= set.side(Side.LEFT).size -> Side.RIGHT
        else -> Side.LEFT
    }

    /** Moves pictures to the trash; they go back to the same set and side when restored. */
    fun deleteImages(setId: String, ids: Set<String>) {
        val set = _state.value.sets.find { it.id == setId } ?: return
        val now = System.currentTimeMillis()
        val items = set.images.filter { it.id in ids }.map { TrashItem(UUID.randomUUID().toString(), now, image = it, fromSetId = set.id, fromSetName = set.name) }
        if (items.isEmpty()) return
        _state.update { s ->
            s.copy(
                sets = s.sets.map { if (it.id == setId) it.copy(images = canonical(it.images.filterNot { img -> img.id in ids })) else it },
                trash = items + s.trash,
            )
        }
        announceDeleted(items, if (items.size == 1) t("Đã xóa 1 ảnh") else t("Đã xóa %1\$s ảnh", items.size))
    }

    // ---- trash ---------------------------------------------------------------------------------

    /** A deletion, for an undo notice. Every one is kept (the notices stack), so each can be undone. */
    class Deleted(val itemIds: List<String>, val message: String, val at: Long = System.currentTimeMillis())
    private val _deletions = kotlinx.coroutines.flow.MutableSharedFlow<Deleted>(extraBufferCapacity = 8)
    val deletions: kotlinx.coroutines.flow.SharedFlow<Deleted> = _deletions

    private fun announceDeleted(items: List<TrashItem>, message: String) { _deletions.tryEmit(Deleted(items.map { it.id }, message)) }

    /** Moves a set in the list (drag in the list view); the quick panel follows this order. */
    fun moveSet(from: Int, to: Int) = _state.update { s ->
        if (from !in s.sets.indices || to !in s.sets.indices) s
        else s.copy(sets = s.sets.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Puts trash items back: sets as they were, pictures into their set and side (a new set if it is gone). */
    fun restoreTrash(itemIds: Collection<String>) {
        val ids = itemIds.toSet()
        _state.update { s ->
            var sets = s.sets
            var active = s.activeSetId
            // Lowest original position first, so each set lands back where it was.
            s.trash.filter { it.id in ids }.sortedWith(compareBy({ it.deletedAt }, { it.position })).forEach { item ->
                item.set?.let { set ->
                    if (sets.none { it.id == set.id }) {
                        sets = sets.toMutableList().apply { add(if (item.position in 0..size) item.position else size, set) }
                        if (item.wasActive) active = set.id
                    }
                }
                item.image?.let { img ->
                    val target = sets.find { it.id == item.fromSetId }
                    sets = if (target != null) sets.map { if (it.id == target.id) addTo(it, img) else it }
                    else sets + WallSet(item.fromSetId ?: UUID.randomUUID().toString(), item.fromSetName ?: t("Khôi phục"), listOf(img.copy(side = Side.CENTER)), System.currentTimeMillis())
                }
            }
            s.copy(sets = sets, trash = s.trash.filterNot { it.id in ids }, activeSetId = active ?: sets.firstOrNull()?.id)
        }
    }

    /** Deletes trash items for good (null = everything) and their files. */
    fun emptyTrash(itemIds: Collection<String>? = null) {
        val doomed = _state.value.trash.filter { itemIds == null || it.id in itemIds }
        if (doomed.isEmpty()) return
        val ids = doomed.map { it.id }.toSet()
        _state.update { it.copy(trash = it.trash.filterNot { t -> t.id in ids }) }
        purge(doomed.flatMap { it.images }.map { it.id }.toSet())
    }

    /** Rebuilds grid thumbnails that are missing or empty while the picture itself is fine. */
    private fun healThumbnails() {
        val s = _state.value
        (s.sets.flatMap { it.images } + s.trash.flatMap { it.images }).map { it.id }.distinct().forEach { id ->
            val t = thumbFile(id)
            if ((!t.exists() || t.length() == 0L) && imageFile(id).let { it.exists() && it.length() > 0 }) rebuildThumb(id)
        }
    }

    // ---- composition ---------------------------------------------------------------------------

    /** Width / height of the screen in portrait (the wallpaper's shape). */
    fun screenAspect(): Float {
        val wm = app.getSystemService(android.view.WindowManager::class.java)
        val (w, h) = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        return minOf(w, h).toFloat() / maxOf(w, h)
    }

    /** Fewest image pixels across a crop may have before it looks soft (80% of the screen's width). */
    fun minCropPx(): Float {
        val dm = app.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels) * 0.8f
    }

    /** A new picture's one-off crop to its subject, once the mask exists. */
    private fun applyAutoCrop(imageId: String) {
        val img = _state.value.sets.flatMap { it.images }.find { it.id == imageId } ?: return
        if (!img.autoCropPending) return
        val default = img.edit.cropX == 0f && img.edit.cropY == 0f && img.edit.cropW == 1f && img.edit.cropH == 1f
        val box = if (_state.value.settings.autoCropSubject && default) SubjectLayout.maskBox(img) else null
        updateImage(imageId) {
            it.copy(
                autoCropPending = false,
                edit = box?.let { b -> SubjectLayout.crop(it.edit, b, it.width, it.height, screenAspect(), minCropPx()) } ?: it.edit,
            )
        }
    }

    /**
     * Makes sure one picture has an AI subject mask (for "Căn theo chủ thể" when Depth is off).
     * Returns false if the model is unavailable or found nothing.
     */
    suspend fun ensureMaskFor(imageId: String): Boolean = withContext(Dispatchers.IO) {
        val img = _state.value.sets.flatMap { it.images }.find { it.id == imageId } ?: return@withContext false
        if (img.hasManualMask || (img.hasMask && maskFile(imageId).exists())) return@withContext true
        if (!SubjectMasks.ensureModule(app)) return@withContext false
        val ok = SubjectMasks.compute(imageFile(imageId), maskFile(imageId))
        if (ok) updateImage(imageId) { it.copy(hasMask = true) }
        ok
    }

    private fun emptyExpiredTrash() {
        val limit = System.currentTimeMillis() - TrashItem.TRASH_DAYS * 24L * 3600_000L
        emptyTrash(_state.value.trash.filter { it.deletedAt < limit }.map { it.id })
    }

    fun updateImage(setId: String, imageId: String, f: (WallImage) -> WallImage) =
        updateSet(setId) { set -> set.copy(images = set.images.map { if (it.id == imageId) f(it) else it }) }

    /** Moves an image to [side] at [index] (inside that side's list; null = end). */
    fun moveImage(setId: String, imageId: String, side: Side, index: Int? = null) = updateSet(setId) { set ->
        val img = set.images.find { it.id == imageId } ?: return@updateSet set
        addTo(set.copy(images = set.images - img), img.copy(side = side), index)
    }

    fun moveImages(setId: String, ids: Set<String>, side: Side) = updateSet(setId) { set ->
        var s = set
        set.images.filter { it.id in ids }.forEach { img ->
            s = addTo(s.copy(images = s.images.filterNot { it.id == img.id }), img.copy(side = side))
        }
        s
    }

    /** Reorders inside one side. */
    fun reorder(setId: String, side: Side, from: Int, to: Int) = updateSet(setId) { set ->
        val list = set.side(side).toMutableList()
        if (from !in list.indices || to !in list.indices) return@updateSet set
        list.add(to, list.removeAt(from))
        set.copy(images = canonical(set.images.filterNot { it.side == side } + list))
    }

    private fun addTo(set: WallSet, img: WallImage, index: Int? = null): WallSet {
        var images = set.images
        if (img.side == Side.CENTER) {
            // Only one neutral image: the previous one becomes the first step to the right.
            val old = images.find { it.side == Side.CENTER }
            if (old != null) {
                images = images - old
                images = images.filter { it.side != Side.RIGHT } + old.copy(side = Side.RIGHT) + images.filter { it.side == Side.RIGHT }
            }
        }
        val sideList = images.filter { it.side == img.side }.toMutableList()
        sideList.add((index ?: sideList.size).coerceIn(0, sideList.size), img)
        return set.copy(images = canonical(images.filterNot { it.side == img.side } + sideList))
    }

    private fun canonical(images: List<WallImage>): List<WallImage> {
        val centers = images.filter { it.side == Side.CENTER }
        val extra = centers.drop(1).map { it.copy(side = Side.RIGHT) }
        return images.filter { it.side == Side.LEFT } + centers.take(1) + extra + images.filter { it.side == Side.RIGHT }
    }

    private fun updateSet(id: String, f: (WallSet) -> WallSet) =
        _state.update { s -> s.copy(sets = s.sets.map { if (it.id == id) f(it) else it }) }

    private fun purge(ids: Set<String>) = scope.launch {
        val s = _state.value
        val stillUsed = (s.sets.flatMap { it.images } + s.trash.flatMap { it.images }).map { it.id }.toSet()
        ids.filterNot { it in stillUsed }.forEach {
            imageFile(it).delete(); thumbFile(it).delete(); maskFile(it).delete(); manualMaskFile(it).delete(); subjectFiles(it).forEach { f -> f.delete() }
        }
    }

    // ---- subject layer: manual masks & external subjects ------------------------------------

    /** Writes [mask] (opaque grayscale, white = subject) as the image's manual mask. */
    suspend fun saveManualMask(imageId: String, mask: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(manualMaskFile(imageId).path + ".tmp")
            writeSynced(tmp) { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(tmp.renameTo(manualMaskFile(imageId)))
            updateImage(imageId) { it.copy(hasManualMask = true, subjectRev = it.subjectRev + 1) }
        }.onFailure { Log.e(TAG, "manual mask save failed", it) }.isSuccess
    }

    fun deleteManualMask(imageId: String) {
        manualMaskFile(imageId).delete()
        updateImage(imageId) { it.copy(hasManualMask = false, subjectRev = it.subjectRev + 1) }
    }

    enum class SubjectResult { OK, CUT_BY_AI, NO_SUBJECT, FAILED }

    /**
     * Uses the picked picture as the image's subject. Pictures without transparency are cut out with the
     * on-device model first. The picture and its mask are kept so the cut can be edited by hand later;
     * the wallpaper uses the composed result trimmed to its visible pixels.
     */
    suspend fun importSubject(imageId: String, uri: Uri): SubjectResult = withContext(Dispatchers.IO) {
        runCatching {
            val src = ImageDecoder.createSource(app.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val s = info.size
                val scale = MAX_SUBJECT.toFloat() / max(s.width, s.height)
                if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
            val bmp = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
            var result = SubjectResult.OK
            val mask: Bitmap
            if (hasTransparency(bmp)) {
                mask = alphaAsMask(bmp)
            } else {
                if (!SubjectMasks.ensureModule(app)) { bmp.recycle(); return@runCatching SubjectResult.FAILED }
                val small = scaledDown(bmp, 1024)
                val seg = SubjectMasks.segment(small)
                if (small !== bmp) small.recycle()
                if (seg == null) { bmp.recycle(); return@runCatching SubjectResult.NO_SUBJECT }
                mask = softThreshold(seg)
                seg.recycle()
                result = SubjectResult.CUT_BY_AI
            }
            val composed = composeSubject(bmp, mask)
            if (composed == null) { bmp.recycle(); mask.recycle(); return@runCatching SubjectResult.NO_SUBJECT }
            writePng(subjectSrcFile(imageId), bmp)
            val storedMask = scaledDown(mask, MASK_EDIT_MAX)
            writePng(subjectMaskFile(imageId), storedMask)
            writePng(subjectMaskOrigFile(imageId), storedMask)
            if (storedMask !== mask) storedMask.recycle()
            bmp.recycle(); mask.recycle()
            writeComposed(imageId, composed)
            updateImage(imageId) {
                it.copy(hasSubject = true, subjectAspect = composed.aspect, subjectBox = composed.box, subjectRev = it.subjectRev + 1)
            }
            result
        }.onFailure { Log.e(TAG, "subject import failed", it) }.getOrDefault(SubjectResult.FAILED)
    }

    /**
     * Subjects added before 1.3.3 only have the composed picture: use it as its own source, with its alpha
     * as the mask, so they can be edited too.
     */
    suspend fun ensureSubjectSources(imageId: String) = withContext(Dispatchers.IO) {
        if (subjectSrcFile(imageId).exists() && subjectMaskFile(imageId).exists()) return@withContext
        runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(subjectFile(imageId).path) ?: return@runCatching
            val mask = scaledDown(alphaAsMask(bmp), MASK_EDIT_MAX)
            writePng(subjectSrcFile(imageId), bmp)
            writePng(subjectMaskFile(imageId), mask)
            writePng(subjectMaskOrigFile(imageId), mask)
            val box = listOf(0, 0, bmp.width, bmp.height)
            bmp.recycle(); mask.recycle()
            updateImage(imageId) { it.copy(subjectBox = box) }
        }.onFailure { Log.e(TAG, "subject migration failed", it) }
    }

    /** Result of [saveSubjectMask]: the trim boxes before and after, to keep the subject in place. */
    class SubjectChange(val oldBox: List<Int>, val newBox: List<Int>)

    /**
     * Saves a hand-edited subject mask, recomposes the subject and shifts its stored placement so the
     * visible pixels stay where they were. Null if nothing would be left visible or saving failed.
     */
    suspend fun saveSubjectMask(imageId: String, mask: Bitmap, viewAspect: Float): SubjectChange? = withContext(Dispatchers.IO) {
        runCatching {
            val src = android.graphics.BitmapFactory.decodeFile(subjectSrcFile(imageId).path, android.graphics.BitmapFactory.Options().apply { inMutable = true })
                ?: return@runCatching null
            val composed = composeSubject(src, mask) ?: run { src.recycle(); return@runCatching null }
            src.recycle()
            writePng(subjectMaskFile(imageId), mask)
            writeComposed(imageId, composed)
            val old = _state.value.sets.flatMap { it.images }.find { it.id == imageId }?.subjectBox.orEmpty()
            updateImage(imageId) {
                it.copy(
                    subjectAspect = composed.aspect, subjectBox = composed.box, subjectRev = it.subjectRev + 1,
                    edit = it.edit.copy(subject = it.edit.subject.compensated(old, composed.box, viewAspect)),
                )
            }
            SubjectChange(old, composed.box)
        }.onFailure { Log.e(TAG, "subject save failed", it) }.getOrNull()
    }

    fun removeSubject(imageId: String) {
        subjectFiles(imageId).forEach { it.delete() }
        updateImage(imageId) { it.copy(hasSubject = false, subjectBox = emptyList(), subjectRev = it.subjectRev + 1) }
    }

    private class Composed(val bitmap: Bitmap, val box: List<Int>) { val aspect get() = bitmap.width.toFloat() / bitmap.height }

    /** [src] with [mask] (white = keep, any size) as its alpha, trimmed to the visible pixels plus a border. */
    private fun composeSubject(src: Bitmap, mask: Bitmap): Composed? {
        val m = if (mask.width == src.width && mask.height == src.height) mask else Bitmap.createScaledBitmap(mask, src.width, src.height, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(src.width); val mp = IntArray(src.width)
        for (y in 0 until src.height) {
            src.getPixels(px, 0, src.width, 0, y, src.width, 1)
            m.getPixels(mp, 0, src.width, 0, y, src.width, 1)
            for (x in px.indices) px[x] = (((mp[x] shr 16) and 0xFF) shl 24) or (px[x] and 0xFFFFFF)
            out.setPixels(px, 0, src.width, 0, y, src.width, 1)
        }
        if (m !== mask) m.recycle()
        val trimmed = trimTransparent(out)
        out.recycle()
        return trimmed
    }

    private fun writeComposed(imageId: String, c: Composed) {
        writePng(subjectFile(imageId), c.bitmap)
        c.bitmap.recycle()
    }

    private fun writePng(file: File, bmp: Bitmap) {
        val tmp = File(file.path + ".tmp")
        java.io.FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.fd.sync() }
        check(tmp.renameTo(file))
    }

    /**
     * Writes [file] and syncs it to storage before returning. Pictures are the user's data: without the
     * sync, a crash or power loss right after saving can leave missing or empty files.
     */
    internal fun writeSynced(file: File, write: (java.io.OutputStream) -> Unit) {
        java.io.FileOutputStream(file).use { out -> write(out); out.flush(); out.fd.sync() }
    }

    /** Copies [src] to [dst] and syncs it to storage (restores write many files at once). */
    internal fun copyDurable(src: File, dst: File) {
        src.inputStream().use { inp -> java.io.FileOutputStream(dst).use { out -> inp.copyTo(out); out.fd.sync() } }
    }

    /** Opaque grayscale bitmap of [bmp]'s alpha (white = visible). */
    private fun alphaAsMask(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in px.indices) { val a = px[x] ushr 24; px[x] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a }
            out.setPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
        }
        return out
    }

    /** Model confidence -> alpha, with the same soft threshold the wallpaper uses for AI masks. */
    private fun softThreshold(seg: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(seg.width, seg.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(seg.width)
        for (y in 0 until seg.height) {
            seg.getPixels(px, 0, seg.width, 0, y, seg.width, 1)
            for (x in px.indices) {
                val a = ((((px[x] shr 16) and 0xFF) - 51) * 255 / 153).coerceIn(0, 255)
                px[x] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            }
            out.setPixels(px, 0, seg.width, 0, y, seg.width, 1)
        }
        return out
    }

    /**
     * True when the picture is already cut out: a real share of it is see-through. A few clear pixels
     * (an anti-aliased border, rounded corners from a screenshot) do not count, so such pictures still
     * get their background removed.
     */
    private fun hasTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val row = IntArray(bmp.width)
        val step = max(1, bmp.height / 96)
        val xStep = max(1, bmp.width / 96)
        var clear = 0; var total = 0
        for (y in 0 until bmp.height step step) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in row.indices step xStep) { total++; if ((row[x] ushr 24) < 128) clear++ }
        }
        return clear >= total * 0.03f
    }

    private fun scaledDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val s = maxDim.toFloat() / max(bmp.width, bmp.height)
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** Crops to the visible pixels plus a transparent border (keeps texture edges clean). Null if empty. */
    private fun trimTransparent(bmp: Bitmap): Composed? {
        var l = bmp.width; var t = bmp.height; var r = -1; var b = -1
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in row.indices) if ((row[x] ushr 24) > 8) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                b = y
            }
        }
        if (r < l || b < t) return null
        val pad = 4
        val w = r - l + 1; val h = b - t + 1
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(bmp, android.graphics.Rect(l, t, r + 1, b + 1), android.graphics.Rect(pad, pad, pad + w, pad + h), null)
        return Composed(out, listOf(l - pad, t - pad, w + pad * 2, h + pad * 2))
    }

    fun updateImage(imageId: String, transform: (WallImage) -> WallImage) {
        _state.update { s ->
            s.copy(sets = s.sets.map { set ->
                val updated = set.images.map { if (it.id == imageId) transform(it) else it }
                if (updated != set.images) set.copy(images = updated) else set
            })
        }
    }

    // ---- settings & depth -------------------------------------------------------------------

    /**
     * Applies the language setting; on Android 13+ a language picked for the app in the phone's settings wins
     * (it is where the user changed it last).
     */
    fun refreshLanguage() {
        val sys = I18n.fromSystem(app)
        if (sys != null && sys != _state.value.settings.language) _state.update { it.copy(settings = it.settings.copy(language = sys)) }
        I18n.apply(app, _state.value.settings.language)
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val before = _state.value.settings
        _state.update { it.copy(settings = f(it.settings)) }
        if (before.language != _state.value.settings.language) {
            I18n.apply(app, _state.value.settings.language)
            I18n.syncSystem(app, _state.value.settings.language)
        }
        if (!before.depth && _state.value.settings.depth) ensureMasks()
        val after = _state.value.settings
        if (before.schedules != after.schedules || before.scheduleEnabled != after.scheduleEnabled) {
            updateScheduledRotation()
            // A new or edited schedule takes effect right away.
            _state.update { it.copy(settings = it.settings.copy(scheduleSlot = "")) }
            applySchedule()
        }
    }

    /**
     * Switches to the set the schedule wants, once per slot: a set picked by hand stays active until the
     * next schedule time. Called by the wallpaper (on show and every minute while shown) and the worker.
     */
    fun applySchedule() {
        val s = _state.value
        if (!s.settings.scheduleEnabled) return
        val slot = Automation.activeSlot(s.settings.schedules) ?: return
        if (slot.key == s.settings.scheduleSlot) return
        val exists = s.sets.any { it.id == slot.schedule.setId }
        _state.update {
            it.copy(
                activeSetId = if (exists) slot.schedule.setId else it.activeSetId,
                settings = it.settings.copy(scheduleSlot = slot.key),
            )
        }
    }

    private fun updateScheduledRotation() {
        val st = _state.value.settings
        if (st.scheduleEnabled && st.schedules.any { it.enabled }) {
            com.tilt.wallpapers.wallpaper.ScheduledRotationWorker.schedule(app)
        } else {
            com.tilt.wallpapers.wallpaper.ScheduledRotationWorker.cancel(app)
        }
    }

    /** Computes subject masks (for the depth effect) for every image that does not have one yet. */
    fun ensureMasks() {
        if (maskJob?.isActive == true) { maskRerun = true; return }
        maskJob = scope.launch {
            val pending = _state.value.sets.flatMap { it.images }
                .filterNot { it.hasMask || it.id in maskFailed }.distinctBy { it.id }
            if (pending.isEmpty()) { _depth.value = DepthStatus.Idle; return@launch }
            _depth.value = DepthStatus.Downloading
            if (!SubjectMasks.ensureModule(app)) {
                _depth.value = DepthStatus.Failed(t("Không tải được mô hình tách chủ thể (cần Google Play Services và mạng)."))
                return@launch
            }
            pending.forEachIndexed { i, img ->
                _depth.value = DepthStatus.Processing(i, pending.size)
                val ok = maskFile(img.id).exists() || SubjectMasks.compute(imageFile(img.id), maskFile(img.id))
                if (ok) {
                    _state.update { s ->
                        s.copy(sets = s.sets.map { set -> set.copy(images = set.images.map { if (it.id == img.id) it.copy(hasMask = true) else it }) })
                    }
                    applyAutoCrop(img.id)
                } else maskFailed += img.id
            }
            _depth.value = DepthStatus.Idle
        }.also { job ->
            job.invokeOnCompletion {
                // Images imported while this ran.
                if (maskRerun) { maskRerun = false; scope.launch { ensureMasks() } }
            }
        }
    }
    @Volatile private var maskRerun = false
    private val maskFailed = java.util.Collections.synchronizedSet(HashSet<String>())
}
