package com.glasslauncher.tv.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.glasslauncher.tv.R

/**
 * Bundled variable fonts (SIL OFL, see /licenses):
 *  - Inter: Latin (app names, song titles) — the closest open match to SF Pro.
 *  - Noto Sans Hebrew: all Hebrew UI text.
 * One file each; every weight we use is a real instance on the `wght` axis,
 * not a synthesized fake bold.
 */
object GlassFonts {

    private val Weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    @OptIn(ExperimentalTextApi::class)
    private fun variableFamily(resId: Int): FontFamily = FontFamily(
        Weights.map { weight ->
            Font(
                resId = resId,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        }
    )

    val Inter: FontFamily = variableFamily(R.font.inter_variable)
    val Hebrew: FontFamily = variableFamily(R.font.noto_sans_hebrew_variable)

    /**
     * Compose picks fonts by weight/style only, not by script, so we choose per string:
     * anything containing Hebrew letters uses Noto Sans Hebrew (its Latin is fine for
     * mixed strings like "פתח את Spotify"); pure Latin / numbers / emoji use Inter.
     */
    fun forText(text: String): FontFamily =
        if (text.any { it in '֐'..'׿' || it in 'יִ'..'ﭏ' }) Hebrew else Inter
}
