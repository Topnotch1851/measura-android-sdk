package dev.measura.sdk

import dev.measura.sdk.utils.InstallReferrerReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallReferrerClickIdTest {

    private val id = "3f1c8a2e-9b47-4d1e-8a6f-2c5b9e7d1a03"

    @Test
    fun `a decoded referrer yields the click id`() {
        assertEquals(id, InstallReferrerReader.extractClickId("measura_click_id=$id"))
    }

    @Test
    fun `a still-encoded referrer yields the same click id`() {
        assertEquals(id, InstallReferrerReader.extractClickId("measura_click_id%3D$id"))
    }

    @Test
    fun `the click id is found among other referrer parameters`() {
        val referrer = "utm_source=whatsapp&measura_click_id=$id&measura_deferred=1"
        assertEquals(id, InstallReferrerReader.extractClickId(referrer))
    }

    @Test
    fun `uppercase hex is accepted, because Play does not normalise case`() {
        val upper = id.uppercase()
        assertEquals(upper, InstallReferrerReader.extractClickId("measura_click_id=$upper"))
    }

    @Test
    fun `an organic install has no click id`() {
        assertNull(InstallReferrerReader.extractClickId("utm_source=google-play&utm_medium=organic"))
    }

    @Test
    fun `no referrer at all is not an error`() {
        assertNull(InstallReferrerReader.extractClickId(null))
        assertNull(InstallReferrerReader.extractClickId(""))
    }

    @Test
    fun `a malformed uuid is refused rather than passed on`() {
        assertNull(InstallReferrerReader.extractClickId("measura_click_id=3f1c8a2e-9b47"))
        assertNull(InstallReferrerReader.extractClickId("measura_click_id=not-a-uuid-at-all"))
    }

    @Test
    fun `a similarly named parameter does not match`() {
        assertNull(InstallReferrerReader.extractClickId("other_click_id=$id"))
    }
}
