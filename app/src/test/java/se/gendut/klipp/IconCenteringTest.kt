package se.gendut.klipp

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The icons' drawn shapes sit in the middle of their 24×24 box (buttons center the box, not the shape). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconCenteringTest {
    private fun bounds(v: ImageVector): android.graphics.RectF {
        val out = android.graphics.RectF(); var first = true
        fun walk(n: VectorNode, tx: Float, ty: Float) {
            when (n) {
                is VectorGroup -> n.forEach { walk(it, tx + n.translationX, ty + n.translationY) }
                is VectorPath -> {
                    val p = PathParser().addPathNodes(n.pathData).toPath()
                    val r = android.graphics.RectF()
                    (p as androidx.compose.ui.graphics.AndroidPath).internalPath.computeBounds(r, true)
                    r.offset(tx, ty)
                    // (RectF.union skips zero-width rects – straight lines)
                    if (first) { out.set(r); first = false }
                    else out.set(minOf(out.left, r.left), minOf(out.top, r.top), maxOf(out.right, r.right), maxOf(out.bottom, r.bottom))
                }
            }
        }
        walk(v.root, 0f, 0f)
        return out
    }

    @Test fun everyIconIsCentered() {
        val off = ArrayList<String>()
        for (f in Ic::class.java.declaredFields) {
            if (f.type != ImageVector::class.java) continue
            // a triangle looks centered a little right of its box's middle (optical center)
            if (f.name == "Play") continue
            f.isAccessible = true
            val b = bounds(f.get(Ic) as ImageVector)
            val dx = b.centerX() - 12; val dy = b.centerY() - 12
            if (kotlin.math.abs(dx) >= 0.5f || kotlin.math.abs(dy) >= 0.5f) off += "%s dx %+.2f dy %+.2f".format(java.util.Locale.ROOT, f.name, dx, dy)
        }
        org.junit.Assert.assertTrue("off-center icons (shift them in Icons.kt): $off", off.isEmpty())
    }
}
