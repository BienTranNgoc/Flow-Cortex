package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AdminAuditActorReaderTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdminAuditActorReader reader = new AdminAuditActorReader(jdbc);

    @Test
    void readsTheCurrentEmailAndName() {
        when(jdbc.queryForList(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("email", "admin@x.io", "name", "Ada")));

        assertThat(reader.read(UUID.randomUUID())).isEqualTo(new ActorSnapshot("admin@x.io", "Ada"));
    }

    @Test
    void aFailedLookupYieldsAnEmptySnapshot() {
        when(jdbc.queryForList(anyString(), anyMap())).thenThrow(new DataAccessResourceFailureException("no"));

        assertThat(reader.read(UUID.randomUUID())).isEqualTo(ActorSnapshot.UNKNOWN);
    }

    @Test
    void anActorWithNoRowYieldsAnEmptySnapshot() {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

        assertThat(reader.read(UUID.randomUUID())).isEqualTo(ActorSnapshot.UNKNOWN);
    }
}
