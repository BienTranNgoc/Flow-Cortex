package com.flowcortex.trash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.api.RestoreOutcome;
import com.flowcortex.trash.api.TrashItemRef;
import com.flowcortex.trash.api.TrashProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrashProvidersTest {

    @Test
    void duplicateTypeFailsNamingIt() {
        assertThatThrownBy(() -> new TrashProviders(List.of(stub("demo", 10, true), stub("demo", 20, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    void malformedTypeFailsNamingIt() {
        assertThatThrownBy(() -> new TrashProviders(List.of(stub("Demo-X", 10, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo-X");
    }

    @Test
    void findsAProviderByType() {
        TrashProvider demo = stub("demo", 10, true);
        TrashProviders providers = new TrashProviders(List.of(demo, stub("demo_late", 20, false)));

        assertThat(providers.find("demo")).containsSame(demo);
        assertThat(providers.find("retired")).isEmpty();
    }

    @Test
    void ranksAreExposedInOrder() {
        TrashProviders providers = new TrashProviders(
                List.of(stub("demo_late", 20, false), stub("zeta", 10, false), stub("demo", 10, true)));

        assertThat(providers.types()).containsExactly("demo", "zeta", "demo_late");
        assertThat(providers.ranked()).extracting(TrashProvider::type).containsExactly("demo", "zeta", "demo_late");
        assertThat(providers.rankByType()).containsExactly(Map.entry("demo", 10), Map.entry("zeta", 10),
                Map.entry("demo_late", 20));
    }

    @Test
    void remindingTypesAreTheOptedInOnes() {
        TrashProviders providers = new TrashProviders(List.of(stub("demo_late", 20, false), stub("demo", 10, true)));

        assertThat(providers.remindingTypes()).containsExactly("demo");
    }

    @Test
    void noProviderIsAValidRegistry() {
        TrashProviders providers = new TrashProviders(List.of());

        assertThat(providers.types()).isEmpty();
        assertThat(providers.remindingTypes()).isEmpty();
    }

    private static TrashProvider stub(String type, int rank, boolean reminds) {
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
                return reminds;
            }

            @Override
            public RestoreOutcome restore(TrashItemRef item) {
                return new RestoreOutcome.Restored();
            }

            @Override
            public PurgeOutcome purge(TrashItemRef item) {
                return new PurgeOutcome.Purged();
            }
        };
    }
}
