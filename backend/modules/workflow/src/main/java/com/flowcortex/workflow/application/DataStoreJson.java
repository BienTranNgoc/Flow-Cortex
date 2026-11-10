package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The one Jackson mapper for the JSON a workflow's data store holds, so a number read from a request, from
 * PostgreSQL's {@code jsonb} or from a template comes back with the digits it went in with.
 */
public final class DataStoreJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private DataStoreJson() {}

    /** Thread-safe once built; callers must not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
