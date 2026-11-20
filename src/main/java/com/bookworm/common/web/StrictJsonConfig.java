package com.bookworm.common.web;

import java.util.List;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Rejects unknown JSON properties for {@link StrictRequest} bodies. The exception becomes a 400
 * {@code MALFORMED_REQUEST} through Spring's normal "message not readable" handling, whose detail is a fixed
 * string, so the offending property name is never echoed back to the client.
 */
@Configuration
public class StrictJsonConfig {

    @Bean
    public JsonMapperBuilderCustomizer strictRequestCustomizer() {
        return builder -> {
            builder.addHandler(new RejectUnknownForStrictRequests());
            // A flag must be a real JSON boolean: no 0/1 and no "true"/"false" strings.
            builder.withCoercionConfig(Boolean.class, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            });
            builder.withCoercionConfig(Boolean.TYPE, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            });
        };
    }

    static final class RejectUnknownForStrictRequests extends DeserializationProblemHandler {

        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser parser,
                                             ValueDeserializer<?> deserializer, Object beanOrClass,
                                             String propertyName) {
            Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
            if (StrictRequest.class.isAssignableFrom(type)) {
                throw UnrecognizedPropertyException.from(parser, beanOrClass, propertyName, List.of());
            }
            return false; // not strict: fall back to the mapper's configured behaviour (ignore)
        }
    }
}
