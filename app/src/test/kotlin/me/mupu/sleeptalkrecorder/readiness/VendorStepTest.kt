package me.mupu.sleeptalkrecorder.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorStepTest {

    private fun stepsFor(manufacturer: String, vararg installed: String) =
        VendorStep.forPhone(manufacturer) { it in installed }

    @Test
    fun `a Samsung phone gets the never sleeping apps step, whatever the case of its maker`() {
        assertEquals(listOf(VendorStep.SAMSUNG_NEVER_SLEEPING), stepsFor("samsung"))
        assertEquals(listOf(VendorStep.SAMSUNG_NEVER_SLEEPING), stepsFor("Samsung"))
    }

    @Test
    fun `Xiaomi, Redmi and POCO phones get autostart and the battery saver`() {
        val xiaomi = listOf(VendorStep.XIAOMI_AUTOSTART, VendorStep.XIAOMI_BATTERY_SAVER)
        assertEquals(xiaomi, stepsFor("Xiaomi"))
        assertEquals(xiaomi, stepsFor("Redmi"))
        assertEquals(xiaomi, stepsFor("POCO"))
    }

    @Test
    fun `any phone with DuraSpeed installed gets the DuraSpeed step`() {
        assertEquals(listOf(VendorStep.DURASPEED), stepsFor("Ulefone", VendorStep.DURASPEED_PACKAGE))
        assertEquals(
            listOf(VendorStep.XIAOMI_AUTOSTART, VendorStep.XIAOMI_BATTERY_SAVER, VendorStep.DURASPEED),
            stepsFor("Xiaomi", VendorStep.DURASPEED_PACKAGE),
        )
    }

    @Test
    fun `a phone without an extra app killer gets no vendor step`() {
        assertEquals(emptyList<VendorStep>(), stepsFor("Google"))
        assertEquals(emptyList<VendorStep>(), stepsFor("Ulefone", "com.example.other"))
    }
}
