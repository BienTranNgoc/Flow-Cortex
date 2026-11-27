package com.flowcortex.workflow.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonParser.NumberType;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads an integer only from a JSON whole number in the {@code int} range. Jackson's default {@code Integer} reading
 * coerces {@code "2"}, {@code 1.5} and {@code 2.0} before bean validation runs, so a revision token that is not a
 * whole number would pass as one. Any other token is a type mismatch, which the global handler answers with a 400
 * naming the field.
 */
final class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT && parser.getNumberType() == NumberType.INT) {
            return parser.getIntValue();
        }
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
    }
}
