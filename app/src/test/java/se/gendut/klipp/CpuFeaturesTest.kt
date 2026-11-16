package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.CpuFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The speech library only on CPUs with the instructions it is built for (dotprod, fp16, v8.1 atomics). */
class CpuFeaturesTest {
    private fun auxv(vararg pairs: Long): ByteArray {
        val b = ByteBuffer.allocate(pairs.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        pairs.forEach { b.putLong(it) }
        return b.array()
    }

    // a Snapdragon 8 Gen 3's AT_HWCAP (fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm
    // jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg)
    private val gen3 = 0xEFFFFFFFL
    // a Cortex-A53 (Snapdragon 625): fp asimd evtstrm aes pmull sha1 sha2 crc32
    private val a53 = 0xFFL

    @Test fun readsHwcapFromTheAuxiliaryVector() {
        assertEquals(gen3, CpuFeatures.hwcapOf(auxv(33, 0x7000, 16, gen3, 6, 4096, 0, 0)))
        assertNull("ends at AT_NULL", CpuFeatures.hwcapOf(auxv(6, 4096, 0, 0, 16, gen3)))
        assertNull(CpuFeatures.hwcapOf(ByteArray(0)))
    }

    @Test fun anArmv8_0CoreIsNotOk() {
        assertTrue(CpuFeatures.speechOk(gen3))
        assertFalse(CpuFeatures.speechOk(a53))
        // dotprod without the atomics (or the other way) is not enough
        assertFalse(CpuFeatures.speechOk((1L shl 20) or (1L shl 9) or (1L shl 10)))
        assertFalse(CpuFeatures.speechOk((1L shl 8) or (1L shl 9) or (1L shl 10)))
    }

    @Test fun cpuinfoFallback() {
        val big = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
        val old = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"
        assertEquals(true, CpuFeatures.speechOkFromCpuinfo(big + big))
        assertEquals(false, CpuFeatures.speechOkFromCpuinfo(old))
        assertEquals("one core without them", false, CpuFeatures.speechOkFromCpuinfo(big + old))
        assertNull(CpuFeatures.speechOkFromCpuinfo("Hardware\t: Qualcomm\n"))
    }
}
