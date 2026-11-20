package com.bookworm.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain-Jackson tests (no Spring context) of the strict integer deserializers. The mapper mirrors what the
 * application uses: Boot's "ignore unknown properties" default plus the project's {@link StrictJsonConfig}.
 */
class StrictNumberDeserializersTest {

    public record StrictReq(
            @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity,
            @JsonDeserialize(using = StrictLongDeserializer.class) Long bookId) implements StrictRequest {
    }

    /** A field without the annotation: shows the shared mapper is unchanged. */
    public record DefaultReq(Integer quantity, Long bookId) implements StrictRequest {
    }

    private static final JsonMapper MAPPER = mapper();

    private static JsonMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        new StrictJsonConfig().strictRequestCustomizer().customize(builder);
        return builder.build();
    }

    private static String quantity(String value) {
        return "{\"bookId\":1,\"quantity\":" + value + "}";
    }

    private static String bookId(String value) {
        return "{\"bookId\":" + value + ",\"quantity\":2}";
    }

    @Test
    void validIntegersAreAccepted() throws Exception {
        StrictReq req = MAPPER.readValue("{\"bookId\":42,\"quantity\":3}", StrictReq.class);

        assertThat(req.quantity()).isEqualTo(3);
        assertThat(req.bookId()).isEqualTo(42L);
    }

    @Test
    void boundaryIntegersAreAccepted() throws Exception {
        StrictReq req = MAPPER.readValue(
                "{\"bookId\":9223372036854775807,\"quantity\":2147483647}", StrictReq.class);

        assertThat(req.quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(req.bookId()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void negativeAndZeroIntegersAreStillIntegerTokens() throws Exception {
        // Range checks belong to bean validation; the deserializer only checks the token type.
        StrictReq req = MAPPER.readValue("{\"bookId\":0,\"quantity\":-5}", StrictReq.class);

        assertThat(req.quantity()).isEqualTo(-5);
        assertThat(req.bookId()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"2\"", "\" 2 \"", "\"1e1\"", "\"abc\"", "\"\"", "\" \"",   // strings, including blank
            "2.7", "2.0", "0.0", "-1.5",                                  // decimals
            "1e1", "1E1", "1e0", "2.5e1",                                 // exponents
            "true", "false",                                              // booleans
            "[2]", "[]", "{}", "{\"a\":1}"})                              // arrays and objects
    void quantityRejectsEveryNonIntegerToken(String value) {
        assertThatThrownBy(() -> MAPPER.readValue(quantity(value), StrictReq.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"7\"", "\" 7 \"", "\"\"", "\"abc\"",
            "7.9", "7.0", "1e1", "7E0",
            "true", "false",
            "[7]", "[]", "{}"})
    void bookIdRejectsEveryNonIntegerToken(String value) {
        assertThatThrownBy(() -> MAPPER.readValue(bookId(value), StrictReq.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void valuesOutsideTheTypeRangeAreRejected() {
        assertThatThrownBy(() -> MAPPER.readValue(quantity("2147483648"), StrictReq.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue(quantity("-2147483649"), StrictReq.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue(bookId("9223372036854775808"), StrictReq.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue(bookId("99999999999999999999"), StrictReq.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void nullAndMissingFieldsBecomeNullSoNotNullMustCatchThem() throws Exception {
        // Jackson does not call a deserializer for JSON null or an absent field.
        assertThat(MAPPER.readValue("{\"bookId\":1,\"quantity\":null}", StrictReq.class).quantity()).isNull();
        assertThat(MAPPER.readValue("{\"bookId\":null,\"quantity\":2}", StrictReq.class).bookId()).isNull();
        StrictReq missing = MAPPER.readValue("{}", StrictReq.class);
        assertThat(missing.quantity()).isNull();
        assertThat(missing.bookId()).isNull();
    }

    @Test
    void unknownFieldsAreStillRejectedForStrictRequests() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"bookId\":1,\"quantity\":2,\"price\":1}", StrictReq.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void theSharedMapperIsUnchangedSoUnannotatedFieldsKeepJacksonDefaults() throws Exception {
        // Guard for "no global Jackson change": plain Integer/Long fields (Phase 2/3 DTOs) still use the
        // default coercion. If this test fails, someone changed the shared mapper, which Phase 4 forbids.
        DefaultReq req = MAPPER.readValue("{\"bookId\":\"7\",\"quantity\":2.7}", DefaultReq.class);

        assertThat(req.bookId()).isEqualTo(7L);
        assertThat(req.quantity()).isEqualTo(2);
    }
}
