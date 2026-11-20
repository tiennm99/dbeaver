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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.DBDDataFormatter;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.time.ExtendedDateFormat;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class DateTimeDataFormatter implements DBDDataFormatter {

    public static final String PROP_PATTERN = "pattern";
    public static final String PROP_TIMEZONE = "timezone";

    private String pattern;
    private ZoneId zone;
    private DateFormat dateFormat;
    private StringBuffer buffer;
    private FieldPosition position;
    private DateTimeFormatter dateTimeFormatter;
    private DateTimeFormatter zeroNanosFormatter;
    private DateTimeFormatter parseFormatter;
    private boolean hasZone;

    @Override
    public void init(DBSTypedObject type, @NotNull Locale locale, @NotNull Map<String, Object> properties)
    {
        pattern = CommonUtils.toString(properties.get(PROP_PATTERN));
        final String timezone = CommonUtils.toString(properties.get(PROP_TIMEZONE));
        zone = CommonUtils.isEmptyTrimmed(timezone) ? null : ZoneId.of(timezone);
        String sdfPattern = pattern.replace("n", "f");
        dateFormat = new ExtendedDateFormat(
            sdfPattern,
            locale);
        // We shouldn't use lenient formatter (#7244)
        dateFormat.setLenient(false);
        buffer = new StringBuffer();
        position = new FieldPosition(0);
        dateTimeFormatter = buildJavaTimeFormatter(pattern, false, false);
        zeroNanosFormatter = buildJavaTimeFormatter(pattern, true, false);
        parseFormatter = buildJavaTimeFormatter(pattern, false, true);
        hasZone = pattern.contains("Z");
    }

    @Nullable
    public ZoneId getZone() {
        return zone;
    }

    @NotNull
    public DateFormat getDateFormat() {
        return dateFormat;
    }

    @Override
    public String getPattern()
    {
        return pattern;
    }

    @Override
    public String formatValue(Object value)
    {
        if (value instanceof Date && zone != null) {
            return formatTemporal(ZonedDateTime.ofInstant(((Date) value).toInstant(), zone));
        }
        if (value instanceof TemporalAccessor) {
            if (zone != null) {
                if (value instanceof LocalDateTime) {
                    return formatTemporal(((LocalDateTime) value).atZone(zone));
                }
                if (value instanceof ZonedDateTime) {
                    return formatTemporal(((ZonedDateTime) value).withZoneSameInstant(zone));
                }
                if (value instanceof OffsetDateTime) {
                    return formatTemporal(((OffsetDateTime) value).atZoneSameInstant(zone));
                }
            }
            return formatTemporal((TemporalAccessor) value);
        }
        synchronized (dateFormat) {
            buffer.setLength(0);
            return value == null ? null : dateFormat.format(value, buffer, position).toString();
        }
    }

    @Override
    public Object parseValue(String value, Class<?> typeHint) throws ParseException
    {
        if (typeHint != null) {
            if (LocalDateTime.class.isAssignableFrom(typeHint)) {
                try {
                    return LocalDateTime.parse(value, parseFormatter);
                } catch (DateTimeParseException e) {
                    throw new ParseException(e.getParsedString(), e.getErrorIndex());
                }
            }
            if (OffsetDateTime.class.isAssignableFrom(typeHint)) {
                try {
                    return OffsetDateTime.parse(value, parseFormatter);
                } catch (DateTimeParseException e) {
                    throw new ParseException(e.getParsedString(), e.getErrorIndex());
                }
            }
        }
        try {
            if (hasZone) {
                return OffsetDateTime.parse(value, parseFormatter);
            } else {
                return LocalDateTime.parse(value, parseFormatter);
            }
        } catch (Exception e) {
            return dateFormat.parse(value);
        }
    }

    @NotNull
    private String formatTemporal(@NotNull TemporalAccessor value) {
        // ExtendedDateFormat omits an optional fraction section when there are no nanoseconds
        if (value.isSupported(ChronoField.NANO_OF_SECOND) && value.getLong(ChronoField.NANO_OF_SECOND) == 0) {
            return zeroNanosFormatter.format(value);
        }
        return dateTimeFormatter.format(value);
    }

    /**
     * Builds a java.time formatter from an ExtendedDateFormat pattern.
     * There each 'f' is one digit of the fraction of second, while java.time 'n' is the nano-of-second
     * number without leading zeros, so every run of 'f' becomes a fixed-width fraction.
     *
     * @param skipOptionalFraction drop optional sections holding 'f', as ExtendedDateFormat does for zero nanos
     * @param forParse accept a fraction of 1 to 9 digits
     */
    @NotNull
    private static DateTimeFormatter buildJavaTimeFormatter(
        @NotNull String pattern,
        boolean skipOptionalFraction,
        boolean forParse
    ) {
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        StringBuilder chunk = new StringBuilder();
        int length = pattern.length();
        for (int i = 0; i < length; i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = pattern.indexOf('\'', i + 1);
                // An unterminated literal is passed as is, so appendPattern reports it
                end = end < 0 ? length : end + 1;
                chunk.append(pattern, i, end);
                i = end - 1;
            } else if (c == 'f') {
                int end = i;
                while (end < length && pattern.charAt(end) == 'f') {
                    end++;
                }
                if (!chunk.isEmpty()) {
                    builder.appendPattern(chunk.toString());
                    chunk.setLength(0);
                }
                int width = Math.min(end - i, 9);
                builder.appendFraction(ChronoField.NANO_OF_SECOND, forParse ? 1 : width, forParse ? 9 : width, false);
                i = end - 1;
            } else if (c == '[' && skipOptionalFraction && getFractionSectionEnd(pattern, i) > 0) {
                i = getFractionSectionEnd(pattern, i);
            } else {
                chunk.append(c);
            }
        }
        if (!chunk.isEmpty()) {
            builder.appendPattern(chunk.toString());
        }
        return builder.toFormatter();
    }

    /**
     * Returns the index of the ']' closing the optional section that starts at {@code start}
     * if that section holds a fraction ('f' outside quotes), or -1 otherwise.
     */
    private static int getFractionSectionEnd(@NotNull String pattern, int start) {
        boolean hasFraction = false;
        int depth = 0;
        for (int i = start; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = pattern.indexOf('\'', i + 1);
                if (end < 0) {
                    return -1;
                }
                i = end;
            } else if (c == 'f') {
                hasFraction = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return hasFraction ? i : -1;
            }
        }
        return -1;
    }

}
