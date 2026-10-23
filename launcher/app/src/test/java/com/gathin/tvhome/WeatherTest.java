package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The weather beside the date: when it asks Open-Meteo, and the glyph and words for each WMO code. */
public class WeatherTest {
    private static final long MIN = 60 * 1000L;
    private static final long NOW = 1_000_000 * MIN;

    @Test
    public void asksWithNoAnswerAndNoRequestYet() {
        assertTrue(Weather.due(0, 0, NOW));
    }

    @Test
    public void anAnswerHoldsForHalfAnHour() {
        assertFalse(Weather.due(NOW - 29 * MIN, NOW - 29 * MIN, NOW));
        assertTrue(Weather.due(NOW - 30 * MIN, NOW - 30 * MIN, NOW));
    }

    @Test
    public void aFailedRequestWaitsFiveMinutes() {
        assertFalse(Weather.due(0, NOW - 4 * MIN, NOW));
        assertTrue(Weather.due(0, NOW - 5 * MIN, NOW));
        // What networkUp does after a failure: the next refresh asks at once.
        assertTrue(Weather.due(0, 0, NOW));
    }

    @Test
    public void clearSkyIsTheSunByDayAndTheMoonAtNight() {
        assertEquals(WeatherIcon.SUN, Weather.kind(0, true));
        assertEquals(WeatherIcon.MOON, Weather.kind(0, false));
        assertEquals(WeatherIcon.SUN_CLOUD, Weather.kind(2, true));
        assertEquals(WeatherIcon.MOON_CLOUD, Weather.kind(1, false));
    }

    @Test
    public void everyWmoGroupHasItsGlyph() {
        assertEquals(WeatherIcon.CLOUD, Weather.kind(3, true));
        assertEquals(WeatherIcon.FOG, Weather.kind(45, true));
        assertEquals(WeatherIcon.FOG, Weather.kind(48, false));
        for (int code : new int[]{51, 55, 57, 61, 63, 65, 66, 67, 80, 81, 82}) {
            assertEquals("code " + code, WeatherIcon.RAIN, Weather.kind(code, true));
        }
        for (int code : new int[]{95, 96, 99}) assertEquals(WeatherIcon.STORM, Weather.kind(code, false));
        // Snow has no glyph of its own; a cloud is the closest.
        assertEquals(WeatherIcon.CLOUD, Weather.kind(71, true));
    }

    @Test
    public void rainSaysHowHeavy() {
        assertEquals(R.string.weather_drizzle, Weather.label(53));
        assertEquals(R.string.weather_light_rain, Weather.label(61));
        assertEquals(R.string.weather_light_rain, Weather.label(80));
        assertEquals(R.string.weather_rain, Weather.label(63));
        assertEquals(R.string.weather_rain, Weather.label(81));
        assertEquals(R.string.weather_heavy_rain, Weather.label(65));
        assertEquals(R.string.weather_heavy_rain, Weather.label(82));
        assertEquals(R.string.weather_storm, Weather.label(95));
        assertEquals(R.string.weather_clear, Weather.label(0));
        assertEquals(R.string.weather_cloudy, Weather.label(3));
    }

    @Test
    public void aLocationIsRoundedToOneDecimalBeforeItGoesOut() {
        assertEquals(48.9, Weather.rounded(48.8566), 0);
        assertEquals(2.4, Weather.rounded(2.3522), 0);
        assertEquals(-33.9, Weather.rounded(-33.8688), 0);
    }

    @Test
    public void anEmptyOrMalformedLocationIsNoLocation() {
        assertEquals(null, Weather.parse(""));
        assertEquals(null, Weather.parse("  "));
        assertEquals(null, Weather.parse("48.85"));
        assertEquals(null, Weather.parse("91,0"));
        assertEquals(48.8566, Weather.parse("48.8566, 2.3522")[0], 0);
    }
}
