package com.isums.contractservice.configurations;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public class VnptInstantDeserializer extends ValueDeserializer<Instant> {

    private static final DateTimeFormatter F = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private static final ZoneOffset DEFAULT_OFFSET = ZoneOffset.UTC;

    @Override
    public Instant deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws JacksonException {
        String s = jsonParser.getValueAsString();
        if (s == null || s.isBlank()) return null;

        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignore) {
        }
        LocalDateTime ldt = LocalDateTime.parse(s, F);
        return ldt.toInstant(DEFAULT_OFFSET);
    }
}
