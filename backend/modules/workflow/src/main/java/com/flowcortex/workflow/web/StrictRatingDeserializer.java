package com.flowcortex.workflow.web;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a rating only from a JSON number and keeps its exact decimal value. Jackson's default {@code Number} reading
 * coerces a string such as {@code "5"} and rounds a long decimal to a double, so either could pass as an integer.
 * Any other token is a type mismatch, which the global handler answers with a 400 naming the field.
 */
final class StrictRatingDeserializer extends ValueDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
    }
}
