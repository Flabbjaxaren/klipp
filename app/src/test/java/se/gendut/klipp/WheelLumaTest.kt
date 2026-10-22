package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.GradeNode

/**
 * The wheels with their luminance side against reference renders: per-channel lift/gain/gamma/offset (the colors) and
 * the master values on the luma (or none – then the color changes keep the luminance), read back from 16-bit files.
 */
class WheelLumaTest {
    private class Case(val name: String, val lift: FloatArray, val gain: FloatArray, val gamma: Float, val offset: FloatArray, val master: Boolean, val px: List<FloatArray>)

    private val cases = listOf(
        Case("lmo_combo_y", floatArrayOf(0.05f, 0.05f, 0.05f), floatArrayOf(1.2f, 1.2f, 1.2f), 0.2f, floatArrayOf(0.05f, 0.05f, 0.05f), true, listOf(
            floatArrayOf(0.35402f, 0.40000f, 0.08000f, 0.57127f, 0.61927f, 0.26284f),
            floatArrayOf(0.40000f, 0.36340f, 0.08000f, 0.61912f, 0.58097f, 0.26268f),
            floatArrayOf(0.08000f, 0.10815f, 0.40000f, 0.26316f, 0.29737f, 0.61959f),
            floatArrayOf(0.24141f, 0.08000f, 0.40000f, 0.45171f, 0.26456f, 0.62100f),
            floatArrayOf(0.40000f, 0.08000f, 0.08563f, 0.62313f, 0.26670f, 0.27361f),
            floatArrayOf(0.17693f, 0.17693f, 0.17693f, 0.37586f, 0.37586f, 0.37586f),
            floatArrayOf(0.12606f, 0.30596f, 0.12239f, 0.31862f, 0.52044f, 0.31427f),
            floatArrayOf(0.22352f, 0.54252f, 0.21701f, 0.43212f, 0.76570f, 0.42487f),
            floatArrayOf(0.50244f, 0.05024f, 0.18590f, 0.73240f, 0.23438f, 0.39649f),
            floatArrayOf(0.33431f, 0.03343f, 0.12370f, 0.55489f, 0.20858f, 0.32003f),
            floatArrayOf(0.03715f, 0.00371f, 0.01374f, 0.20742f, 0.16333f, 0.17679f),
            floatArrayOf(0.00098f, 0.00010f, 0.00036f, 0.15946f, 0.15825f, 0.15863f),
            floatArrayOf(0.43695f, 0.04370f, 0.16167f, 0.66435f, 0.22437f, 0.36704f)
        )),
        Case("lmo_puck", floatArrayOf(0.08f, 0f, -0.04f), floatArrayOf(1.0f, 1.1f, 1.1f), 0.0f, floatArrayOf(0f, 0f, 0f), true, listOf(
            floatArrayOf(0.71437f, 0.40000f, 0.80000f, 0.74794f, 0.45071f, 0.88540f),
            floatArrayOf(0.71906f, 0.40000f, 0.80000f, 0.75244f, 0.45089f, 0.88559f),
            floatArrayOf(0.63460f, 0.80000f, 0.40000f, 0.67291f, 0.88907f, 0.42432f),
            floatArrayOf(0.79413f, 0.80000f, 0.40000f, 0.82577f, 0.89517f, 0.43043f),
            floatArrayOf(0.40000f, 0.80000f, 0.67214f, 0.44714f, 0.87913f, 0.72697f),
            floatArrayOf(0.40000f, 0.08000f, 0.14194f, 0.44900f, 0.08899f, 0.11984f),
            floatArrayOf(0.16446f, 0.40000f, 0.08000f, 0.22350f, 0.43220f, 0.03990f),
            floatArrayOf(0.08000f, 0.36716f, 0.40000f, 0.14144f, 0.39171f, 0.40310f),
            floatArrayOf(0.08000f, 0.38780f, 0.40000f, 0.14144f, 0.41444f, 0.40310f),
            floatArrayOf(0.08000f, 0.38968f, 0.40000f, 0.14144f, 0.41650f, 0.40310f),
            floatArrayOf(0.84262f, 0.84262f, 0.84262f, 0.87068f, 0.94235f, 0.93912f),
            floatArrayOf(0.84555f, 0.84555f, 0.84555f, 0.87347f, 0.94568f, 0.94258f),
            floatArrayOf(0.86901f, 0.86901f, 0.86901f, 0.89589f, 0.97230f, 0.97035f),
            floatArrayOf(0.08504f, 0.08504f, 0.08504f, 0.14737f, 0.08267f, 0.04263f),
            floatArrayOf(0.48172f, 0.60903f, 0.80000f, 0.52500f, 0.67175f, 0.87651f),
            floatArrayOf(0.05161f, 0.35097f, 0.80000f, 0.11283f, 0.37142f, 0.86004f),
            floatArrayOf(0.26041f, 0.47625f, 0.80000f, 0.31292f, 0.51722f, 0.86804f),
            floatArrayOf(0.58495f, 0.67097f, 0.80000f, 0.62391f, 0.74383f, 0.88046f),
            floatArrayOf(0.63265f, 0.69959f, 0.80000f, 0.66963f, 0.77714f, 0.88228f),
            floatArrayOf(0.90000f, 0.57977f, 0.28416f, 0.92763f, 0.65737f, 0.30184f),
            floatArrayOf(0.90000f, 0.55415f, 0.23490f, 0.92781f, 0.62936f, 0.24543f),
            floatArrayOf(0.90000f, 0.58480f, 0.29384f, 0.92760f, 0.66287f, 0.31292f),
            floatArrayOf(0.90000f, 0.80805f, 0.72317f, 0.92609f, 0.90694f, 0.80453f),
            floatArrayOf(0.90000f, 0.45808f, 0.05015f, 0.92845f, 0.52433f, 0.03386f),
            floatArrayOf(0.14579f, 0.35386f, 0.14154f, 0.20539f, 0.38050f, 0.10967f),
            floatArrayOf(0.26943f, 0.65396f, 0.26158f, 0.32345f, 0.71493f, 0.25185f),
            floatArrayOf(0.26339f, 0.63930f, 0.25572f, 0.31768f, 0.69859f, 0.24491f),
            floatArrayOf(0.06685f, 0.16227f, 0.06491f, 0.13001f, 0.16701f, 0.01889f),
            floatArrayOf(0.79765f, 0.07977f, 0.29513f, 0.82951f, 0.10341f, 0.31048f),
            floatArrayOf(0.19355f, 0.01935f, 0.07161f, 0.25139f, 0.01462f, 0.03142f),
            floatArrayOf(0.32845f, 0.03284f, 0.12152f, 0.38050f, 0.03444f, 0.09372f),
            floatArrayOf(0.37537f, 0.03754f, 0.13889f, 0.42541f, 0.04135f, 0.11540f)
        )),
        Case("lmo_off_red", floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), 0f, floatArrayOf(0.1f, 0f, 0f), false, listOf(
            floatArrayOf(0.40000f, 0.80000f, 0.50088f, 0.49989f, 0.80000f, 0.50088f),
            floatArrayOf(0.53372f, 0.40000f, 0.80000f, 0.63362f, 0.40000f, 0.80000f),
            floatArrayOf(0.40000f, 0.52434f, 0.80000f, 0.49989f, 0.52435f, 0.80000f),
            floatArrayOf(0.74252f, 0.40000f, 0.80000f, 0.84240f, 0.40000f, 0.80000f),
            floatArrayOf(0.80000f, 0.40000f, 0.42815f, 0.89989f, 0.40000f, 0.42815f),
            floatArrayOf(0.08000f, 0.40000f, 0.36903f, 0.17989f, 0.40000f, 0.36904f),
            floatArrayOf(0.08000f, 0.40000f, 0.20950f, 0.17989f, 0.40000f, 0.20951f),
            floatArrayOf(0.40000f, 0.08000f, 0.37654f, 0.49989f, 0.08000f, 0.37655f),
            floatArrayOf(0.08000f, 0.40000f, 0.25455f, 0.17989f, 0.40000f, 0.25455f),
            floatArrayOf(0.40000f, 0.08000f, 0.19449f, 0.49989f, 0.08000f, 0.19449f),
            floatArrayOf(0.73216f, 0.73216f, 0.73216f, 0.83204f, 0.73216f, 0.73216f),
            floatArrayOf(0.42522f, 0.42522f, 0.42522f, 0.52511f, 0.42522f, 0.42522f),
            floatArrayOf(0.31672f, 0.31672f, 0.31672f, 0.41660f, 0.31672f, 0.31672f),
            floatArrayOf(0.40176f, 0.40176f, 0.40176f, 0.50164f, 0.40175f, 0.40175f),
            floatArrayOf(0.29638f, 0.49783f, 0.80000f, 0.39626f, 0.49783f, 0.80000f),
            floatArrayOf(0.05239f, 0.35144f, 0.80000f, 0.15229f, 0.35143f, 0.80000f),
            floatArrayOf(0.52004f, 0.63202f, 0.80000f, 0.61993f, 0.63203f, 0.80000f),
            floatArrayOf(0.34956f, 0.52974f, 0.80000f, 0.44944f, 0.52973f, 0.80000f),
            floatArrayOf(0.30733f, 0.50440f, 0.80000f, 0.40722f, 0.50440f, 0.80000f),
            floatArrayOf(0.03021f, 0.07331f, 0.02933f, 0.13010f, 0.07332f, 0.02933f),
            floatArrayOf(0.35320f, 0.85728f, 0.34291f, 0.45309f, 0.85728f, 0.34292f),
            floatArrayOf(0.13975f, 0.33920f, 0.13568f, 0.23964f, 0.33919f, 0.13568f),
            floatArrayOf(0.07813f, 0.18964f, 0.07586f, 0.17801f, 0.18964f, 0.07585f),
            floatArrayOf(0.34313f, 0.83284f, 0.33314f, 0.44302f, 0.83284f, 0.33313f),
            floatArrayOf(0.52493f, 0.05249f, 0.19422f, 0.62481f, 0.05249f, 0.19422f),
            floatArrayOf(0.54839f, 0.05484f, 0.20290f, 0.64828f, 0.05484f, 0.20290f),
            floatArrayOf(0.49169f, 0.04917f, 0.18193f, 0.59158f, 0.04916f, 0.18193f),
            floatArrayOf(0.25709f, 0.02571f, 0.09512f, 0.35697f, 0.02571f, 0.09512f)
        )),
    )

    @Test fun matchesReferenceRenders() {
        for (c in cases) {
            val prim = (0..2).map { GradeNode.primaries(c.lift[it], c.gain[it], c.gamma) }
            // the master values: the cases set all channels alike for the master part (red's values carry it)
            val y = if (c.master) GradeNode.primaries(c.lift[1], c.gain[1], c.gamma) else Triple(0f, 1f, 1f)
            val p = GradeNode.Params(
                FloatArray(3) { prim[it].first }, FloatArray(3) { prim[it].third }, FloatArray(3) { prim[it].second }, c.offset,
                1f, 0.435f, floatArrayOf(1f, 1f, 1f), 1f, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 1f, null,
                ySide = floatArrayOf(y.first, y.second, y.third),
            )
            for (px in c.px) {
                val rgb = floatArrayOf(px[0], px[1], px[2])
                GradeNode.apply(p, rgb)
                for (i in 0..2) assertEquals("${c.name} (${px[0]}, ${px[1]}, ${px[2]}) channel $i", px[3 + i], rgb[i], 0.002f)
            }
        }
    }
}
