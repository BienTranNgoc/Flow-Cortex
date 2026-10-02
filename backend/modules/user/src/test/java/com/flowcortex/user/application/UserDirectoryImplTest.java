package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.UserEmail;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserDirectoryImplTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final UserDirectoryImpl directory = new UserDirectoryImpl(repository);

    @Test
    void findEmailsByIdsReadsOnlyIdsAndEmailsInOneLookupAndLeavesUnknownIdsOut() {
        UUID active = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        Set<UUID> ids = Set.of(active, suspended, unknown);
        when(repository.findEmailsByIdIn(ids)).thenReturn(List.of(
                new UserEmail(active, "active@example.com"), new UserEmail(suspended, "suspended@example.com")));

        Map<UUID, String> emails = directory.findEmailsByIds(ids);

        assertThat(emails).containsOnly(
                Map.entry(active, "active@example.com"),
                Map.entry(suspended, "suspended@example.com"));
        verify(repository).findEmailsByIdIn(ids);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void findEmailsByIdsWithNoIdsQueriesNothing() {
        assertThat(directory.findEmailsByIds(List.of())).isEmpty();

        verifyNoInteractions(repository);
    }
}
