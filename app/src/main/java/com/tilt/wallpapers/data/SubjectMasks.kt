package com.tilt.wallpapers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** On-device subject segmentation (ML Kit) used by the depth effect. */
object SubjectMasks {
    private const val TAG = "SubjectMasks"
    private const val MAX_INPUT = 1024

    private fun client(): SubjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
    )

    suspend fun ensureModule(context: Context): Boolean = runCatching {
        val segmenter = client()
        try {
            val installer = ModuleInstall.getClient(context)
            if (installer.areModulesAvailable(segmenter).await().areModulesAvailable()) return@runCatching true
            installer.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
            // The request returns once queued; wait for the download to land.
            repeat(60) {
                if (installer.areModulesAvailable(segmenter).await().areModulesAvailable()) return@runCatching true
                kotlinx.coroutines.delay(2000)
            }
            false
        } finally {
            segmenter.close()
        }
    }.onFailure { Log.w(TAG, "module install failed", it) }.getOrDefault(false)

    /** Writes a grayscale PNG where white = subject. */
    suspend fun compute(src: File, out: File): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_INPUT) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching false
        val mask = try { segment(bmp) } finally { bmp.recycle() } ?: return@runCatching false
        Repo.writeSynced(out) { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
        mask.recycle()
        true
    }.onFailure { Log.w(TAG, "segmentation failed", it) }.getOrDefault(false)

    /**
     * Segments [bmp] (the caller keeps it around 1024px) into an opaque grayscale bitmap of the same
     * size where white = subject. Null if the model failed or found nothing.
     */
    suspend fun segment(bmp: Bitmap): Bitmap? = runCatching {
        val segmenter = client()
        try {
            val result = segmenter.process(InputImage.fromBitmap(bmp, 0)).await()
            val buf = result.foregroundConfidenceMask ?: return@runCatching null
            buf.rewind()
            var any = false
            val px = IntArray(bmp.width * bmp.height) {
                val v = (buf.get() * 255f).toInt().coerceIn(0, 255)
                if (v > 128) any = true
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            if (any) Bitmap.createBitmap(px, bmp.width, bmp.height, Bitmap.Config.ARGB_8888) else null
        } finally {
            segmenter.close()
        }
    }.onFailure { Log.w(TAG, "segmentation failed", it) }.getOrNull()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
