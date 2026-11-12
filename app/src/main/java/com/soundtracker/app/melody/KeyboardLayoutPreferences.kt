package com.soundtracker.app.melody

import android.content.Context
import org.json.JSONObject

/**
 * Per-keyboard customizable geometry:
 * - [deckHeightDp]: total height of the keyboard deck (header strip + keys) in dp
 * - [widthFraction]: fraction of available screen width occupied by this keyboard (`0.45f..1.00f`)
 * - [keyWidthDp]: physical width of a single white key in dp (`22f..86f`)
 * - [scrollWhiteKey]: horizontal scroll offset in white keys (`0f..49f`)
 */
data class KeyboardDeckGeometry(
    val deckHeightDp: Float,
    val widthFraction: Float,
    val keyWidthDp: Float,
    val scrollWhiteKey: Float
) {
    fun coerced(): KeyboardDeckGeometry = KeyboardDeckGeometry(
        deckHeightDp = deckHeightDp.coerceIn(96f, 440f),
        widthFraction = widthFraction.coerceIn(0.45f, 1.00f),
        keyWidthDp = keyWidthDp.coerceIn(22f, 86f),
        scrollWhiteKey = scrollWhiteKey.coerceIn(0f, (TOTAL_VIRTUAL_WHITE_KEYS - 1).toFloat())
    )
}

/**
 * Complete layout configuration automatically saved and restored per unique [layoutKey],
 * e.g.:
 * - `TABLET_LANDSCAPE_SINGLE` (平板横屏 · 单键盘)
 * - `PHONE_PORTRAIT_DUAL`     (手机竖屏 · 双键盘)
 * - `TABLET_LANDSCAPE_DUAL`   (平板横屏 · 双键盘)
 * - `PHONE_PORTRAIT_SINGLE`   (手机竖屏 · 单键盘)
 */
data class KeyboardLayoutProfile(
    val layoutKey: String,
    val displayLabel: String,
    val primary: KeyboardDeckGeometry,
    val secondary: KeyboardDeckGeometry,
    val collapseScore: Boolean = false
)

data class LayoutDescriptor(
    val key: String,
    val displayLabel: String,
    val isTablet: Boolean,
    val isFoldable: Boolean,
    val isLandscape: Boolean,
    val dualKeyboard: Boolean
)

object KeyboardLayoutPreferences {
    private const val PREFS_NAME = "soundtracker_melody_keyboard_layouts"
    private const val KEY_PROFILES_JSON = "profiles_v1"
    private const val KEY_TOUCH_GUARD_JSON = "touch_guard_v1"

