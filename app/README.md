# Tilt Wallpapers: developer notes

Kotlin + Jetpack Compose app with an OpenGL ES 3.0 live wallpaper. One renderer draws both the wallpaper and every in-app preview, so what you see in the editor is what the wallpaper shows.

## Requirements

- Android Studio (recent) or the command line, JDK 17
- Android SDK 36 (compileSdk / targetSdk 36, **minSdk 29**)
- AGP 8.13, Kotlin 2.2, Compose BOM 2025.12

## Build

```bash
./gradlew assembleDebug        # debug APK, no signing needed
./gradlew assembleRelease      # needs signing (below); R8 + resource shrinking on
```

Outputs: `app/build/outputs/apk/{debug,release}/`.

### Release signing

Release builds read `keystore.properties` at the project root (git-ignored):

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it the release build is unsigned. **Keep a private backup of the keystore**: losing it means no updates for installed copies.

## Releasing a version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (every visible fix gets a new version).
2. Add the entry to the changelog (`notes/CHANGELOG.md`, kept out of git) and to `releases` in `ui/screens/AboutSection.kt` (the in-app **"Có gì mới" / "What's new"**).
3. Every new Vietnamese UI string goes through `t("…")` with an English entry in `data/En.kt`. In docs and issues, name UI items in both languages, e.g. **"Bám chủ thể" / "Hug the subject"**.
4. `./gradlew assembleRelease`, check the version with `aapt2 dump badging`.
5. Tag `vX.Y.Z` and attach the APK to a GitHub Release.

## Layout

```
app/src/main/java/com/tilt/wallpapers/
├── data/        state and logic, no UI
│   ├── Models.kt       AppState, WallSet, WallImage, Settings (all @Serializable, saved as state.json)
│   ├── Repo.kt         single source of truth (StateFlow), files, import/export, clock & text fitting
│   ├── Clock.kt        ClockStyle / ClockPlacement, ClockText (draws the clock texture), ClockPlace (placement, "Bám chủ thể" / "Hug the subject")
│   ├── TextArt.kt      free texts: TextItem / TextLayer, layout & texture, TextPlace (walls, auto place)
│   ├── Fonts.kt        user fonts (.ttf/.otf): import, weight axis from 'fvar'
│   ├── Backup.kt       .tilt full backup (zip + manifest)
│   ├── I18n.kt, En.kt  t(vi) → English map; Vietnamese is the source language
│   └── Automation.kt, SubjectMasks.kt, SubjectLayout.kt, Grading.kt, ColorExtract.kt
├── gl/          rendering
│   ├── TiltRenderer.kt one renderer for wallpaper + previews: tilt, transitions, textures, clock binding
│   ├── Shaders.kt      single fragment shader specialised by #define EFFECT / TRANS / DEPTH / CLOCK
│   ├── TextGl.kt       free-text textures and per-character data
│   ├── GlThread.kt     EGL context + Choreographer-driven render thread (draws only on demand)
│   └── EffectThumbs.kt, ShadowMap.kt
├── wallpaper/   TiltWallpaperService (the live wallpaper engine), scheduled rotation worker
├── tilt/        gravity sensor, tilt → picture navigation
├── tile/        Quick Settings tile, app shortcuts
└── ui/          Compose screens; components/Studio.kt is the shared editor layout
app/src/main/assets/  bundled fonts and their licences
```

## How the renderer works

- `GlThread` renders **only when asked** (`requestRender`) or while something animates; a phone lying still draws nothing. Keep it that way: no continuous effects.
- The fragment shader is compiled per combination of effect / transition / depth / clock (cached by an int key).
- **Clock texture** is RGBA bytes, not premultiplied: `r` glyphs, `g` halo of digits 0 and 2, `b` of digits 1 and 3, `a` of colon and date. Neighbouring digits never share a halo layer, so each digit can shrink around the subject with its own shadow.
- **Free texts**: one texture with a strip per line (`r` glyphs, `g/b/a` halos of every third character) plus an `RGBA32F` data texture (row 0: cell boxes, row 1: strip, halo channel, kept height per image).
- Texture units: 0–5 image A/B (picture, mask, shadow), 6–7 clock A/B, 8–9 texts. **Any `glBindTexture` outside the per-frame bind block must set its own `glActiveTexture`**, or it replaces whatever texture was left on the active unit.
- Clock / text textures are keyed only on what changes glyphs (font, weight, fill, text, size), not colour or shadow, which are uniforms.

## Checking shaders

Shader errors only show at runtime on device, so compile every combination offline with `glslc` from the NDK (`ndk/<ver>/shader-tools/<os>/glslc`): extract the `FRAGMENT` string from `Shaders.kt`, replace `#version 300 es` with `#version 450`, prepend the four `#define`s, and run

```bash
glslc --target-env=opengl -fauto-bind-uniforms -fauto-map-locations -fshader-stage=frag shader.frag -o /dev/null
```

for EFFECT 1–8 × TRANS 0–10 × DEPTH 0–1 × CLOCK 0–1 (352 variants). Keep the shader source ASCII.

## Conventions

- Minimal permissions: the app declares none itself (the merged ones come from WorkManager and ML Kit). Use system pickers (`OpenDocument` / `CreateDocument`), never storage permissions.
- State changes go through `Repo` (`updateSet`, `updateImage`, …); saving is debounced and atomic (temp file + fsync + rename).
- Heavy work (decoding, texture drawing, coverage grids) runs off the main and GL threads.
- Comments explain *why*; match the surrounding code's style.

## Testing

No automated UI tests. Check on a real device or emulator: open **"Đồng hồ & chữ" / "Clock & text"** (Hiệu ứng / Effects tab), the image editor (**"Chỉnh sửa" / "Edit"**), **"Xem thử" / "Preview"** in a set, and set the live wallpaper; watch `adb logcat` for `FATAL` and `TiltRenderer` (shader compile / link errors are logged there).
