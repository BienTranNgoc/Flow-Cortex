package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The environment switch, the console switch and the admin settings route that flips the console one. */
@ChatIt
class ChatSwitchIT extends ChatItSupport {

    private static final String SETTINGS = "/api/v1/admin/chat/settings";

    record Settings(boolean enabled, String updatedBy, Instant updatedAt) {
    }

    record SettingsUpdate(Boolean enabled) {
    }

    record ListResponse(List<Object> items, boolean chatEnabled) {
    }

    record Features(Boolean chat) {
    }

    @Test
    void withTheConsoleSwitchOffASendIs503AndTheHistoryStaysReadable() {
        UserView owner = member("switch-off");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Kept");
        seedMessage(conversation, 1, "user", "earlier", "done");
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");
        int globalBefore = globalCount();

        ResponseEntity<Problem> send = send(token, conversation, UUID.randomUUID().toString(), "hello",
                Problem.class);
        ResponseEntity<ListResponse> list = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ListResponse.class);
        ResponseEntity<Msg[]> history = restTemplate.exchange(BASE + "/" + conversation + "/messages",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), Msg[].class);

        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(send.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-disabled"));
        assertThat(send.getBody().detail()).isNotBlank();
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().chatEnabled()).isFalse();
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).hasSize(1);
        assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(1);
        assertThat(memberCount(owner.id())).isZero();
        assertThat(globalCount()).isEqualTo(globalBefore);
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void anAdminFlipsTheConsoleSwitchAndTheRowRecordsWhoAndWhen() {
        String token = login(admin.email());
        Instant before = Instant.now().minusSeconds(1);

        ResponseEntity<Settings> off = restTemplate.exchange(SETTINGS, HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(false), bearer(token)), Settings.class);
        ResponseEntity<Settings> read = restTemplate.exchange(SETTINGS, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Settings.class);
        ResponseEntity<Settings> on = restTemplate.exchange(SETTINGS, HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(true), bearer(token)), Settings.class);

        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().enabled()).isFalse();
        assertThat(off.getBody().updatedBy()).isEqualTo(admin.email());
        assertThat(off.getBody().updatedAt()).isAfter(before);
        assertThat(read.getBody()).isEqualTo(off.getBody());
        assertThat(on.getBody().enabled()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT updated_by FROM chat_settings WHERE id = 1", UUID.class))
                .isEqualTo(admin.id());
    }

    @Test
    void aMemberOrAnAnonymousCallerCannotReadOrFlipTheSwitch() {
        String token = login(member("switch-member").email());

        ResponseEntity<String> put = restTemplate.exchange(SETTINGS, HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(false), bearer(token)), String.class);
        ResponseEntity<String> get = restTemplate.exchange(SETTINGS, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        ResponseEntity<String> anonymous = restTemplate.getForEntity(SETTINGS, String.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM chat_settings WHERE id = 1", Boolean.class))
                .isTrue();
    }

    @Test
    void anUpdateWithoutTheFlagIsRefused() {
        ResponseEntity<Problem> response = restTemplate.exchange(SETTINGS, HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(null), bearer(login(admin.email()))), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("enabled");
    }

    /** The environment switch is off while the console row is on. */
    @Test
    void withTheEnvironmentSwitchOffASendIs503AndTheListReportsChatOffWhateverTheConsoleRowSays() {
        UserView owner = member("switch-env");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        chatSwitch.environmentEnabled(false);

        ResponseEntity<Problem> send = send(token, conversation, UUID.randomUUID().toString(), "hello",
                Problem.class);
        ResponseEntity<ListResponse> list = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ListResponse.class);

        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(send.getBody().code()).isEqualTo("chat-disabled");
        assertThat(list.getBody().chatEnabled()).isFalse();
        assertThat(rows("chat_message", "conversation_id", conversation)).isZero();
    }

    @Test
    void theFeaturesEndpointReportsChatOnWhenTheEnvironmentSwitchIsOn() {
        ResponseEntity<Features> response = restTemplate.getForEntity("/api/v1/features", Features.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().chat()).isTrue();
    }
}
