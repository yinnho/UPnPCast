package com.yinnho.upnpcast.internal.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class UpnpTimeTest {

    @ParameterizedTest(name = "parseToMs(\"{0}\") == {1}")
    @CsvSource(
        "00:00:00, 0",
        "00:00:01, 1000",
        "00:01:30, 90000",
        "01:00:00, 3600000",
        "01:02:03, 3723000",
        "10:00:00, 36000000",
        "100:00:00, 360000000"
    )
    fun parsesStandardTimeStrings(input: String, expectedMs: Long) {
        assertEquals(expectedMs, UpnpTime.parseToMs(input))
    }

    @ParameterizedTest(name = "parseToMs(\"{0}\") == 0")
    @ValueSource(strings = ["NOT_IMPLEMENTED", "", "12:34", "abc", "aa:bb:cc"])
    fun returnsZeroForNonParseableInput(input: String) {
        assertEquals(0L, UpnpTime.parseToMs(input))
    }

    @org.junit.jupiter.api.Test
    fun truncatesFractionalSeconds() {
        assertEquals(90000L, UpnpTime.parseToMs("00:01:30.500"))
    }

    @ParameterizedTest(name = "format({0}) == \"{1}\"")
    @CsvSource(
        "0, 00:00:00",
        "1000, 00:00:01",
        "90000, 00:01:30",
        "3600000, 01:00:00",
        "3723000, 01:02:03",
        "360000000, 100:00:00"
    )
    fun formatsMillisecondsAsColonTime(ms: Long, expected: String) {
        assertEquals(expected, UpnpTime.format(ms))
    }

    @org.junit.jupiter.api.Test
    fun formatIgnoresSubSecondPrecision() {
        assertEquals("00:00:01", UpnpTime.format(1999L))
    }

    @org.junit.jupiter.api.Test
    fun parseAndFormatRoundTrip() {
        val original = "02:34:56"
        assertEquals(original, UpnpTime.format(UpnpTime.parseToMs(original)))
    }
}
