package com.bookworm.common.web;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * The {@code Long} counterpart of {@link StrictIntegerDeserializer}: only a real JSON integer token is accepted;
 * numeric strings, decimals, exponents, blank strings and every other token type are rejected. Opt-in per field
 * with {@code @JsonDeserialize(using = StrictLongDeserializer.class)}; the field still needs {@code @NotNull}.
 */
public final class StrictLongDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getLongValue();
        }
        return (Long) context.handleUnexpectedToken(Long.class, parser);
    }
}
