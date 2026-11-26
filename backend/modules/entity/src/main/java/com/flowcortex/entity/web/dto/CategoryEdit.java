package com.flowcortex.entity.web.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * A category edit: a text sets it, JSON {@code null} clears it. An absent key leaves the request holding {@code null};
 * {@code Optional} cannot tell the two apart because Jackson reads an absent key as the type's null value.
 */
@JsonDeserialize(using = CategoryEdit.Reader.class)
public record CategoryEdit(String label) {

    static final class Reader extends StdDeserializer<CategoryEdit> {

        Reader() {
            super(CategoryEdit.class);
        }

        @Override
        public CategoryEdit deserialize(JsonParser parser, DeserializationContext context) {
            return new CategoryEdit(context.readValue(parser, String.class));
        }

        @Override
        public CategoryEdit getNullValue(DeserializationContext context) {
            return new CategoryEdit(null);
        }

        @Override
        public CategoryEdit getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
