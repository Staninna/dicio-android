package org.stypox.dicio.skills.weather

import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.json.JSONArray
import org.json.JSONObject
import org.stypox.dicio.sentences.Sentences.Weather
import org.stypox.dicio.skills.weather.WeatherInfo.weatherDataStore
import org.stypox.dicio.util.ConnectionUtils
import org.stypox.dicio.util.StringUtils
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.math.roundToInt

class WeatherSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Weather>) :
    StandardRecognizerSkill<Weather>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Weather): SkillOutput {
        val prefs = ctx.android.weatherDataStore.data.first()
        val place = getPlace(prefs, inputData) ?: return WeatherOutput.Failed(city = "")
        val lang = ctx.locale.language.lowercase(Locale.getDefault())

        var cityName: String? = null
        val weatherData = try {
            val where = if (place.province.isEmpty() && place.country.isEmpty()) {
                "&q=" + ConnectionUtils.urlEncode(place.city)
            } else {
                // the weather API only understands US state codes, so find the exact place first
                val found = geocode(place, ctx.locale)
                    ?: return WeatherOutput.Failed(city = place.city)
                cityName = found.optJSONObject("local_names")?.optString(lang)
                    ?.takeIf { it.isNotEmpty() }
                    ?: found.getString("name")
                "&lat=" + found.getDouble("lat") + "&lon=" + found.getDouble("lon")
            }
            // always request in metric units (°C and m) and convert later
            ConnectionUtils.getPageJson(
                "$WEATHER_API_URL?APPID=$API_KEY&units=metric&lang=$lang$where"
            )
        } catch (_: FileNotFoundException) {
            return WeatherOutput.Failed(city = place.city)
        }

        val weatherObject = weatherData.getJSONArray("weather").getJSONObject(0)
        val mainObject = weatherData.getJSONObject("main")
        val windObject = weatherData.getJSONObject("wind")

        val tempUnit = ResolvedTemperatureUnit.from(prefs)
        val temp = mainObject.getDouble("temp")
        val tempConverted = tempUnit.convert(temp)
        return WeatherOutput.Success(
            city = cityName ?: weatherData.getString("name"),
            description = weatherObject.getString("description")
                .apply { this[0].uppercaseChar() + this.substring(1) },
            iconUrl = ICON_BASE_URL + weatherObject.getString("icon") + ICON_FORMAT,
            temp = temp,
            tempMin = mainObject.getDouble("temp_min"),
            tempMax = mainObject.getDouble("temp_max"),
            tempString = ctx.parserFormatter
                ?.niceNumber(tempConverted.roundToInt().toDouble())?.speech(true)?.get()
                ?: (tempConverted.roundToInt().toString()),
            windSpeed = windObject.getDouble("speed"),
            temperatureUnit = tempUnit,
            lengthUnit = ResolvedLengthUnit.from(prefs),
        )
    }

    private data class Place(val city: String, val province: String = "", val country: String = "")

    private fun getPlace(prefs: SkillSettingsWeather, inputData: Weather): Place? {
        val spokenCity = when (inputData) {
            is Weather.Current -> inputData.where
        }
        if (!spokenCity.isNullOrEmpty()) {
            // the default province and country belong to the default city, not to a spoken one
            return Place(spokenCity)
        }

        val defaultCity = StringUtils.removePunctuation(prefs.defaultCity.trim { ch -> ch <= ' ' })
        if (defaultCity.isNotEmpty()) {
            return Place(
                city = defaultCity,
                province = prefs.defaultProvince.trim(),
                country = prefs.defaultCountry.trim(),
            )
        }

        val ipCity = ConnectionUtils.getPageJson(IP_INFO_URL).getString("city")
        return if (ipCity.isNullOrEmpty()) null else Place(ipCity)
    }

    /**
     * Looks up [place] with OpenWeatherMap's geocoding API. Among the results, prefers the one
     * whose state matches the province, since the API ignores provinces outside the US when it
     * ranks results. Returns null if nothing was found.
     */
    private fun geocode(place: Place, locale: Locale): JSONObject? {
        val country = CountryCodes.resolve(place.country, listOf(locale, Locale.getDefault()))
            ?: place.country
        val query = listOf(place.city, place.province, country)
            .filter { it.isNotEmpty() }
            .joinToString(",")
        val results = JSONArray(
            ConnectionUtils.getPage(
                "$GEOCODING_API_URL?appid=$API_KEY&limit=5&q=" + ConnectionUtils.urlEncode(query)
            )
        )
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
        return candidates.firstOrNull {
            place.province.isNotEmpty() &&
                    it.optString("state").equals(place.province, ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    companion object {
        private const val IP_INFO_URL = "https://ipinfo.io/json"
        private const val GEOCODING_API_URL = "https://api.openweathermap.org/geo/1.0/direct"
        private const val WEATHER_API_URL = "https://api.openweathermap.org/data/2.5/weather"
        private const val API_KEY = "061f24cf3cde2f60644a8240302983f2"
        private const val ICON_BASE_URL = "https://openweathermap.org/img/wn/"
        private const val ICON_FORMAT = "@2x.png"
    }
}
