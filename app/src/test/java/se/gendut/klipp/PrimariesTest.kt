package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.GradeNode

/**
 * The wheels' primaries against reference renders: a 16-bit gray ramp through known Lift/Gain/Gamma/Offset values
 * (the reference's offset unit adds 0.1835), read back from the file.
 */
class PrimariesTest {
    private class Case(val lift: Float, val gain: Float, val gamma: Float, val offset: Float, val x: FloatArray, val y: FloatArray)

    private val cases = listOf(
        Case(0.1f, 1.3f, 0.25f, 0f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.19094f, 0.27294f, 0.34911f, 0.49049f, 0.6222f, 0.74723f, 0.8672f, 0.98315f, 1.0f, 1.0f)),
        Case(0.1f, 1.3f, 0.25f, 0.05f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.20195f, 0.28304f, 0.3586f, 0.49923f, 0.63044f, 0.75511f, 0.87478f, 0.99051f, 1.0f, 1.0f)),
        Case(-0.15f, 1.2f, -0.3f, 0f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.0f, 0.0f, 0.0f, 0.10025f, 0.25399f, 0.4343f, 0.6342f, 0.84999f, 1.0f, 1.0f)),
        Case(0.2f, 0.8f, 0.5f, -0.05f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.29165f, 0.3411f, 0.38718f, 0.47205f, 0.54987f, 0.62252f, 0.69114f, 0.75651f, 0.82107f, 0.87904f)),
        Case(0.05f, 1.0f, -0.6f, 0.1f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.01366f, 0.03717f, 0.0685f, 0.15042f, 0.25428f, 0.37731f, 0.51768f, 0.67407f, 0.85103f, 1.0f)),
        Case(-0.05f, 1.6f, 0.1f, 0.02f, floatArrayOf(0.0f, 0.06256f, 0.12512f, 0.25024f, 0.37537f, 0.50049f, 0.62561f, 0.75073f, 0.87977f, 1.0f), floatArrayOf(0.0f, 0.0407f, 0.16512f, 0.38965f, 0.60114f, 0.80528f, 1.0f, 1.0f, 1.0f, 1.0f)),
    )

    @Test fun matchesReferenceRenders() {
        for (c in cases) {
            val (a, b, e) = GradeNode.primaries(c.lift, c.gain, c.gamma)
            val p = GradeNode.Params(
                floatArrayOf(a, a, a), floatArrayOf(e, e, e), floatArrayOf(b, b, b), FloatArray(3) { c.offset * 0.1835f },
                1f, 0.435f, floatArrayOf(1f, 1f, 1f), 1f, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 1f, null,
            )
            for (i in c.x.indices) {
                val rgb = floatArrayOf(c.x[i], c.x[i], c.x[i])
                GradeNode.apply(p, rgb)
                assertEquals("lift ${c.lift} gain ${c.gain} gamma ${c.gamma} offset ${c.offset} at ${c.x[i]}", c.y[i], rgb[0], 0.002f)
            }
        }
    }
}
