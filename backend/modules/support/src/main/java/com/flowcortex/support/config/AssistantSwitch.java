package com.flowcortex.support.config;

import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.Settings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether the assistant takes questions: the environment switch AND the console switch, decided by
 * the settings registry per call so a console flip applies to the next question.
 */
@Component
public class AssistantSwitch {

    private final SupportProperties properties;
    private final Settings settings;

    public AssistantSwitch(SupportProperties properties, Settings settings) {
        this.properties = properties;
        this.settings = settings;
    }

    public boolean envEnabled() {
        return properties.assistant().enabled();
    }

    /** The console switch as decided before the environment veto: stored value, else on. */
    public boolean consoleEnabled() {
        return settings.get(SupportSettingKeys.ASSISTANT_ENABLED).decided();
    }

    public boolean available() {
        return settings.get(SupportSettingKeys.ASSISTANT_ENABLED).effective();
    }

    /** Who last flipped the console switch and when; both null when it was never flipped. */
    public record Stamp(UUID updatedBy, Instant updatedAt) {
    }

    public Stamp stamp() {
        Optional<SettingChange> change = settings.lastChange(SupportSettingKeys.ASSISTANT_ENABLED);
        return new Stamp(change.map(SettingChange::actor).orElse(null), change.map(SettingChange::at).orElse(null));
    }

    public void setConsole(boolean enabled, UUID actor) {
        settings.set(SupportSettingKeys.ASSISTANT_ENABLED, enabled, actor);
    }
}
