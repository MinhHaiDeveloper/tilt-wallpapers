package com.tilt.wallpapers.data

import kotlinx.serialization.Serializable

/** Where an image sits on the tilt axis. Order inside LEFT/RIGHT = distance from the neutral image. */
@Serializable
enum class Side(private val vi: String) { LEFT("Trái"), CENTER("Giữa"), RIGHT("Phải"); val label: String get() = t(vi) }

@Serializable
enum class FilterStyle(private val vi: String) {
    NONE("Gốc"), VIVID("Rực rỡ"), WARM("Ấm"), COOL("Lạnh"), FADE("Phai"),
    SEPIA("Sepia"), MONO("Đơn sắc"), NOIR("Noir"), DRAMA("Kịch tính");

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

/**
 * The subject layer of an image: placement of an external subject (offsets are fractions of the
 * screen, rotation in degrees) plus the drop shadow and background blur used by every subject.
 */
@Serializable
data class SubjectTransform(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val dropShadow: Boolean = false,
    val shadowOpacity: Float = 0.5f,
    val shadowBlur: Float = 0.5f,
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 0.012f,
    val backgroundBlur: Float = 0f,
) {
    companion object {
        /** Size of an external subject at scale 1 as fractions of the screen: fits inside 80% of it. */
        fun baseSize(subjectAspect: Float, viewAspect: Float): Pair<Float, Float> {
            var w = 0.8f
            var h = w * viewAspect / subjectAspect
            if (h > 0.8f) { h = 0.8f; w = h * subjectAspect / viewAspect }
            return w to h
        }
    }

    /**
     * The same on-screen placement after the subject's trim box changed from [oldBox] to [newBox]
     * (both left, top, width, height in source pixels): same pixel size, same spot for unchanged pixels.
     */
    fun compensated(oldBox: List<Int>, newBox: List<Int>, viewAspect: Float): SubjectTransform {
        if (oldBox.size < 4 || newBox.size < 4 || oldBox == newBox) return this
        val (ol, ot, ow, oh) = oldBox
        val (nl, nt, nw, nh) = newBox
        val bw0 = baseSize(ow.toFloat() / oh, viewAspect).first
        val bw1 = baseSize(nw.toFloat() / nh, viewAspect).first
        val k = bw0 * scale / ow                     // screen widths per source pixel
        var dx = (nl + nw / 2f) - (ol + ow / 2f)
        var dy = (nt + nh / 2f) - (ot + oh / 2f)
        if (flipHorizontal) dx = -dx
        if (flipVertical) dy = -dy
        val r = Math.toRadians(rotation.toDouble())
        val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
        return copy(
            offsetX = offsetX + (dx * c - dy * s) * k,
            offsetY = offsetY + (dx * s + dy * c) * k * viewAspect,
            scale = k * nw / bw1,
        )
    }
}

/** Non-destructive edits. Crop is a normalized rect of the source image. */
@Serializable
data class ImageEdit(
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropW: Float = 1f,
    val cropH: Float = 1f,
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val vignette: Float = 0f,
    val filter: FilterStyle = FilterStyle.NONE,
    val subject: SubjectTransform = SubjectTransform(),
)

@Serializable
data class WallImage(
    val id: String,
    val width: Int,
    val height: Int,
    val side: Side,
    val edit: ImageEdit = ImageEdit(),
    val hasMask: Boolean = false,
    /** A user-painted mask in files/manual-masks, used instead of the AI mask. */
    val hasManualMask: Boolean = false,
    /** An external RGBA subject in files/subjects, used instead of any mask. */
    val hasSubject: Boolean = false,
    val subjectAspect: Float = 1f,
    /** Where the trimmed subject sits in its source picture: left, top, width, height (source pixels). */
    val subjectBox: List<Int> = emptyList(),
    /** Bumped whenever a mask or subject file changes so cached textures reload. */
    val subjectRev: Int = 0,
    /** Added while "Tự căn theo chủ thể" was on: crop to the subject once its mask is ready. */
    val autoCropPending: Boolean = false,
    /** Up to 6 theme colours picked from the edited picture, best first; [paletteKey] is the edit they came from. */
    val palette: List<Int> = emptyList(),
    val paletteKey: Int = 0,
    /** Where this picture shows its set's clock; null until placed. */
    val clock: ClockPlacement? = null,
    /** Where this picture shows each of its set's free texts, by [TextItem.id]. */
    val texts: Map<String, ClockPlacement> = emptyMap(),
)

@Serializable
data class WallSet(
    val id: String,
    val name: String,
    val images: List<WallImage> = emptyList(),
    val createdAt: Long = 0L,
    val clock: ClockStyle = ClockStyle(),
    /** Up to two free texts, sharing one layer. */
    val texts: TextLayer = TextLayer(),
) {
    fun side(side: Side) = images.filter { it.side == side }

    /**
     * Images ordered from far-left to far-right and the index shown when the phone is held level.
     * Left images are stored nearest-first, so they are reversed here.
     */
    fun tiltSequence(): Pair<List<WallImage>, Int> {
        val left = side(Side.LEFT)
        val center = images.firstOrNull { it.side == Side.CENTER }
        val right = side(Side.RIGHT)
        val seq = left.asReversed() + listOfNotNull(center) + right
        val neutral = if (center != null || right.isNotEmpty()) left.size else (left.size - 1).coerceAtLeast(0)
        return seq to neutral
    }
}

@Serializable
enum class Effect(private val vi: String) {
    ORIGINAL("Gốc"), NARROW_REEDED("Kính sọc mảnh"), WIDE_REEDED("Kính sọc rộng"),
    LUMINA("Lumina"), GRID("Lưới kính"), LIQUID("Chất lỏng"), RIPPLE("Gợn sóng");

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

@Serializable
enum class Transition(private val vi: String, val directional: Boolean) {
    CROSSFADE("Mờ dần", false),
    FADE_BLACK("Qua nền tối", false),
    SOFT_WIPE("Fade theo hướng", true),
    SLIDE("Trượt", true),
    ZOOM("Phóng to", false),
    CIRCLE("Vòng tròn", true),
    LIQUID("Gợn nước", true),
    DISSOLVE("Tan biến", false),
    FOLD("Flip", true);

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

@Serializable
enum class Direction(private val vi: String) {
    AUTO("Theo nghiêng"), LEFT_TO_RIGHT("Trái → phải"), RIGHT_TO_LEFT("Phải → trái"),
    TOP_TO_BOTTOM("Trên → dưới"), BOTTOM_TO_TOP("Dưới → trên");

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

@Serializable
enum class TiltMode(private val vi: String) { ZONES("Theo góc"), STEPS("Từng bước"); val label: String get() = t(vi) }

@Serializable
enum class ThemeMode(private val vi: String) { SYSTEM("Hệ thống"), LIGHT("Sáng"), DARK("Tối"); val label: String get() = t(vi) }

/** Where the app's Material You colours come from. */
@Serializable
enum class ColorSource(private val vi: String) { SET("Bộ ảnh"), SYSTEM("Hình nền máy"), CUSTOM("Tự chọn"); val label: String get() = t(vi) }

@Serializable
data class Schedule(
    val hour: Int,
    val setId: String,
    val enabled: Boolean = true,
    val minute: Int = 0,
) {
    val minuteOfDay get() = hour * 60 + minute
}

@Serializable
enum class NightMode(private val vi: String) { OFF("Tắt"), SYSTEM_DARK("Theo chế độ tối"), HOURS("Theo giờ"); val label: String get() = t(vi) }

@Serializable
data class Settings(
    val effect: Effect = Effect.ORIGINAL,
    val effectStrength: Float = 0.8f,
    val transition: Transition = Transition.SOFT_WIPE,
    val direction: Direction = Direction.AUTO,
    val durationMs: Int = 650,
    /** "Theo góc" only: the transition follows the tilt angle instead of playing on its own. */
    val followTilt: Boolean = false,
    /** Flip tuning: how dark the next picture starts, blur strength, how soon the blur arrives
     *  (fraction of the move) and how see-through the panel's free edge gets. */
    val foldDim: Float = 0.35f,
    val foldBlur: Float = 1f,
    val foldOnset: Float = 0.25f,
    val foldClear: Float = 0.85f,
    val tiltMode: TiltMode = TiltMode.ZONES,
    val tiltRange: Float = 35f,
    val invertTilt: Boolean = false,
    val parallax: Boolean = true,
    val rotateSubject: Boolean = false,
    val depth: Boolean = false,
    val depthStrength: Float = 0.6f,
    val shake: Boolean = false,
    /** Tilt no longer changes the picture (parallax still runs); "Ảnh tiếp" still does. */
    val tiltLock: Boolean = false,
    /** Lying on one side: a sideways angle held for a few seconds becomes the new "level". */
    val tiltAutoLevel: Boolean = true,
    /** Raw roll (degrees) the user set as level with "Đặt góc hiện tại làm thẳng". */
    val tiltOffset: Float = 0f,
    /** New pictures get their crop centred on their subject. */
    val autoCropSubject: Boolean = true,
    val schedules: List<Schedule> = emptyList(),
    /** Bộ ảnh tab shows sets as a list instead of a grid. */
    val setsList: Boolean = false,
    /** Where the clock layer shows (the phone's own lock-screen clock can't be hidden by a wallpaper). */
    val clockShowOn: ClockShowOn = ClockShowOn.HOME,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val scheduleEnabled: Boolean = false,
    /** The schedule slot already applied ("day:minute"), so a set picked by hand stays until the next slot. */
    val scheduleSlot: String = "",
    val batterySaver: Boolean = true,
    val batteryThreshold: Int = 15,
    val night: NightMode = NightMode.OFF,
    val nightStart: Int = 22 * 60,
    val nightEnd: Int = 6 * 60,
    val nightDim: Float = 0.35f,
    /** 0 = parallax follows the hand quickly, 1 = it drifts slowly and smoothly. */
    val smoothing: Float = 0.5f,
    /** versionCode whose "what's new" the user has seen. */
    val seenVersion: Int = 0,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.SET,
    /** Primary and two secondary seed colours (ARGB) for [ColorSource.CUSTOM]. */
    val customColors: List<Int> = emptyList(),
    /** Motion when the screen turns on / the phone unlocks. */
    val unlockAnim: UnlockAnim = UnlockAnim.LIGHT,
    /** "Xem trước" opens with its buttons hidden ("Ẩn giao diện"). */
    val previewHideUi: Boolean = false,
)

@Serializable
enum class UnlockAnim(private val vi: String, val amount: Float) {
    OFF("Tắt", 0f), LIGHT("Nhẹ", 0.5f), STRONG("Rõ", 1f);
    val label: String get() = t(vi)
}

/** A deleted set, or a picture deleted from a set, kept for [TRASH_DAYS] days. Its files stay on disk until then. */
@Serializable
data class TrashItem(
    val id: String,
    val deletedAt: Long,
    val set: WallSet? = null,
    val image: WallImage? = null,
    val fromSetId: String? = null,
    val fromSetName: String? = null,
    /** Where a deleted set sat in the list and whether it was the active one, so restoring puts it back. */
    val position: Int = -1,
    val wasActive: Boolean = false,
) {
    val images: List<WallImage> get() = set?.images ?: listOfNotNull(image)

    companion object { const val TRASH_DAYS = 7 }
}

@Serializable
data class AppState(
    val sets: List<WallSet> = emptyList(),
    val activeSetId: String? = null,
    val settings: Settings = Settings(),
    val trash: List<TrashItem> = emptyList(),
    /** Fonts the user added, for clocks and texts. */
    val fonts: List<CustomFont> = emptyList(),
) {
    val activeSet: WallSet? get() = sets.find { it.id == activeSetId } ?: sets.firstOrNull()
}
