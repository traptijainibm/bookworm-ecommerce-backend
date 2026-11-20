package com.bookworm.common.web;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Accepts only a real JSON integer token for an {@code Integer} field. Jackson's default also accepts numeric
 * strings ({@code "2"}), decimals ({@code 2.7}, {@code 2.0}), exponents ({@code 1e1}) and blank strings (read as
 * null); this deserializer rejects all of them, and every other non-integer token, through Jackson's normal
 * unexpected-token path (a 400 {@code MALFORMED_REQUEST}, with no echo of the submitted value).
 *
 * <p>Opt-in per field with {@code @JsonDeserialize(using = StrictIntegerDeserializer.class)}; it does not change
 * the shared mapper. Jackson does not call it for JSON {@code null} or a missing field, so the field still needs
 * {@code @NotNull}. A number outside the {@code int} range fails inside {@code getIntValue()}.
 */
public final class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
    }
}
