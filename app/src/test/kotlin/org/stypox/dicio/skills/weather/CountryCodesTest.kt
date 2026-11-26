package org.stypox.dicio.skills.weather

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class CountryCodesTest : StringSpec({
    "codes in any case" {
        CountryCodes.resolve("NL", listOf()) shouldBe "NL"
        CountryCodes.resolve(" us ", listOf()) shouldBe "US"
    }
    "English names" {
        CountryCodes.resolve("Netherlands", listOf()) shouldBe "NL"
        CountryCodes.resolve("germany", listOf()) shouldBe "DE"
    }
    "names in the given locale" {
        CountryCodes.resolve("Allemagne", listOf(Locale.FRENCH)) shouldBe "DE"
    }
    "names in the country's own language" {
        CountryCodes.resolve("Nederland", listOf()) shouldBe "NL"
        CountryCodes.resolve("Deutschland", listOf()) shouldBe "DE"
    }
    "unknown or empty" {
        CountryCodes.resolve("Atlantis", listOf()) shouldBe null
        CountryCodes.resolve("  ", listOf()) shouldBe null
    }
})
