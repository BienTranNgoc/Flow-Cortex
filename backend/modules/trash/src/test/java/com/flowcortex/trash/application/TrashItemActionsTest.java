package com.flowcortex.trash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.api.RestoreOutcome;
import com.flowcortex.trash.api.TrashItemRef;
import com.flowcortex.trash.api.TrashProvider;
import com.flowcortex.trash.domain.TrashRequestRules.ItemRef;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class TrashItemActionsTest {

    private static final UUID OWNER = UUID.randomUUID();

    private final List<String> purgeCalls = new ArrayList<>();

    @Test
    void typesOfOneRankArePurgedByTypeKeyThenRequestPosition() {
        TrashItemActions actions = actions(provider("beta", 10), provider("alpha", 10), provider("late", 20));

        List<TrashItemActions.Result> results = actions.purgeMany(OWNER, List.of(
                new ItemRef("late", "1"), new ItemRef("beta", "1"), new ItemRef("alpha", "2"),
                new ItemRef("beta", "0"), new ItemRef("alpha", "1")));

        assertThat(purgeCalls).containsExactly("alpha:2", "alpha:1", "beta:1", "beta:0", "late:1");
        assertThat(results).extracting(TrashItemActions.Result::status)
                .containsOnly(TrashItemActions.Status.SUCCEEDED).hasSize(5);
    }

    private TrashItemActions actions(TrashProvider... providers) {
        TrashEntryStore store = mock(TrashEntryStore.class);
        when(store.lockOwned(anyString(), anyString(), eq(OWNER))).thenReturn(Optional.of(UUID.randomUUID()));
        return new TrashItemActions(store, new TrashProviders(List.of(providers)),
                mock(PlatformTransactionManager.class));
    }

    private TrashProvider provider(String type, int rank) {
        return new TrashProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public int purgeRank() {
                return rank;
            }

            @Override
            public boolean remindsBeforeExpiry() {
                return false;
            }

            @Override
            public RestoreOutcome restore(TrashItemRef item) {
                return new RestoreOutcome.Restored();
            }

            @Override
            public PurgeOutcome purge(TrashItemRef item) {
                purgeCalls.add(type + ":" + item.itemId());
                return new PurgeOutcome.Purged();
            }
        };
    }
}
