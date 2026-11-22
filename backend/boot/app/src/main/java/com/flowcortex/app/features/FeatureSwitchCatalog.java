package com.flowcortex.app.features;

import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.api.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Every member feature switch in {@code FeaturesResponse} order. A runtime switch names the registry
 * key an admin changes and is read on every call; an environment-only one holds the server
 * configuration read at startup. The catalog refuses startup when a key it names is not a declared
 * {@code GENERAL} switch, so a rename in the owning module cannot leave a field reading nothing.
 */
@Component
class FeatureSwitchCatalog implements SmartInitializingSingleton {

    /** One switch as served now; {@code settingKey} is null for an environment-only switch. */
    record Reading(String name, String settingKey, boolean value) {
    }

    private record Entry(String name, String settingKey, boolean environmentValue) {
    }

    private final Settings settings;
    private final List<Entry> entries;

    FeatureSwitchCatalog(Settings settings, Environment environment) {
        this.settings = settings;
        this.entries = List.of(
                environmentOnly("emailConfirmation", environment, "flowcortex.auth.email-confirmation.enabled", false),
                environmentOnly("phoneVerification", environment, "flowcortex.phone-verification.enabled", false),
                environmentOnly("uploadContentCheck", environment, "flowcortex.media.upload.content-check.enabled",
                        true),
                runtime("runCancellation", "workflow.run_cancellation.enabled"),
                environmentOnly("credits", environment, "flowcortex.credits.enabled", false),
                runtime("passwordReset", "user.pw_reset.enabled"),
                runtime("supportWidget", "support.help_center.enabled"),
                runtime("supportAssistant", "support.assistant.enabled"),
                environmentOnly("personalAccessTokens", environment, "flowcortex.auth.personal-access-tokens.enabled",
                        false),
                environmentOnly("onboarding", environment, "flowcortex.onboarding.enabled", false),
                environmentOnly("chat", environment, "flowcortex.chat.enabled", false));
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> declared = settings.declaredKeys(SettingScope.GENERAL);
        Set<String> refused = new TreeSet<>();
        for (Entry entry : entries) {
            if (entry.settingKey() == null) {
                continue;
            }
            boolean generalSwitch = declared.contains(entry.settingKey())
                    && settings.describe(entry.settingKey(), SettingScope.GENERAL)
                            .map(SettingDescription::type).filter(SettingType.SWITCH::equals).isPresent();
            if (!generalSwitch) {
                refused.add(entry.settingKey());
            }
        }
        if (!refused.isEmpty()) {
            throw new IllegalStateException(
                    "The feature catalog names keys that are not declared general switches: " + String.join(", ", refused));
        }
    }

    /** Every switch's value now, one registry read per runtime switch. */
    List<Reading> read() {
        List<Reading> readings = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            boolean value = entry.settingKey() == null ? entry.environmentValue()
                    : settings.get(SettingKey.switchKey(entry.settingKey())).effective();
            readings.add(new Reading(entry.name(), entry.settingKey(), value));
        }
        return readings;
    }

    private static Entry runtime(String name, String settingKey) {
        return new Entry(name, settingKey, false);
    }

    private static Entry environmentOnly(String name, Environment environment, String property, boolean fallback) {
        return new Entry(name, null, environment.getProperty(property, Boolean.class, fallback));
    }
}
