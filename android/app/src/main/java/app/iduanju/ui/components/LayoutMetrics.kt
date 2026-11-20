package app.iduanju.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SizeTier { Compact, Medium, Expanded }

/**
 * Unified responsive layout metrics shared across phone, foldable, split-screen, and Android Pad.
 * Breakpoints match iOS `LayoutMetrics` (Compact < 600, Medium 600..<840, Expanded >= 840).
 */
@Immutable
data class LayoutMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
) {
    val tier: SizeTier
        get() = when {
            widthDp >= 840 -> SizeTier.Expanded
            widthDp >= 600 -> SizeTier.Medium
            else -> SizeTier.Compact
        }

    val isTablet: Boolean get() = smallestWidthDp >= 600
    val isLandscape: Boolean get() = widthDp > heightDp
    val useRail: Boolean get() = widthDp >= 840

    /** Portrait poster grid columns (home, category, manga, TVBox, library, search results). */
    val posterCols: Int
        get() = when {
            widthDp >= 1100 -> 6
            widthDp >= 840 -> 5
            widthDp >= 600 -> 4
            else -> 3
        }

    /** Landscape / wide card grid columns (search hot list, landscape feeds). */
    val wideCardCols: Int
        get() = when {
            widthDp >= 840 -> 4
            widthDp >= 600 -> 3
            else -> 2
        }

    /** Episode selector grid columns inside detail and player sheets. */
    val episodeCols: Int
        get() = when {
            widthDp >= 840 -> 10
            widthDp >= 600 -> 8
            else -> 6
        }

    val detailMaxWidth: Dp get() = if (tier == SizeTier.Compact) Dp.Unspecified else 900.dp
    val readerMaxWidth: Dp get() = if (tier == SizeTier.Compact) Dp.Unspecified else 720.dp
}

val LocalLayoutMetrics = staticCompositionLocalOf {
    LayoutMetrics(widthDp = 390, heightDp = 844, smallestWidthDp = 390)
}
