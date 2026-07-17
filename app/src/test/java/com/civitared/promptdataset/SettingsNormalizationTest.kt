package com.civitared.promptdataset

import com.civitared.promptdataset.data.AppSettings
import com.civitared.promptdataset.data.SettingsRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNormalizationTest {
    @Test
    fun baseHostGetsApiPath() {
        assertEquals(
            "https://civita.red/api/v1/images",
            SettingsRules.normalizeEndpoint("civita.red/"),
        )
    }

    @Test
    fun existingEndpointAndQueryAreNormalized() {
        assertEquals(
            "https://example.org/api/v1/images",
            SettingsRules.normalizeEndpoint(
                "http://example.org/api/v1/images/?temporary=true#fragment",
            ),
        )
    }

    @Test
    fun nsfwRequiresAgeConfirmationAndApiKey() {
        val noAge = AppSettings(includeNsfw = true, apiKey = "secret")
        assertEquals(
            "Для NSFW-ленты требуется подтверждение возраста 18+",
            SettingsRules.validationError(noAge),
        )

        val noKey = AppSettings(includeNsfw = true, ageConfirmed = true)
        assertEquals(
            "Для NSFW-ленты укажите пользовательский API-ключ",
            SettingsRules.validationError(noKey),
        )

        assertNull(
            SettingsRules.validationError(
                AppSettings(includeNsfw = true, ageConfirmed = true, apiKey = "secret"),
            ),
        )
    }
}
