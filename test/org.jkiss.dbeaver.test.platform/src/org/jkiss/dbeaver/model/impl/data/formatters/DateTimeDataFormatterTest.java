/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.impl.data.formatters;

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DateTimeDataFormatterTest extends DBeaverUnitTest {

    private static final String OPTIONAL_NANOS_PATTERN = "yyyy-MM-dd HH:mm:ss[.fffffffff]";

    private static DateTimeDataFormatter formatter(String pattern, String timezone) {
        Map<String, Object> props = new HashMap<>();
        props.put(DateTimeDataFormatter.PROP_PATTERN, pattern);
        if (timezone != null) {
            props.put(DateTimeDataFormatter.PROP_TIMEZONE, timezone);
        }
        DateTimeDataFormatter formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.US, props);
        return formatter;
    }

    private static LocalDateTime dateTime(int nanos) {
        return LocalDateTime.of(2022, 12, 16, 9, 9, 9, nanos);
    }

    private static Timestamp utcTimestamp(int nanos) {
        return Timestamp.from(dateTime(nanos).toInstant(ZoneOffset.UTC));
    }

    @Test
    public void fractionKeepsLeadingZerosWithTimezone() {
        DateTimeDataFormatter formatter = formatter(OPTIONAL_NANOS_PATTERN, "UTC");
        Assertions.assertEquals("2022-12-16 09:09:09.080000000", formatter.formatValue(utcTimestamp(80_000_000)));
        Assertions.assertEquals("2022-12-16 09:09:09.080000000", formatter.formatValue(dateTime(80_000_000)));
        Assertions.assertEquals("2022-12-16 09:09:09.000000123", formatter.formatValue(utcTimestamp(123)));
        Assertions.assertEquals("2022-12-16 09:09:09.000000123", formatter.formatValue(dateTime(123)));
    }

    @Test
    public void zeroNanosOmitOptionalFractionWithTimezone() {
        DateTimeDataFormatter formatter = formatter(OPTIONAL_NANOS_PATTERN, "UTC");
        Assertions.assertEquals("2022-12-16 09:09:09", formatter.formatValue(utcTimestamp(0)));
        Assertions.assertEquals("2022-12-16 09:09:09", formatter.formatValue(dateTime(0)));
    }

    @Test
    public void timezoneOutputMatchesNoTimezoneOutput() {
        // Without a timezone a Timestamp goes through ExtendedDateFormat in the default timezone
        DateTimeDataFormatter withZone = formatter(OPTIONAL_NANOS_PATTERN, ZoneId.systemDefault().getId());
        DateTimeDataFormatter withoutZone = formatter(OPTIONAL_NANOS_PATTERN, null);
        for (int nanos : new int[]{0, 123, 80_000_000, 500_000_000, 999_999_999}) {
            Timestamp value = utcTimestamp(nanos);
            Assertions.assertEquals(withoutZone.formatValue(value), withZone.formatValue(value), "nanos " + nanos);
        }
    }

    @Test
    public void fractionWidthFollowsPattern() {
        DateTimeDataFormatter millis = formatter("yyyy-MM-dd HH:mm:ss.fff", "UTC");
        Assertions.assertEquals("2022-12-16 09:09:09.080", millis.formatValue(dateTime(80_123_456)));
        Assertions.assertEquals("2022-12-16 09:09:09.000", millis.formatValue(dateTime(0)));
        DateTimeDataFormatter centis = formatter("yyyy-MM-dd HH:mm:ss.ff", "UTC");
        Assertions.assertEquals("2022-12-16 09:09:09.08", centis.formatValue(dateTime(80_123_456)));
        Assertions.assertEquals("2022-12-16 09:09:09.08", centis.formatValue(utcTimestamp(80_123_456)));
    }

    @Test
    public void fractionIsParsedAsFractionOfSecond() throws ParseException {
        DateTimeDataFormatter formatter = formatter(OPTIONAL_NANOS_PATTERN, "UTC");
        Assertions.assertEquals(dateTime(80_000_000), formatter.parseValue("2022-12-16 09:09:09.080", LocalDateTime.class));
        Assertions.assertEquals(dateTime(80_000_000), formatter.parseValue("2022-12-16 09:09:09.08", LocalDateTime.class));
        Assertions.assertEquals(dateTime(500_000_000), formatter.parseValue("2022-12-16 09:09:09.5", LocalDateTime.class));
        Assertions.assertEquals(dateTime(123), formatter.parseValue("2022-12-16 09:09:09.000000123", LocalDateTime.class));
        Assertions.assertEquals(dateTime(0), formatter.parseValue("2022-12-16 09:09:09", LocalDateTime.class));
    }

    @Test
    public void patternsWithoutFractionAreUnchanged() {
        LocalDateTime value = dateTime(80_123_456);
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"}) {
            Assertions.assertEquals(
                DateTimeFormatter.ofPattern(pattern).format(value),
                formatter(pattern, "UTC").formatValue(value),
                pattern);
        }
        // A quoted 'f' is a literal, not a fraction
        Assertions.assertEquals("2022-12-16 f09", formatter("yyyy-MM-dd 'f'HH", "UTC").formatValue(value));
    }
}
