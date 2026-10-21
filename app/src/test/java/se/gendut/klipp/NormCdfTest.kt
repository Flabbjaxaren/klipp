package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.Spektra

class NormCdfTest {
    @Test fun matchesScipy() {
        // 0.5·erfc(−z/√2) from Python (math.erfc) – same as scipy.stats.norm.cdf
        val ref = listOf(
            -6.0 to 9.865876450377014e-10, -3.5 to 0.00023262907903552502, -2.0 to 0.022750131948179216, -1.0 to 0.15865525393145705,
            -0.4 to 0.3445782583896758, 0.0 to 0.5, 0.3 to 0.6179114221889526, 0.7 to 0.758036347776927, 1.5 to 0.9331927987311419,
            3.0 to 0.9986501019683699, 4.5 to 0.9999966023268753, 7.0 to 0.9999999999987201,
        )
        for ((z, v) in ref) assertEquals("z = $z", v, Spektra.normCdf(z), 1e-13 + 1e-12 * v)
    }
}
