package org.stypox.dicio.skills.weather

import java.util.Locale

object CountryCodes {
    /**
     * Turns what the user typed as a country ("NL", "nl", "Netherlands", "Nederland") into the
     * ISO 3166 alpha-2 code that OpenWeatherMap expects. Names are matched in English, in the
     * given [locales] and in the country's own languages. Returns null if nothing matches.
     */
    fun resolve(input: String, locales: Collection<Locale>): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val codes = Locale.getISOCountries()
        val upper = trimmed.uppercase(Locale.ROOT)
        if (upper in codes) {
            return upper
        }

        fun matches(code: String, locale: Locale) =
            Locale("", code).getDisplayCountry(locale).equals(trimmed, ignoreCase = true)

        val nameLocales = listOf(Locale.ENGLISH) + locales
        return codes.firstOrNull { code -> nameLocales.any { matches(code, it) } }
            ?: Locale.getAvailableLocales()
                .firstOrNull { it.country in codes && matches(it.country, it) }
                ?.country
    }
}
