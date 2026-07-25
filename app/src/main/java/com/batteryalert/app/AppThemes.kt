package com.batteryalert.app

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.children

/**
 * The app's four selectable themes, applied live to the one layout.
 * Views opt in via android:tag ("card", "cardAlt", "btnAccent", "btnNeutral",
 * "divider", "textSecondary"); "textSemantic" marks state-coloured text the
 * themer must leave alone (green/red belong to updateUI). Untagged TextViews
 * get textPrimary; untagged Buttons (ENABLE, the theme button) keep their own
 * styling.
 */
object AppThemes {

    const val DEFAULT_INDEX = 2 // Deep Ocean

    data class Theme(
        val name: String,
        val lightSystemBars: Boolean,
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val onAccent: Int,
        val neutralBtn: Int,
        val onNeutral: Int,
        val divider: Int
    )

    val ALL = listOf(
        Theme("Mint Light", true,
            0xFFF4FBF7.toInt(), 0xFFE5F3EB.toInt(), 0xFFD5EADF.toInt(),
            0xFF15352A.toInt(), 0xFF5E7D70.toInt(),
            0xFF0F9D58.toInt(), 0xFFFFFFFF.toInt(),
            0xFFCBE2D6.toInt(), 0xFF15352A.toInt(), 0xFFD2E5DA.toInt()),
        Theme("Cream Comfort", true,
            0xFFFFF8F0.toInt(), 0xFFF7EFE3.toInt(), 0xFFEFE3D0.toInt(),
            0xFF3D3327.toInt(), 0xFF8A7B68.toInt(),
            0xFFC2410C.toInt(), 0xFFFFFFFF.toInt(),
            0xFFE4D8C4.toInt(), 0xFF3D3327.toInt(), 0xFFE0D4C0.toInt()),
        Theme("Deep Ocean", false,
            0xFF06222E.toInt(), 0xFF0E3442.toInt(), 0xFF174B5D.toInt(),
            0xFFE2F4F9.toInt(), 0xFF86AEBB.toInt(),
            0xFF4DD0E1.toInt(), 0xFF03242E.toInt(),
            0xFF20586B.toInt(), 0xFFE2F4F9.toInt(), 0xFF154555.toInt()),
        Theme("Lavender", true,
            0xFFFAF8FF.toInt(), 0xFFF0EBFA.toInt(), 0xFFE4DCF4.toInt(),
            0xFF2C2540.toInt(), 0xFF6F6689.toInt(),
            0xFF7C4DFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFFDCD2EF.toInt(), 0xFF2C2540.toInt(), 0xFFE0D8F0.toInt())
    )

    fun apply(root: View, theme: Theme) {
        root.setBackgroundColor(theme.background)
        walk(root, theme)
    }

    private fun walk(view: View, t: Theme) {
        when (view.tag as? String) {
            "card" -> view.backgroundTintList = ColorStateList.valueOf(t.surface)
            "cardAlt" -> view.backgroundTintList = ColorStateList.valueOf(t.surfaceAlt)
            "btnAccent" -> {
                view.backgroundTintList = ColorStateList.valueOf(t.accent)
                (view as? Button)?.setTextColor(t.onAccent)
            }
            "btnNeutral" -> {
                view.backgroundTintList = ColorStateList.valueOf(t.neutralBtn)
                (view as? Button)?.setTextColor(t.onNeutral)
            }
            "divider" -> view.setBackgroundColor(t.divider)
            "textSecondary" -> (view as? TextView)?.setTextColor(t.textSecondary)
            "textSemantic" -> {} // green/red state colours owned by updateUI
            else -> when {
                view is EditText -> {
                    view.setTextColor(t.textPrimary)
                    view.setHintTextColor(t.textSecondary)
                }
                view is TextView && view !is Button -> view.setTextColor(t.textPrimary)
            }
        }
        if (view is ViewGroup) view.children.forEach { walk(it, t) }
    }
}
