package com.flowcortex.user.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.api.AccountDeletionBlocker;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeletionBlockerRegistryTest {

    @Test
    void aDuplicateKeyFailsNamingIt() {
        assertThatThrownBy(() -> new DeletionBlockerRegistry(List.of(blocker("workflow.active_run"),
                blocker("support.open_request"), blocker("workflow.active_run"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow.active_run");
    }

    @Test
    void aMalformedKeyFailsNamingIt() {
        for (String key : List.of("Workflow.ActiveRun", "workflow", "workflow.", "a.b.c")) {
            assertThatThrownBy(() -> new DeletionBlockerRegistry(List.of(blocker(key))))
                    .as(key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    void wellFormedKeysAreListedInKeyOrder() {
        DeletionBlockerRegistry registry = new DeletionBlockerRegistry(
                List.of(blocker("workflow.active_run"), blocker("support.open_request")));

        assertThat(registry.all()).extracting(AccountDeletionBlocker::key)
                .containsExactly("support.open_request", "workflow.active_run");
        assertThat(registry.find("workflow.active_run")).isPresent();
        assertThat(registry.find("workflow.unknown")).isEmpty();
    }

    private static AccountDeletionBlocker blocker(String key) {
        return new AccountDeletionBlocker() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public int defaultSortOrder() {
                return 0;
            }

            @Override
            public String defaultMessageVi() {
                return "vi";
            }

            @Override
            public String defaultMessageEn() {
                return "en";
            }

            @Override
            public boolean blocks(UUID userId) {
                return false;
            }
        };
    }
}