    fun loadTouchGuardConfig(context: Context): PianoTouchGuardConfig {
        val fallback = PianoTouchGuardConfig()
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TOUCH_GUARD_JSON, null) ?: return fallback
            val obj = JSONObject(raw)
            val modeName = obj.optString("mode", fallback.mode.name)
            val resolvedMode = TouchGuardMode.entries.firstOrNull { it.name == modeName } ?: fallback.mode
            PianoTouchGuardConfig(
                mode = resolvedMode,
                glissandoSamePlaneLock = obj.optBoolean("glissandoSamePlaneLock", fallback.glissandoSamePlaneLock),
                lockChromeWhilePlaying = obj.optBoolean("lockChromeWhilePlaying", fallback.lockChromeWhilePlaying)
            )
        }.getOrDefault(fallback)
    }

    fun saveTouchGuardConfig(context: Context, config: PianoTouchGuardConfig) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put("mode", config.mode.name)
                put("glissandoSamePlaneLock", config.glissandoSamePlaneLock)
                put("lockChromeWhilePlaying", config.lockChromeWhilePlaying)
            }
            prefs.edit().putString(KEY_TOUCH_GUARD_JSON, obj.toString()).apply()
        }
    }

    fun resolveDescriptor(
        smallestScreenWidthDp: Int,
        availWidthDp: Float,
        availHeightDp: Float,
        dualKeyboard: Boolean
    ): LayoutDescriptor {
        val isTablet = smallestScreenWidthDp >= 600
        val isFoldable = !isTablet && smallestScreenWidthDp >= 500
        val isLandscape = availWidthDp > availHeightDp

        val deviceTag = when {
            isTablet -> "TABLET"
            isFoldable -> "FOLD"
            else -> "PHONE"
        }
        val deviceCn = when {
            isTablet -> "平板"
            isFoldable -> "折叠屏"
            else -> "手机"
        }
        val orientTag = if (isLandscape) "LANDSCAPE" else "PORTRAIT"
        val orientCn = if (isLandscape) "横屏" else "竖屏"
        val modeTag = if (dualKeyboard) "DUAL" else "SINGLE"
        val modeCn = if (dualKeyboard) "双键盘" else "单键盘"

        return LayoutDescriptor(
            key = "${deviceTag}_${orientTag}_${modeTag}",
            displayLabel = "${deviceCn}${orientCn} · ${modeCn}",
            isTablet = isTablet,
            isFoldable = isFoldable,
            isLandscape = isLandscape,
            dualKeyboard = dualKeyboard
        )
    }

    fun defaultProfileFor(
        descriptor: LayoutDescriptor,
        availWidthDp: Float,
        availHeightDp: Float
    ): KeyboardLayoutProfile {
        val defaultKeyW = when {
            descriptor.isTablet && descriptor.isLandscape -> 43f
            descriptor.isTablet -> 41f
            descriptor.isFoldable -> 39f
            descriptor.isLandscape -> 39f
            else -> 38f
        }

        val singleHeight = when {
            descriptor.isTablet && descriptor.isLandscape -> 236f
            descriptor.isTablet -> 222f
            descriptor.isFoldable -> 208f
            descriptor.isLandscape && availHeightDp < 440f -> 144f
            descriptor.isLandscape -> 168f
            else -> 210f
        }

        val dualEachHeight = when {
            descriptor.isTablet && descriptor.isLandscape -> 186f
            descriptor.isTablet -> 178f
            descriptor.isFoldable -> 164f
            descriptor.isLandscape && availHeightDp < 440f -> 108f
            descriptor.isLandscape -> 124f
            else -> 156f
        }

        val targetHeight = if (descriptor.dualKeyboard) dualEachHeight else singleHeight

        return KeyboardLayoutProfile(
            layoutKey = descriptor.key,
            displayLabel = descriptor.displayLabel,
            primary = KeyboardDeckGeometry(
                deckHeightDp = targetHeight,
                widthFraction = 1.0f,
                keyWidthDp = defaultKeyW,
                scrollWhiteKey = 21f // C4
            ).coerced(),
            secondary = KeyboardDeckGeometry(
                deckHeightDp = targetHeight,
                widthFraction = 1.0f,
                keyWidthDp = defaultKeyW,
                scrollWhiteKey = 14f // C3
            ).coerced(),
            collapseScore = false
        )
    }

    fun loadProfile(
        context: Context,
        descriptor: LayoutDescriptor,
        availWidthDp: Float,
        availHeightDp: Float
    ): KeyboardLayoutProfile {
        val fallback = defaultProfileFor(descriptor, availWidthDp, availHeightDp)
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PROFILES_JSON, null) ?: return fallback
            val root = JSONObject(raw)
            val obj = root.optJSONObject(descriptor.key) ?: return fallback
            val pObj = obj.optJSONObject("primary")
            val sObj = obj.optJSONObject("secondary")

            val primary = if (pObj != null) {
                KeyboardDeckGeometry(
                    deckHeightDp = pObj.optDouble("deckHeightDp", fallback.primary.deckHeightDp.toDouble()).toFloat(),
                    widthFraction = pObj.optDouble("widthFraction", fallback.primary.widthFraction.toDouble()).toFloat(),
                    keyWidthDp = pObj.optDouble("keyWidthDp", fallback.primary.keyWidthDp.toDouble()).toFloat(),
                    scrollWhiteKey = pObj.optDouble("scrollWhiteKey", fallback.primary.scrollWhiteKey.toDouble()).toFloat()
                ).coerced()
            } else {
                fallback.primary
            }

            val secondary = if (sObj != null) {
                KeyboardDeckGeometry(
                    deckHeightDp = sObj.optDouble("deckHeightDp", fallback.secondary.deckHeightDp.toDouble()).toFloat(),
                    widthFraction = sObj.optDouble("widthFraction", fallback.secondary.widthFraction.toDouble()).toFloat(),
                    keyWidthDp = sObj.optDouble("keyWidthDp", fallback.secondary.keyWidthDp.toDouble()).toFloat(),
                    scrollWhiteKey = sObj.optDouble("scrollWhiteKey", fallback.secondary.scrollWhiteKey.toDouble()).toFloat()
                ).coerced()
            } else {
                fallback.secondary
            }

            KeyboardLayoutProfile(
                layoutKey = descriptor.key,
                displayLabel = descriptor.displayLabel,
                primary = primary,
                secondary = secondary,
                collapseScore = obj.optBoolean("collapseScore", false)
            )
        }.getOrDefault(fallback)
    }

    fun saveProfile(context: Context, profile: KeyboardLayoutProfile) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PROFILES_JSON, null)
            val root = if (!raw.isNullOrBlank()) JSONObject(raw) else JSONObject()

            val p = profile.primary.coerced()
            val s = profile.secondary.coerced()

            val entry = JSONObject().apply {
                put("collapseScore", profile.collapseScore)
                put(
                    "primary",
                    JSONObject().apply {
                        put("deckHeightDp", p.deckHeightDp.toDouble())
                        put("widthFraction", p.widthFraction.toDouble())
                        put("keyWidthDp", p.keyWidthDp.toDouble())
                        put("scrollWhiteKey", p.scrollWhiteKey.toDouble())
                    }
                )
                put(
                    "secondary",
                    JSONObject().apply {
                        put("deckHeightDp", s.deckHeightDp.toDouble())
                        put("widthFraction", s.widthFraction.toDouble())
                        put("keyWidthDp", s.keyWidthDp.toDouble())
                        put("scrollWhiteKey", s.scrollWhiteKey.toDouble())
                    }
                )
            }
            root.put(profile.layoutKey, entry)
            prefs.edit().putString(KEY_PROFILES_JSON, root.toString()).apply()
        }
    }

    fun resetProfile(
        context: Context,
        descriptor: LayoutDescriptor,
        availWidthDp: Float,
        availHeightDp: Float
    ): KeyboardLayoutProfile {
        val fresh = defaultProfileFor(descriptor, availWidthDp, availHeightDp)
        saveProfile(context, fresh)
        return fresh
    }
}
