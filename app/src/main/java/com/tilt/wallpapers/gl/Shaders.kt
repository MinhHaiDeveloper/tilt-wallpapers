package com.tilt.wallpapers.gl

import com.tilt.wallpapers.data.Effect
import com.tilt.wallpapers.data.Transition

/**
 * One fragment shader, specialised with #defines per (effect, transition, depth) and compiled lazily.
 * Texture space is y-down: uv (0,0) is the top-left of the screen and of every image.
 */
object Shaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    fun fragment(effect: Effect, transition: Transition?, depth: Boolean, clock: Boolean): String =
        "#version 300 es\n" +
            "#define EFFECT ${effect.ordinal + 1}\n" +
            "#define TRANS ${transition?.let { it.ordinal + 1 } ?: 0}\n" +
            "#define DEPTH ${if (depth) 1 else 0}\n" +
            "#define CLOCK ${if (clock) 1 else 0}\n" +
            FRAGMENT

    private const val FRAGMENT = """
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTexA; uniform sampler2D uMaskA; uniform sampler2D uShadowA;
uniform sampler2D uTexB; uniform sampler2D uMaskB; uniform sampler2D uShadowB;
uniform vec4 uRectA; uniform vec4 uRectB;   // crop rect inside each texture (x, y, w, h)
uniform mat4 uGradeA; uniform mat4 uGradeB; // colour matrix, offset in column 3
uniform vec3 uLookA; uniform vec3 uLookB;   // opacity, vignette, unused
// Subject layer per image: screen uv -> subject uv as two affine rows, effects
// (background blur lod, shadow opacity, unused, mode 0 none / 1 mask / 2 rgba subject)
// and the shadow offset in screen uv. The subject texture is bound to uMask*.
uniform vec3 uSubA0; uniform vec3 uSubA1; uniform vec4 uFxA; uniform vec2 uShA;
uniform vec3 uSubB0; uniform vec3 uSubB1; uniform vec4 uFxB; uniform vec2 uShB;
uniform vec3 uBg;                           // background layer: shift.xy, scale
uniform vec2 uRes;
uniform vec2 uTilt;      // -1..1, smoothed
uniform float uStrength; // effect intensity
uniform float uProgress; // transition 0..1 (eased)
uniform vec2 uDir;       // transition direction (unit, y-down)
uniform float uDim;      // night dimming 0..1
uniform vec4 uFold;      // Gập: next picture's starting shade, blur strength, blur onset, free-edge see-through
uniform float uTaps;     // blur taps in a transition (8; 4 when lightened)

const float PI = 3.14159265;

// ---------------------------------------------------------------- glass effects
// Returns the uv displacement for the background layer and writes lighting (mul, add).
vec2 glass(vec2 uv, out vec2 light) {
    light = vec2(1.0, 0.0);
    float s = uStrength;
#if EFFECT == 2 || EFFECT == 3
  #if EFFECT == 2
    float n = 34.0; float mag = 1.6;
  #else
    float n = 9.0; float mag = 3.2;
  #endif
    float x = uv.x * n;
    float f = fract(x);
    float lens = f - 0.5;
    // each reed shows a compressed slice of what is behind it, nudged by the tilt
    float dx = (lens * (mag - 1.0) * s) / n + uTilt.x * 0.012 * s;
    float dy = sin(f * PI) * 0.004 * s;
    light.x = mix(1.0, 0.86 + 0.2 * sin(f * PI), s);
    float spec = exp(-pow((f - 0.28 - uTilt.x * 0.15) * 7.0, 2.0));
    light.y = spec * 0.09 * s - smoothstep(0.9, 1.0, f) * 0.05 * s;
    return vec2(dx, dy);
#elif EFFECT == 4
    float n = 7.0;
    float f = fract(uv.x * n);
    float dx = (f - 0.5) * 0.25 * s / n + uTilt.x * 0.006 * s;
    float ew = 2.0 * n / uRes.x;
    float edge = 1.0 - smoothstep(0.0, ew, f);
    float sweep = 1.0 - clamp(abs(uv.x - 0.5 + uTilt.x * 0.35) * 1.6, 0.0, 1.0);
    light.x = 1.0 - 0.07 * s * f;
    light.y = (edge * 0.16 + sweep * 0.05 + (1.0 - f) * 0.03) * s;
    return vec2(dx, 0.0);
#elif EFFECT == 5
    float n = 6.0;
    vec2 aspect = vec2(1.0, uRes.y / uRes.x);
    vec2 p = uv * aspect * n;
    vec2 f = fract(p) - 0.5;
    float r2 = dot(f, f);
    vec2 local = f * (-0.45 * s) + f * r2 * 1.4 * s;           // magnify the centre, pull at the rims
    vec2 d = (local + uTilt * 0.08 * s) / (aspect * n);
    float rad = 0.12;
    vec2 q = abs(f) - (0.5 - rad);
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad; // rounded tile, <0 inside
    float bevel = 1.0 - smoothstep(-0.09, -0.005, sd);
    vec2 nrm = normalize(f + 1e-4);
    float lit = dot(nrm, normalize(vec2(-0.6, -0.8) - uTilt * 0.4));
    light.x = 1.0 - (1.0 - bevel) * 0.12 * s - smoothstep(-0.012, 0.0, sd) * 0.25 * s;
    light.y = (1.0 - bevel) * lit * 0.14 * s;
    return d;
#elif EFFECT == 6
    float phase = uv.x * 26.0 + sin(uv.y * 4.5 + uv.x * 3.0) * 2.4 + uTilt.x * 1.5;
    float w = sin(phase);
    vec2 d = vec2(w * 0.016, cos(phase) * 0.004) * s;
    light.x = 1.0 + w * 0.08 * s;
    light.y = pow(max(w, 0.0), 10.0) * 0.12 * s;
    return d;
#elif EFFECT == 7
    vec2 aspect = vec2(1.0, uRes.y / uRes.x);
    vec2 c = vec2(0.5, 0.42) + uTilt * vec2(0.06, 0.04);
    vec2 v = (uv - c) * aspect;
    float r = length(v);
    float k = r * 48.0;
    float fall = exp(-r * 1.2);
    vec2 d = normalize(v + 1e-5) * sin(k) * 0.013 * s * fall / aspect;
    light.x = 1.0 + cos(k) * 0.07 * s * fall;
    light.y = pow(max(cos(k), 0.0), 8.0) * 0.08 * s * fall;
    return d;
#else
    return vec2(0.0);
#endif
}

vec2 layer(vec2 uv, vec3 l) { return (uv - 0.5) * l.z + 0.5 + l.xy; }

vec3 grade(vec3 c, mat4 m) { return clamp((m * vec4(c, 1.0)).rgb, 0.0, 1.0); }

// Extra blur (mip levels) for the image being drawn; set by the fold transition.
float gBlur = 0.0;

// Off for the many blur taps of a transition: the clock is worked out once per pixel, not once per tap.
bool gClockOn = true;
// What the last image() call put down for the clock (colour, visible amount after the subject), so a blurred
// transition can lay the clock back over its frosted result once instead of in every tap.
vec3 gCkCol = vec3(0.0); float gCkVis = 0.0;
vec2 gScreen;                            // this pixel's screen uv (the clock stays put while pictures flip)
#if CLOCK == 1
// Clock layer, between the background and the subject. Texture: red = glyphs, green = soft halo.
// Each image has its own texture (one line / stacked), box and "Bám chủ thể" shape.
uniform sampler2D uClockA; uniform sampler2D uClockB;
uniform vec4 uClkA; uniform vec4 uClkB;  // clock box on screen per image (x, y, w, h)
uniform vec4 uClockColor;                // rgb, alpha (opacity x lock fade)
uniform vec4 uClockFx;                   // fill (0 solid / 1 outline / 2 glass), see-through, shadow, gradient on
uniform vec3 uClockMisc;                 // drift shift xy, follow the picture (1) or the screen (0)
uniform vec3 uGrad0; uniform vec3 uGrad1; uniform vec3 uGrad2;
uniform vec4 uColLA; uniform vec4 uColRA; uniform vec4 uColTA; uniform vec4 uColBA; uniform vec4 uTimeA;
uniform vec4 uColLB; uniform vec4 uColRB; uniform vec4 uColTB; uniform vec4 uColBB; uniform vec4 uTimeB;
uniform vec4 uSqA; uniform vec4 uSqB;    // height kept per digit column
uniform vec2 uHxA; uniform vec2 uHxB;    // width kept for the time, squeezed toward the left (0) or right (1)
uniform vec2 uStk;                       // per image: 1 = vertical clock (no colon between its columns)
uniform vec4 uDigA[4]; uniform vec4 uDigB[4]; // each digit's cell in its texture (l, t, r, b); x > 1.5 = none
// Screen-space gradients for sampling the clock texture. Squeeze and morph mappings jump at their edges, and
// letting the GPU guess the mip level there picked a blurred level and drew thin lines along those edges.
vec2 gCkDx; vec2 gCkDy;
uniform float uClockMix;                 // >= 0: progress of a layout change between the two images, < 0: none
uniform vec4 uCoreA[2]; uniform vec4 uCoreB[2]; // date and colon boxes in each texture (l, t, r, b); x > 1.5 = none
uniform vec2 uLay;                       // layout of each image: 0 horizontal, 1 vertical
uniform vec2 uTop;                       // per image: clock on top of everything (1) or behind the subject (0)
uniform float uClockOn;                  // 0: the layer only has free texts
uniform vec2 uCkAsp;                     // width / height of each image's clock texture
// How far a digit's glow reaches around its cell, in texture units: a third of the cell's height, the same
// distance across (texture units differ across and down; condensed fonts make a narrow texture).
vec2 glowReach(vec4 box, float asp) { float g = (box.w - box.y) * 0.32; return vec2(g / asp, g); }
// Free texts (up to two, sharing one layer). Texture: a strip per line, r = glyphs, g / b / a = halos of every
// third character. Data texture row 0: each character's cell (l, t, r, b) in em; row 1: its strip, halo channel and
// the height it keeps on image A and B (shrinking from its top like a clock digit).
uniform sampler2D uTxtTex; uniform highp sampler2D uTxtData;
uniform vec4 uTxtBoxA[2]; uniform vec4 uTxtBoxB[2]; // each text's block on screen per image (x, y, w, h)
uniform vec4 uTxtEm[2];                  // block size in em (w, h), first cell, cell count
uniform vec4 uTxtStrip[4];               // per line: origin in its block (em) and in the texture
uniform vec2 uTxtScale;                  // texture units per em
uniform vec4 uTxtColor[2];               // rgb, opacity x fade
uniform vec4 uTxtFx[2];                  // fill, see-through, shadow, gradient on
uniform vec3 uTxtGrad[6];
uniform vec4 uTxtAlpha;                  // shown amount: text 0 on A, B; text 1 on A, B
uniform vec4 uTxtMisc;                   // on top for image A, B; which text is in front; any text
float hxMapX(float x, vec4 tb, vec2 hx) {
    if (hx.x >= 0.999) return x;
    return hx.y < 0.5 ? tb.x + (x - tb.x) * hx.x : tb.z - (tb.z - x) * hx.x;
}
float clockSee() { return uClockFx.y; }
// Sideways squeeze of the time toward the side away from the subject.
vec2 squeezeX(vec2 cp, vec4 tb, vec2 hx) {
    if (hx.x < 0.999 && cp.y >= tb.y && cp.y <= tb.w + (tb.w - tb.y) * 0.08) {
        float m = (tb.w - tb.y) * 0.2;   // reaches past the digits to take their glow along
        if (hx.y < 0.5) {
            float xr = tb.x + (tb.z - tb.x) * hx.x;
            if (cp.x >= tb.x && cp.x <= tb.z + m) cp.x = cp.x < xr ? tb.x + (cp.x - tb.x) / hx.x : -1.0;
        } else {
            float xl = tb.z - (tb.z - tb.x) * hx.x;
            if (cp.x >= tb.x - m && cp.x <= tb.z) cp.x = cp.x > xl ? tb.z - (tb.z - cp.x) / hx.x : -1.0;
        }
    }
    return cp;
}
// Texture: r = glyphs, g = halo of digits 0 and 2, b = of digits 1 and 3, a = of the colon and date.
vec4 clockTex4(int ci, vec2 p) { return ci == 0 ? textureGrad(uClockA, p, gCkDx, gCkDy) : textureGrad(uClockB, p, gCkDx, gCkDy); }
vec2 clockTex(int ci, vec2 p) { vec4 v = clockTex4(ci, p); return vec2(v.r, max(max(v.g, v.b), v.a)); }
vec2 inUnit(vec2 p) { vec2 e = step(vec2(0.0), p) * step(p, vec2(1.0)); return vec2(e.x * e.y); }
// One column shrunk toward its top: where cp shows in the texture, or y < 0 where the column has nothing.
vec2 colShrink(vec2 cp, float t, float bot, float f) {
    if (f >= 0.999 || cp.y <= t) return cp;
    float yb = t + (bot - t) * f;
    // Inside: stretched back from the shorter column. Below it: the rest (its glow) moved up with it.
    return vec2(cp.x, cp.y < yb ? t + (cp.y - t) / f : bot + (cp.y - yb));
}
bool inBox(vec2 p, vec4 r) { return p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w; }
/**
 * "Bám chủ thể": every digit column shrinks toward its top by its own amount. Each digit is sampled through its
 * column's shrink, its glyph kept only inside its own cell and its halo taken from its own layer, so a digit never
 * shows a neighbour's glyph or (unshrunk) shadow, even where columns overlap. Halos are combined, so neighbouring
 * shadows join up.
 */
vec2 clockColumns(int ci, vec2 cp, vec4 T, vec4 B, vec4 sq, float stk) {
    // The date and colon (glyphs outside the digit cells, alpha halo) as they are.
    vec4 pl = clockTex4(ci, cp) * inUnit(cp).x;
    float r = pl.r; float h = pl.a;
    for (int d = 0; d < 4; d++) {
        vec4 box = ci == 0 ? uDigA[d] : uDigB[d];
        if (box.x < 1.5 && inBox(cp, box)) r = 0.0;
    }
    // The colon of a horizontal clock, between digits 1 and 2, shrinks with the shorter of them.
    if (stk < 0.5) {
        vec4 b1 = ci == 0 ? uDigA[1] : uDigB[1]; vec4 b2 = ci == 0 ? uDigA[2] : uDigB[2];
        float f = min(sq[1], sq[2]);
        vec2 g = glowReach(b1, ci == 0 ? uCkAsp.x : uCkAsp.y);
        float t = min(T[1], T[2]); float bt = max(B[1], B[2]);
        if (b2.x < 1.5 && f < 0.999 && cp.x > b1.z - g.x && cp.x < b2.x + g.x && cp.y > t && cp.y < bt + g.y) {
            vec2 p = colShrink(cp, t, bt, f);
            vec4 v = clockTex4(ci, p) * inUnit(p).x;
            if (cp.x > b1.z && cp.x < b2.x) r = p.y <= bt ? v.r : 0.0;
            // Below the colon's column only the date's own halo, where it really is.
            h = p.y <= bt ? v.a : (cp.y > bt ? pl.a : 0.0);
        }
    }
    vec2 acc = vec2(r, h);
    for (int d = 0; d < 4; d++) {
        vec4 box = ci == 0 ? uDigA[d] : uDigB[d];
        if (box.x > 1.5) continue;             // no such digit
        int c = stk > 0.5 ? d - (d / 2) * 2 : d;
        vec2 g = glowReach(box, ci == 0 ? uCkAsp.x : uCkAsp.y);   // all of its glow, even at full shadow
        if (cp.x < box.x - g.x || cp.x > box.z + g.x || cp.y < T[c] - g.y || cp.y > B[c] + g.y) continue;
        vec2 p = colShrink(cp, T[c], B[c], sq[c]);
        if (!inBox(p, vec4(box.xy - g, box.zw + g))) continue;
        vec4 v = clockTex4(ci, p);
        float halo = d == 0 || d == 2 ? v.g : v.b;
        acc = max(acc, vec2(inBox(p, box) ? v.r : 0.0, halo));
    }
    return acc;
}
// Layout change: each digit, the colon and the date fly from their spot in one clock to their spot in the other
// (so minutes travel diagonally between under and beside the hours); the colon fades. Each piece's spot in a
// clock is its exact glyph box there, shrunk the way that clock shows it still (a vertical clock's column shrinks
// from the hours' top, so its minutes move up too), so the change starts and ends exactly on the still clocks.
// Glyphs always come from the horizontal clock's texture (the same glyphs either way): there no two digits
// sharing a halo layer are near each other.
vec4 pieceRect(vec4 box, vec4 clk, vec4 tb, vec2 hx, float top, float f, bool time) {
    vec4 b = box;
    b.y = top + (box.y - top) * f; b.w = top + (box.w - top) * f;
    if (time) { b.x = hxMapX(b.x, tb, hx); b.z = hxMapX(b.z, tb, hx); }
    return vec4(clk.xy + b.xy * clk.zw, clk.xy + b.zw * clk.zw);
}
vec2 clockMorph(vec2 pos, float t) {
    vec2 acc = vec2(0.0);
    // Every piece flies inside the box spanning both clocks (plus room for glow): elsewhere, nothing.
    vec2 lo = min(uClkA.xy, uClkB.xy); vec2 hi = max(uClkA.xy + uClkA.zw, uClkB.xy + uClkB.zw);
    vec2 pad = (hi - lo) * 0.1;
    if (any(lessThan(pos, lo - pad)) || any(greaterThan(pos, hi + pad))) return acc;
    bool srcB = uLay.y < 0.5;                   // the horizontal one
    vec4 d0 = srcB ? uDigB[0] : uDigA[0];
    vec2 g0 = glowReach(d0, 1.0);               // glow reach down; across depends on the texture sampled
    float tTop = min(d0.y, (srcB ? uDigB[3] : uDigA[3]).y); float tBot = max(d0.w, (srcB ? uDigB[3] : uDigA[3]).w);
    for (int k = 0; k < 6; k++) {
        // 0..3 digits, 4 date, 5 colon
        vec4 ba; vec4 bb; float fa = 1.0; float fb = 1.0; float ta; float tb;
        if (k < 4) {
            int ca = uLay.x > 0.5 ? k - (k / 2) * 2 : k; int cb = uLay.y > 0.5 ? k - (k / 2) * 2 : k;
            ba = uDigA[k]; bb = uDigB[k]; fa = uSqA[ca]; fb = uSqB[cb]; ta = uColTA[ca]; tb = uColTB[cb];
        } else if (k == 4) {
            ba = uCoreA[0]; bb = uCoreB[0]; ta = ba.y; tb = bb.y;
        } else {
            ba = uCoreA[1]; bb = uCoreB[1];
            fa = min(uSqA[1], uSqA[2]); fb = min(uSqB[1], uSqB[2]);
            ta = min(uColTA[1], uColTA[2]); tb = min(uColTB[1], uColTB[2]);
        }
        bool hasA = ba.x < 1.5; bool hasB = bb.x < 1.5;
        if (!hasA && !hasB) continue;
        vec4 sa = pieceRect(ba, uClkA, uTimeA, uHxA, ta, fa, k != 4);
        vec4 sb = pieceRect(bb, uClkB, uTimeB, uHxB, tb, fb, k != 4);
        if (!hasA) { sa = sb; fa = fb; }
        if (!hasB) { sb = sa; fb = fa; }
        vec4 s = mix(sa, sb, t);
        float fy = mix(fa, fb, t);
        bool useB = hasB && (srcB || !hasA);
        vec4 src = useB ? bb : ba;
        vec2 g = vec2(g0.y / (useB ? uCkAsp.y : uCkAsp.x), g0.y);
        vec2 sc = (src.zw - src.xy) / max(s.zw - s.xy, vec2(1e-5));
        // Across the glyph box: stretched to fit; above and below it (the glow): at the unshrunk scale.
        vec2 q;
        q.x = src.x + (pos.x - s.x) * sc.x;
        q.y = pos.y < s.y ? src.y + (pos.y - s.y) * sc.y * fy
            : pos.y > s.w ? src.w + (pos.y - s.w) * sc.y * fy
            : src.y + (pos.y - s.y) * sc.y;
        if (!inBox(q, vec4(src.xy - g, src.zw + g))) continue;
        // The date and the colon share a halo layer: each keeps to its own side of the gap between them.
        if (k == 4) { if (src.y < tTop ? q.y > (src.w + tTop) * 0.5 : q.y < (tBot + src.y) * 0.5) continue; }
        if (k == 5) { vec4 dt = useB ? uCoreB[0] : uCoreA[0];
            if (dt.x < 1.5 && (dt.y < tTop ? q.y < (dt.w + tTop) * 0.5 : q.y > (tBot + dt.y) * 0.5)) continue; }
        vec4 v = useB ? textureGrad(uClockB, q, gCkDx * sc, gCkDy * sc) : textureGrad(uClockA, q, gCkDx * sc, gCkDy * sc);
        float halo = k == 0 || k == 2 ? v.g : (k < 4 ? v.b : v.a);
        vec2 o = vec2(inBox(q, src) ? v.r : 0.0, halo);
        if (!(hasA && hasB)) o *= hasB ? t : 1.0 - t;
        acc = max(acc, o);
    }
    return acc;
}
// One image's clock at this pixel: glyph (r), halo (g); cp = texture position (for the glass edge).
vec2 clockSample(int ci, vec4 clk, vec2 uv, out vec2 cp, out float y) {
    vec2 cp0 = (mix(gScreen, uv, uClockMisc.z) - uClockMisc.xy - clk.xy) / clk.zw;
    y = cp0.y;
    gCkDx = dFdx(cp0); gCkDy = dFdy(cp0);
    // Outside the clock's texture nothing can show (every mapping below only reads inside it): most of the
    // screen skips the digit loops and their texture reads.
    if (cp0.x < 0.0 || cp0.y < 0.0 || cp0.x > 1.0 || cp0.y > 1.0) { cp = cp0; return vec2(0.0); }
    if (ci == 0) {
        cp = squeezeX(cp0, uTimeA, uHxA);
        return clockColumns(0, cp, uColTA, uColBA, uSqA, uStk.x);
    }
    cp = squeezeX(cp0, uTimeB, uHxB);
    return clockColumns(1, cp, uColTB, uColBB, uSqB, uStk.y);
}
// One free text of one image at screen position [pos]: glyph (r), halo (g); [bp] = position in its block (em).
vec2 textSample(int t, int ci, vec2 pos, out vec2 bp) {
    vec4 box = ci == 0 ? uTxtBoxA[t] : uTxtBoxB[t];
    vec4 em = uTxtEm[t];
    bp = (pos - box.xy) / max(box.zw, vec2(1e-5)) * em.xy;
    vec2 dx = dFdx(bp) * uTxtScale, dy = dFdy(bp) * uTxtScale;
    if (box.z <= 0.0 || em.w < 0.5 || bp.x < -0.4 || bp.y < -0.4 || bp.x > em.x + 0.4 || bp.y > em.y + 0.4) return vec2(0.0);
    vec2 acc = vec2(0.0);
    int first = int(em.z); int n = int(em.w);
    const float g = 0.2;                         // glow reach in em (TextArt.REACH)
    for (int k = 0; k < 24; k++) {
        if (k >= n) break;
        vec4 cell = texelFetch(uTxtData, ivec2(first + k, 0), 0);
        if (bp.x < cell.x - g || bp.x > cell.z + g || bp.y < cell.y - g || bp.y > cell.w + g) continue;
        vec4 inf = texelFetch(uTxtData, ivec2(first + k, 1), 0);
        // Like a digit: shrunk toward its top, glyph only from its own cell, halo from its own layer.
        vec2 q = colShrink(bp, cell.y, cell.w + 0.12, ci == 0 ? inf.z : inf.w);
        if (!inBox(q, vec4(cell.xy - g, cell.zw + g))) continue;
        vec4 st = uTxtStrip[int(inf.x + 0.5)];
        vec4 v = textureGrad(uTxtTex, st.zw + (q - st.xy) * uTxtScale, dx, dy);
        acc = max(acc, vec2(inBox(q, cell) ? v.r : 0.0, inf.y < 0.5 ? v.g : (inf.y < 1.5 ? v.b : v.a)));
    }
    return acc;
}
vec3 textTint(int t, float y) {
    if (uTxtFx[t].w < 0.5) return uTxtColor[t].rgb;
    return y < 0.5 ? mix(uTxtGrad[t * 3], uTxtGrad[t * 3 + 1], y * 2.0) : mix(uTxtGrad[t * 3 + 1], uTxtGrad[t * 3 + 2], y * 2.0 - 1.0);
}
// Adds a layer of colour [oc] at coverage [of] over (c, v): what a blurred transition lays back once.
void over(inout vec3 c, inout float v, vec3 oc, float of) {
    float nv = of + v * (1.0 - of);
    if (nv > 0.0) c = (oc * of + c * v * (1.0 - of)) / nv;
    v = nv;
}
vec3 clockTint(float y) {
    if (uClockFx.w < 0.5) return uClockColor.rgb;
    return y < 0.5 ? mix(uGrad0, uGrad1, y * 2.0) : mix(uGrad1, uGrad2, y * 2.0 - 1.0);
}
#else
float clockSee() { return 0.0; }
#endif

// Background sample; blur > 0 widens the footprint by 2^blur pixels using the mip chain plus a small kernel.
vec3 background(sampler2D tex, vec2 tuv, vec2 gx, vec2 gy, float blur) {
    if (blur <= 0.0) return textureGrad(tex, tuv, gx, gy).rgb;
    float k = exp2(blur);
    vec2 bx = gx * k, by = gy * k;
    vec2 o1 = (bx + by) * 0.6, o2 = (bx - by) * 0.6;
    vec3 c = textureGrad(tex, tuv, bx, by).rgb * 0.4;
    c += (textureGrad(tex, tuv + o1, bx, by).rgb + textureGrad(tex, tuv - o1, bx, by).rgb
        + textureGrad(tex, tuv + o2, bx, by).rgb + textureGrad(tex, tuv - o2, bx, by).rgb) * 0.15;
    return c;
}

#if DEPTH == 1
float inside(vec2 p) { vec2 b = step(vec2(0.0), p) * step(p, vec2(1.0)); return b.x * b.y; }

// Pre-blurred shadow map (alpha), faded out near its border so nothing can end in a straight cut.
float shadowAt(sampler2D shd, vec2 p) {
    vec2 e = smoothstep(vec2(0.0), vec2(0.06), min(p, 1.0 - p));
    return texture(shd, p).a * e.x * e.y;
}
#endif

// Glass fill: the background bends along the halo's slope [grad] and lights up a little at the rims.
vec3 glassFill(sampler2D tex, vec2 tuv, vec2 gx, vec2 gy, float blur, mat4 gm, vec2 light, vec4 rect, vec2 box, vec2 grad, vec3 tint) {
    vec2 off = -grad * 0.05 * box * rect.zw * uBg.z;
    vec3 g = grade(background(tex, tuv + off, gx, gy, blur + 2.0), gm) * light.x + light.y;
    // Frosted, lifted and lightly tinted so the glass reads on any picture; bright rims where it bends.
    g = mix(g * 1.1 + 0.14, tint, 0.22) + length(grad) * 1.4 * tint;
    return clamp(g, 0.0, 1.0);
}

// One fully composed image: glass on the background, optional shadow + crisp subject on top, grading, look.
vec3 image(sampler2D tex, sampler2D msk, sampler2D shd, vec4 rect, mat4 gm, vec3 look, vec3 s0, vec3 s1, vec4 fx, vec2 sh,
           vec4 clk, int ci, vec2 uv, vec2 disp, vec2 light, vec2 gx, vec2 gy) {
    vec2 tuv = rect.xy + rect.zw * layer(uv + disp, uBg);
    vec3 c = grade(background(tex, tuv, gx, gy, fx.x + gBlur), gm) * light.x + light.y;
    float clockFill = 0.0;
    float subjA = 0.0;
    float ckShadow = 0.0; bool ckTop = false;
#if CLOCK == 1
    // Free texts: worked out first, laid under the clock where both share a layer.
    vec3 txCol[2]; float txFill[2]; float txSh[2];
    txCol[0] = vec3(0.0); txCol[1] = vec3(0.0); txFill[0] = 0.0; txFill[1] = 0.0; txSh[0] = 0.0; txSh[1] = 0.0;
    bool txTop = (ci == 0 ? uTxtMisc.x : uTxtMisc.y) > 0.5;
    int txFront = int(uTxtMisc.z + 0.5);
    if (gClockOn && uTxtMisc.w > 0.5) {
        vec2 pos = mix(gScreen, uv, uClockMisc.z) - uClockMisc.xy;
        for (int t = 0; t < 2; t++) {
            vec2 bp;
            vec2 v = textSample(t, ci, pos, bp);
            float al = uTxtColor[t].a * (t == 0 ? (ci == 0 ? uTxtAlpha.x : uTxtAlpha.y) : (ci == 0 ? uTxtAlpha.z : uTxtAlpha.w));
            txFill[t] = v.r * al;
            txSh[t] = uTxtFx[t].z * 0.4 * v.g * al;
            vec3 tint = textTint(t, clamp(bp.y / uTxtEm[t].y, 0.0, 1.0));
            vec2 grad = vec2(dFdx(v.g), dFdy(v.g)) * 2.0;
            if (uTxtFx[t].x > 1.5 && v.r + v.g > 0.0)
                tint = glassFill(tex, tuv, gx, gy, fx.x, gm, light, rect, (ci == 0 ? uTxtBoxA[t] : uTxtBoxB[t]).zw, grad, tint);
            txCol[t] = tint;
        }
        if (!txTop) for (int i = 0; i < 2; i++) { int t = i == 0 ? 1 - txFront : txFront; c *= 1.0 - txSh[t]; c = mix(c, txCol[t], txFill[t]); }
    }
    if (gClockOn && uClockOn > 0.5) {
        vec2 cp; float cy;
        vec2 ck;
        if (uClockMix < 0.0) ck = clockSample(ci, clk, uv, cp, cy);
        else {
            // Changing layout: the digit pairs move to their new places.
            vec2 pos = mix(gScreen, uv, uClockMisc.z) - uClockMisc.xy;
            gCkDx = dFdx(pos); gCkDy = dFdy(pos);
            ck = clockMorph(pos, uClockMix);
            ci = uClockMix < 0.5 ? 0 : 1;
            vec4 cb = ci == 0 ? uClkA : uClkB;
            cp = (pos - cb.xy) / cb.zw; cy = cp.y;
            gCkDx /= cb.zw; gCkDy /= cb.zw;
        }
        clockFill = ck.r * uClockColor.a;
        vec3 tint = clockTint(cy);
        ckShadow = uClockFx.z * 0.4 * ck.g * uClockColor.a;
        ckTop = (ci == 0 ? uTop.x : uTop.y) > 0.5;
        if (uClockFx.x > 1.5 && ck.r + ck.g > 0.0) {
            vec2 d = vec2(2.0) / uRes / clk.zw;
            vec2 grad = vec2(clockTex(ci, cp + vec2(d.x, 0.0)).g - clockTex(ci, cp - vec2(d.x, 0.0)).g,
                             clockTex(ci, cp + vec2(0.0, d.y)).g - clockTex(ci, cp - vec2(0.0, d.y)).g);
            gCkCol = glassFill(tex, tuv, gx, gy, fx.x, gm, light, rect, clk.zw, grad, tint);
        } else {
            gCkCol = tint;
        }
        if (!ckTop) { c *= 1.0 - ckShadow; c = mix(c, gCkCol, clockFill); }
    }
#endif
#if DEPTH == 1
    if (fx.w > 0.5) {
        vec3 h = vec3(uv, 1.0);
        vec2 su = vec2(dot(s0, h), dot(s1, h));
        if (fx.y > 0.0) {
            vec2 ss = su - vec2(dot(s0.xy, sh), dot(s1.xy, sh));
            c *= 1.0 - fx.y * shadowAt(shd, ss);
        }
        float a;
        vec3 fg;
        if (fx.w > 1.5) {
            vec4 v = texture(msk, su, gBlur);
            a = v.a * inside(su);
            fg = grade(v.rgb / max(v.a, 1e-3), gm);
        } else {
            a = smoothstep(0.2, 0.8, texture(msk, su).r);
            fg = grade(texture(tex, su, gBlur).rgb, gm);
        }
        if (!ckTop) a *= 1.0 - clockSee() * clockFill;
#if CLOCK == 1
        if (!txTop) a *= (1.0 - uTxtFx[0].y * txFill[0]) * (1.0 - uTxtFx[1].y * txFill[1]);
#endif
        subjA = a;
        c = mix(c, fg, a);
    }
#endif
#if CLOCK == 1
    // "Trên cùng": over the subject too, like an ordinary phone clock (texts under the clock).
    if (gClockOn && txTop) for (int i = 0; i < 2; i++) { int t = i == 0 ? 1 - txFront : txFront; c *= 1.0 - txSh[t]; c = mix(c, txCol[t], txFill[t]); }
    if (gClockOn && ckTop) { c *= 1.0 - ckShadow; c = mix(c, gCkCol, clockFill); }
#endif
    vec2 vp = (uv - 0.5) * uRes;
    float t = length(vp) / (0.75 * max(uRes.x, uRes.y));
    c *= 1.0 - look.y * clamp((t - 0.45) / 0.55, 0.0, 1.0);
#if CLOCK == 1
    if (gClockOn) {
        // Everything the layer put down, as seen after the subject, for a blurred transition to lay back once.
        vec3 lc = vec3(0.0); float lv = 0.0;
        for (int i = 0; i < 2; i++) { int k = i == 0 ? 1 - txFront : txFront; over(lc, lv, txCol[k], txFill[k] * (txTop ? 1.0 : 1.0 - subjA)); }
        over(lc, lv, gCkCol, clockFill * (ckTop ? 1.0 : 1.0 - subjA));
        gCkVis = lv; gCkCol = lc * look.x;
    }
#else
    if (gClockOn) { gCkVis = clockFill * (1.0 - subjA); gCkCol *= look.x; }
#endif
    return clamp(c, 0.0, 1.0) * look.x;
}

#if CLOCK == 1
  #define CLK_A uClkA, 0
  #define CLK_B uClkB, 1
#else
  #define CLK_A vec4(0.0, 0.0, 1.0, 1.0), 0
  #define CLK_B vec4(0.0, 0.0, 1.0, 1.0), 1
#endif
#define IMG_A(u, d) image(uTexA, uMaskA, uShadowA, uRectA, uGradeA, uLookA, uSubA0, uSubA1, uFxA, uShA, CLK_A, u, d, light, gxA, gyA)
#define IMG_B(u, d) image(uTexB, uMaskB, uShadowB, uRectB, uGradeB, uLookB, uSubB0, uSubB1, uFxB, uShB, CLK_B, u, d, light, gxB, gyB)

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void main() {
    vec2 uv = vUv;
    gScreen = vUv;
    vec2 light;
    vec2 disp = glass(uv, light);
    vec2 duv = dFdx(uv), dvv = dFdy(uv);
    vec2 gxA = duv * uRectA.zw * uBg.z, gyA = dvv * uRectA.zw * uBg.z;
    vec3 col;
#if TRANS == 0
    col = IMG_A(uv, disp);
#else
    vec2 gxB = duv * uRectB.zw * uBg.z, gyB = dvv * uRectB.zw * uBg.z;
    float p = uProgress;
  #if TRANS == 1
    col = mix(IMG_A(uv, disp), IMG_B(uv, disp), p);
  #elif TRANS == 2
    col = IMG_A(uv, disp) * (1.0 - smoothstep(0.0, 0.5, p)) + IMG_B(uv, disp) * smoothstep(0.5, 1.0, p);
  #elif TRANS == 3
    float s = dot(uv - 0.5, uDir) + 0.5;
    float w = 0.45;
    float m = 1.0 - smoothstep(p * (1.0 + w) - w, p * (1.0 + w), s);
    col = mix(IMG_A(uv, disp), IMG_B(uv, disp), m);
  #elif TRANS == 4
    vec2 ub = uv - uDir * (p - 1.0);
    float along = dot(ub - 0.5, uDir) + 0.5;
    if (along <= 1.0) {
        col = IMG_B(ub, disp);
        float edge = 1.0 - along;                       // distance to the leading edge
        col *= 1.0 - (1.0 - smoothstep(0.0, 0.02, edge)) * 0.25;
    } else {
        vec2 ua = uv - uDir * p * 0.3;
        float shadow = smoothstep(0.0, 0.12, along - 1.0);
        col = IMG_A(ua, disp) * (1.0 - 0.35 * p) * mix(0.6, 1.0, shadow);
    }
  #elif TRANS == 5
    vec2 ua = (uv - 0.5) / (1.0 + 0.18 * p) + 0.5;
    vec2 ub = (uv - 0.5) / (1.12 - 0.12 * p) + 0.5;
    col = mix(IMG_A(ua, disp), IMG_B(ub, disp), smoothstep(0.1, 0.9, p));
  #elif TRANS == 6
    vec2 aspect = vec2(uRes.x / uRes.y, 1.0);
    vec2 o = 0.5 - uDir * 0.5;
    float d = length((uv - o) * aspect);
    float maxR = length(max(o, 1.0 - o) * aspect);
    float e = 0.08;
    float r = p * (maxR + e);
    float m = 1.0 - smoothstep(r - e, r, d);
    col = mix(IMG_A(uv, disp), IMG_B(uv, disp), m);
  #elif TRANS == 7
    float s = dot(uv - 0.5, uDir) + 0.5;
    float q = dot(uv - 0.5, vec2(-uDir.y, uDir.x));
    float wave = sin(q * 16.0 + p * 7.0) * 0.035 + sin(q * 6.0 - p * 5.0) * 0.03;
    float w = 0.12;
    float front = p * (1.0 + w + 0.14) - w - 0.07;
    float m = 1.0 - smoothstep(front - w, front, s + wave);
    vec2 warp = uDir * (1.0 - abs(m * 2.0 - 1.0)) * 0.03;
    col = mix(IMG_A(uv - warp, disp), IMG_B(uv + warp, disp), m);
  #elif TRANS == 8
    float n = hash(floor(uv * uRes / 4.0));
    float m = smoothstep(n - 0.12, n + 0.12, p * 1.24 - 0.12);
    col = mix(IMG_A(uv, disp), IMG_B(uv, disp), m);
  #elif TRANS == 9
    // iPhone Duo fold, geometry adapted from chuspeeism/iphone-duo (MIT): A is a frosted-glass panel hinged on
    // the edge it moves toward, swinging away from a fixed eye; the further it opens, the more it frosts over
    // (like opening a frosted cover). B lies flat underneath and comes up out of a light shade.
    const float EYE = 40.0;
    const float W = 15.8;                          // panel width, in the reference's units
    float ang = min(p, 0.985) * 1.5707963;
    float ca = cos(ang), sa = sin(ang);
    vec2 across = vec2(-uDir.y, uDir.x);
    float s = dot(uv - 0.5, uDir) + 0.5;            // 1 at the hinge
    float c = dot(uv - 0.5, across);
    float q = (1.0 - s) * W;                        // projected distance from the hinge
    float den = EYE * ca - q * sa;
    float d = den > 0.0 ? EYE * q / den : 2.0 * W;  // distance along the tilted panel
    float persp = (EYE + d * sa) / EYE;             // the far part shrinks across as well
    vec2 ua = 0.5 + uDir * (0.5 - d / W) + across * c * persp;
    vec2 gxF = dFdx(ua) * uRectA.zw * uBg.z, gyF = dFdy(ua) * uRectA.zw * uBg.z;
    float edge = clamp(d / W, 0.0, 1.0);
    float motion = smoothstep(0.0, 1.0, p);
    // The panel: frosted all over, growing with the fold, a bit more toward its free edge. A disc of taps
    // over a mip-prefiltered sample keeps the blur smooth (mips alone show blocks).
    // Frosts quickly at the start of the fold (like the reference), up to ~140 px at 1080 wide.
    float frost = smoothstep(0.0, 0.6, p);
    // Like Apple's animation: the blur arrives early in the fold and holds, graded across the panel from
    // moderate at the hinge to fully frosted at the free edge, creeping further in as the fold deepens.
    float k = (0.3 + 0.7 * pow(edge, mix(1.8, 0.8, smoothstep(0.0, 1.0, p)))) * smoothstep(0.0, uFold.z, p);
    float rpx = uRes.x * k * 0.13 * uFold.y;
    gBlur = log2(max(1.0, rpx * 0.55));
    vec2 gxA0 = gxA, gyA0 = gyA;
    gxA = gxF; gyA = gyF;
    vec3 panel = vec3(0.0);
    bool showPanel = d < W * 1.1 && den > 0.0;
    gCkVis = 0.0;
    if (showPanel) panel = IMG_A(ua, disp) * 0.2;
    float panelCkVis = gCkVis; vec3 panelCkCol = gCkCol;
    gClockOn = false;
    if (showPanel && rpx > 1.0) {
        vec2 rad = vec2(rpx) / uRes;
        float spin = 0.0;                                 // fixed pattern: smooth, no grain
        for (int i = 0; i < 8; i++) {
            float fi = float(i);
            if (fi >= uTaps) break;
            float a = spin + fi * 2.3999632;                // golden angle
            float r = sqrt((fi + 0.5) / uTaps);
            panel += IMG_A(ua + vec2(cos(a), sin(a)) * r * rad, disp) * (0.8 / uTaps);
        }
    } else panel *= 5.0;
    gClockOn = true;
    // Taps skipped the clock; lay it over the frosted panel once, at full strength.
    if (showPanel && rpx > 1.0) panel = mix(panel, panelCkCol, panelCkVis);
    gxA = gxA0; gyA = gyA0;
    gBlur = 0.0;
    // Milky glass: lighter and a little paler as it opens, a soft sheen that slides across with the
    // angle, a thin shadow along the hinge crease and a touch of shade toward the far edge for depth.
    float lum = dot(panel, vec3(0.299, 0.587, 0.114));
    panel = mix(vec3(lum), panel, 1.0 + 0.12 * k);               // blur keeps (slightly lifts) the colour
    panel = mix(panel, vec3(1.0), 0.05 * k);
    float sheenAt = mix(0.15, 0.85, p);
    panel += exp(-pow((edge - sheenAt) * 4.0, 2.0)) * 0.10 * motion;
    panel *= 1.0 - 0.3 * motion * (1.0 - smoothstep(0.0, 0.05, edge));
    // Seamless like the Duo: no hard outline. Every edge of the panel fades out over a band that widens
    // as it frosts, and the picture underneath wears the same milky veil while it is hazy, so the tones meet.
    float farEdge = W * ca * EYE / (EYE + W * sa);
    float soft = 0.004 + 0.04 * frost;
    float freeDist = (farEdge - q) / W;                          // screen distance to the free edge
    float sideDist = (0.5 - abs(c * persp)) / persp;            // screen distance to the top / bottom edge
    float onPanel = den > 0.0 ? smoothstep(0.0, soft, freeDist) * smoothstep(0.0, soft * 0.6, sideDist) : 0.0;
    onPanel *= 1.0 - smoothstep(0.8, 1.0, p);                     // frosted through: the panel melts away
    // Apple's free edge darkens because nothing is behind that screen; here the next picture is, so the
    // free edge turns see-through instead, more the deeper the fold.
    onPanel *= 1.0 - uFold.w * motion * smoothstep(0.35, 1.0, edge);
    // Behind: the next picture comes into focus with the hand, from as frosted as the panel gets to sharp
    // (subject included), and from a light shade to full brightness. Only drawn where it shows.
    vec3 under = vec3(0.0);
    if (onPanel < 0.999) {
        float haze = 1.0 - smoothstep(0.65, 0.95, p);           // stays soft, then sharpens late, like Apple
        float rb = uRes.x * 0.08 * haze * uFold.y;
        gBlur = log2(max(1.0, rb * 0.55));
        gCkVis = 0.0;
        under = IMG_B(uv, disp) * 0.2;
        float underCkVis = gCkVis; vec3 underCkCol = gCkCol;
        gClockOn = false;
        if (rb > 1.0) {
            vec2 rad = vec2(rb) / uRes;
            float spin = 0.4;
            for (int i = 0; i < 8; i++) {
                float fi = float(i);
                if (fi >= uTaps) break;
                float a = spin + fi * 2.3999632;
                float r = sqrt((fi + 0.5) / uTaps);
                under += IMG_B(uv + vec2(cos(a), sin(a)) * r * rad, disp) * (0.8 / uTaps);
            }
            under = mix(under, underCkCol, underCkVis);
        } else under *= 5.0;
        gClockOn = true;
        gBlur = 0.0;
        under *= 1.0 - uFold.x * (1.0 - smoothstep(0.0, 0.9, p));
    }
    col = mix(under, clamp(panel, 0.0, 1.0), onPanel);
  #endif
#endif
    fragColor = vec4(col * (1.0 - uDim), 1.0);
}
"""
}
