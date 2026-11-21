package com.flowcortex.user.preferences;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AccountPreferences;
import com.flowcortex.user.api.PreferencesView;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes upsert the row, lock it, merge and save in one transaction, so two PATCHes of different
 * keys never lose each other. Privacy defaults are applied on read and never stored. Account
 * existence and status come from {@link UserDirectory#findById}, which refuses a suspended account.
 */
@Service
class AccountPreferencesImpl implements AccountPreferences {

    private static final Set<String> LOCALES = Set.of("vi", "en");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final AccountPreferenceRepository repository;
    private final UserDirectory userDirectory;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    AccountPreferencesImpl(AccountPreferenceRepository repository, UserDirectory userDirectory, Clock clock) {
        this.repository = repository;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PreferencesView get(UUID userId) {
        requireActiveAccount(userId);
        return repository.findById(userId)
                .map(row -> new PreferencesView(row.getLocale(), UiPreferences.fromMap(read(row.getUi())),
                        PreferenceMerge.withDefaults(PrivacySettings.fromMap(read(row.getPrivacy())))))
                .orElseGet(() -> new PreferencesView(null, UiPreferences.EMPTY,
                        PreferenceMerge.withDefaults(PrivacySettings.EMPTY)));
    }

    @Override
    @Transactional
    public void setLocale(UUID userId, String locale) {
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("Unsupported locale: " + locale);
        }
        lockedRow(userId).setLocale(locale, clock.instant());
    }

    @Override
    @Transactional
    public UiPreferences mergeUi(UUID userId, UiPreferences patch) {
        AccountPreference row = lockedRow(userId);
        UiPreferences merged = PreferenceMerge.mergeUi(UiPreferences.fromMap(read(row.getUi())), patch);
        row.setUi(write(merged.toMap()), clock.instant());
        return merged;
    }

    @Override
    @Transactional
    public PrivacySettings mergePrivacy(UUID userId, PrivacySettings patch) {
        AccountPreference row = lockedRow(userId);
        PrivacySettings stored = PreferenceMerge.storedOver(PrivacySettings.fromMap(read(row.getPrivacy())), patch);
        row.setPrivacy(write(stored.toMap()), clock.instant());
        return PreferenceMerge.withDefaults(stored);
    }

    private AccountPreference lockedRow(UUID userId) {
        requireActiveAccount(userId);
        repository.insertIfAbsent(userId, clock.instant());
        return repository.findByUserIdForUpdate(userId).orElseThrow();
    }

    private void requireActiveAccount(UUID userId) {
        userDirectory.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
    }

    private Map<String, String> read(String stored) {
        return json.readValue(stored, STRING_MAP);
    }

    private String write(Map<String, String> values) {
        return json.writeValueAsString(values);
    }
}
