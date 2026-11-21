package com.flowcortex.app.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailSubscriptions;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.LinkSource;
import com.flowcortex.mail.links.UnsubscribeLink;
import com.flowcortex.mail.links.UnsubscribeLinks;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The member's list state written from the preferences switch and read back by the send path. */
@SpringBootTest(properties = "flowcortex.mail.link-secret=integration-test-link-secret-0123456789abcdef")
class MailSubscriptionsIT extends AbstractPostgresBootTest {

    @Autowired
    private MailSubscriptions subscriptions;

    @Autowired
    private UnsubscribeLinks links;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView admin;

    @BeforeEach
    void admin() {
        admin = userService.createInitialAdmin(email("subs-admin"), "password123", "Admin");
    }

    @Test
    void noLinkRowMeansSubscribed() {
        UserView member = member("subs-none");

        assertThat(subscriptions.subscribed(member.id(), MailCategory.CARE)).isTrue();
        assertThat(subscriptions.subscribed(member.id(), MailCategory.PROMOTIONAL)).isTrue();
        assertThat(linkRows(member)).isZero();
    }

    @Test
    void turningAListOffCreatesTheRowAndRecordsOneUnsubscribe() {
        UserView member = member("subs-off");

        boolean changed = subscriptions.setSubscribed(member.id(), MailCategory.PROMOTIONAL, false);

        assertThat(changed).isTrue();
        assertThat(subscriptions.subscribed(member.id(), MailCategory.PROMOTIONAL)).isFalse();
        assertThat(subscriptions.subscribed(member.id(), MailCategory.CARE)).isTrue();
        assertThat(linkRows(member)).isEqualTo(1);
        assertThat(history(member)).containsExactly(Map.of("list", "marketing", "source", "preferences",
                "action", "unsubscribe"));
    }

    @Test
    void turningItOnAgainRecordsOneResubscribe() {
        UserView member = member("subs-on");
        subscriptions.setSubscribed(member.id(), MailCategory.CARE, false);

        boolean changed = subscriptions.setSubscribed(member.id(), MailCategory.CARE, true);

        assertThat(changed).isTrue();
        assertThat(subscriptions.subscribed(member.id(), MailCategory.CARE)).isTrue();
        assertThat(history(member)).containsExactly(
                Map.of("list", "care", "source", "preferences", "action", "unsubscribe"),
                Map.of("list", "care", "source", "preferences", "action", "resubscribe"));
    }

    @Test
    void settingTheCurrentValueRecordsNothing() {
        UserView member = member("subs-same");

        assertThat(subscriptions.setSubscribed(member.id(), MailCategory.CARE, true)).isFalse();
        subscriptions.setSubscribed(member.id(), MailCategory.CARE, false);
        assertThat(subscriptions.setSubscribed(member.id(), MailCategory.CARE, false)).isFalse();

        assertThat(history(member)).hasSize(1);
    }

    @Test
    void aFooterUnsubscribeReadsAsOff() {
        UserView member = member("subs-footer");
        UnsubscribeLink link = links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD);

        links.apply(tokenOf(link), LinkSource.FOOTER_LINK, "reset-password");

        assertThat(subscriptions.subscribed(member.id(), MailCategory.CARE)).isFalse();
        assertThat(jdbc.queryForObject("SELECT action FROM mail_unsubscribe_history WHERE user_id = ?",
                String.class, member.id())).isEqualTo("unsubscribe");
    }

    @Test
    void transactionalIsRefused() {
        UserView member = member("subs-tx");

        assertThatThrownBy(() -> subscriptions.subscribed(member.id(), MailCategory.TRANSACTIONAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscriptions.setSubscribed(member.id(), MailCategory.TRANSACTIONAL, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(linkRows(member)).isZero();
    }

    @Test
    void thePromotionalSendStateFollowsTheSwitch() {
        UserView member = member("subs-send");

        subscriptions.setSubscribed(member.id(), MailCategory.PROMOTIONAL, false);
        assertThat(links.state(member.id()).marketingUnsubscribed()).isTrue();
        assertThat(links.state(member.id()).careUnsubscribed()).isFalse();

        subscriptions.setSubscribed(member.id(), MailCategory.PROMOTIONAL, true);
        assertThat(links.state(member.id()).marketingUnsubscribed()).isFalse();
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), email(prefix), "password123", "Member", "MEMBER");
    }

    private int linkRows(UserView member) {
        return jdbc.queryForObject("SELECT count(*) FROM mail_unsubscribe_link WHERE user_id = ?", Integer.class,
                member.id());
    }

    private List<Map<String, Object>> history(UserView member) {
        return jdbc.queryForList("SELECT list, source, action FROM mail_unsubscribe_history WHERE user_id = ? "
                + "ORDER BY created_at, id", member.id());
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String tokenOf(UnsubscribeLink link) {
        String url = link.footerUrl();
        int from = url.indexOf("/email/unsubscribe/") + "/email/unsubscribe/".length();
        return url.substring(from, url.indexOf('?'));
    }
}
