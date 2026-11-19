package se.gendut.klipp.engine

import android.graphics.RectF

/**
 * Where the platforms' own buttons and text cover a vertical (9:16) video – a guide over the preview only, never in the
 * export. Klipp's own plain shapes after each platform's published safe-zone guidance (1080 × 1920; fractions here):
 *
 *  * TikTok: TikTok's In-Feed safe-zone file (ads.tiktok.com, In-Feed ads): top 240, bottom 660, left 120, right 120 px,
 *    and the button column 780–960 × 840–1260 (its width read from the image).
 *  * Instagram Reels: Meta's Reels guide – top 14 %, bottom 35 %, sides 6 %; the button column (no official figure)
 *    after secondary sources, about 120 px wide above the bottom zone.
 *  * Instagram Stories: Meta's Stories guide – top 14 %, sides 6 %; the bottom 35 % there covers an ad's button, so
 *    the bottom here is the widely cited 20 % for ordinary stories (the reply bar).
 *  * YouTube Shorts: Google's vertical video safe-zone (support.google.com/google-ads/answer/9128498) – top 288,
 *    bottom 672, left 48, right 192 px (the buttons inside the right strip).
 *
 * The platforms change their apps; the zones are a guide, not a guarantee.
 */
object SafeZones {
    enum class Platform(val key: String, @androidx.annotation.StringRes val label: Int) {
        TIKTOK("tiktok", se.gendut.klipp.R.string.safezone_tiktok), REELS("reels", se.gendut.klipp.R.string.safezone_reels),
        STORIES("stories", se.gendut.klipp.R.string.safezone_stories), SHORTS("shorts", se.gendut.klipp.R.string.safezone_shorts),
    }

    /** A platform's zones: the area that is free of its UI ([clear]) and what covers it ([covered]), fractions of the frame. */
    class Zones(val clear: RectF, val covered: List<RectF>)

    private fun px(l: Float, t: Float, r: Float, b: Float) = RectF(l / 1080f, t / 1920f, r / 1080f, b / 1920f)

    fun of(p: Platform): Zones = when (p) {
        Platform.TIKTOK -> zones(px(120f, 240f, 960f, 1260f), px(780f, 840f, 960f, 1260f))
        Platform.REELS -> zones(px(65f, 269f, 1015f, 1248f), px(895f, 900f, 1015f, 1248f))
        Platform.STORIES -> zones(px(65f, 269f, 1015f, 1536f), null)
        Platform.SHORTS -> zones(px(48f, 288f, 888f, 1248f), null)
    }

    /** The covered bands around [clear] (top, bottom, left, right) and the button column inside it, if any. */
    private fun zones(clear: RectF, column: RectF?): Zones {
        val covered = listOf(
            RectF(0f, 0f, 1f, clear.top), RectF(0f, clear.bottom, 1f, 1f),
            RectF(0f, clear.top, clear.left, clear.bottom), RectF(clear.right, clear.top, 1f, clear.bottom),
        ) + listOfNotNull(column)
        return Zones(clear, covered)
    }

    fun byKey(k: String?): Platform? = Platform.entries.firstOrNull { it.key == k }

    /** What a caption block ([r], fractions of the frame) runs into: null when it is wholly in the clear area. */
    fun hit(z: Zones, r: RectF): RectF? = z.covered.firstOrNull { c -> RectF.intersects(c, r) && overlap(c, r) > 1e-4f }

    private fun overlap(a: RectF, b: RectF) =
        maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) * maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))

    /**
     * How far (fraction of the frame's height) the caption blocks [blocks] must move up (negative) or down so that all
     * lie between the clear area's top and bottom – and the button column, when it is beside them, above its top.
     * 0 when they fit; null when they don't fit at any height (taller than the free space).
     */
    fun verticalFit(z: Zones, blocks: List<RectF>): Float? {
        if (blocks.isEmpty()) return 0f
        val top = blocks.minOf { it.top }
        var bottom = z.clear.bottom
        // a column inside the clear area that the blocks reach sideways: they go above it
        z.covered.drop(4).forEach { col -> if (blocks.any { it.right > col.left && it.left < col.right }) bottom = minOf(bottom, col.top) }
        val low = blocks.maxOf { it.bottom }
        if (low - top > bottom - z.clear.top + 1e-4f) return null
        return when {
            low > bottom -> bottom - low
            top < z.clear.top -> z.clear.top - top
            else -> 0f
        }
    }

    /** Whether the blocks are wider than the clear area (they then run into a side whatever the height). */
    fun tooWide(z: Zones, blocks: List<RectF>): Boolean = blocks.any { it.left < z.clear.left - 1e-4f || it.right > z.clear.right + 1e-4f }
}
