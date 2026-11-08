package com.flowcortex.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.FlowCortexApplication;
import com.flowcortex.app.realtime.SseTestClient.Open;
import com.flowcortex.realtime.api.RealtimeEvents;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Two backend instances on one broker of their own: an event published on one reaches the streams
 * held on both, once; and a broker that goes away and comes back costs every open stream exactly
 * one resync, with the instance's queue redeclared under the same name. The broker is stopped and
 * started inside its container so its published port stays the same.
 */
class RealtimeFanoutIT extends AbstractPostgresBootTest {

    private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.3.6-management");

    static {
        BROKER.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Open> streams = new ArrayList<>();
    private final List<ConfigurableApplicationContext> instances = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        streams.forEach(Open::close);
        instances.forEach(ConfigurableApplicationContext::close);
    }

    private static String newVirtualHost() throws Exception {
        String vhost = "rt-" + UUID.randomUUID();
        BROKER.execInContainer("rabbitmqctl", "add_vhost", vhost);
        BROKER.execInContainer("rabbitmqctl", "set_permissions", "-p", vhost, BROKER.getAdminUsername(), ".*", ".*", ".*");
        return vhost;
    }

    private static String newDatabase() throws SQLException {
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        try (java.sql.Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return database;
    }

    private ConfigurableApplicationContext startInstance(String vhost, String database) {
        String jdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(FlowCortexApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.rabbitmq.host=" + BROKER.getHost(),
                "--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
                "--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
                "--spring.rabbitmq.password=" + BROKER.getAdminPassword(),
                "--spring.rabbitmq.virtual-host=" + vhost,
                "--spring.rabbitmq.connection-timeout=2s");
        instances.add(context);
        return context;
    }

    private Open open(ConfigurableApplicationContext instance, UUID user) throws Exception {
        int port = ((ServletWebServerApplicationContext) instance).getWebServer().getPort();
        String token = SseTestClient.token(instance.getBean(JwtEncoder.class), user, Instant.now().plus(Duration.ofMinutes(5)));
        Open stream = SseTestClient.open(http, port, "?topic=user", token, null);
        assertThat(stream.status()).isEqualTo(200);
        streams.add(stream);
        return stream;
    }

    @Test
    void anEventPublishedOnOneInstanceReachesTheStreamsOnBothOnce() throws Exception {
        String vhost = newVirtualHost();
        String database = newDatabase();
        ConfigurableApplicationContext a = startInstance(vhost, database);
        ConfigurableApplicationContext b = startInstance(vhost, database);
        UserView user = a.getBean(UserService.class).createInitialAdmin("fan-" + UUID.randomUUID() + "@example.com",
                "password123", "fan");
        Open onA = open(a, user.id());
        Open onB = open(b, user.id());

        b.getBean(RealtimeEvents.class).publish("user:" + user.id(), "run.updated", Map.of("runId", "r1"));

        onA.await(line -> line.equals("event: run.updated"));
        onB.await(line -> line.equals("event: run.updated"));
        Thread.sleep(1500);
        assertThat(onA.count(line -> line.equals("event: run.updated"))).isEqualTo(1);
        assertThat(onB.count(line -> line.equals("event: run.updated"))).isEqualTo(1);
    }

    @Test
    void aBrokerThatGoesAwayAndComesBackCostsEveryOpenStreamOneResyncAndBlocksNoCaller() throws Exception {
        String vhost = newVirtualHost();
        String database = newDatabase();
        ConfigurableApplicationContext a = startInstance(vhost, database);
        ConfigurableApplicationContext b = startInstance(vhost, database);
        UserView user = a.getBean(UserService.class).createInitialAdmin("fan-" + UUID.randomUUID() + "@example.com",
                "password123", "fan");
        Open onA = open(a, user.id());
        Open onB = open(b, user.id());
        String queueOfA = a.getBean("realtimeEventsQueue", Queue.class).getName();
        assertThat(BROKER.execInContainer("rabbitmqctl", "list_queues", "-p", vhost, "name").getStdout())
                .contains(queueOfA);

        BROKER.execInContainer("rabbitmqctl", "stop_app");
        try {
            JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
            long started = System.nanoTime();
            a.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT 1", Integer.class);
                a.getBean(RealtimeEvents.class).publish("user:" + user.id(), "run.updated", Map.of("runId", "lost"));
            });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            BROKER.execInContainer("rabbitmqctl", "start_app");
        }

        onA.await(line -> line.equals("event: resync"));
        onB.await(line -> line.equals("event: resync"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                BROKER.execInContainer("rabbitmqctl", "list_queues", "-p", vhost, "name").getStdout())
                .contains(queueOfA));
        a.getBean(RealtimeEvents.class).publish("user:" + user.id(), "run.updated", Map.of("runId", "after"));
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            a.getBean(RealtimeEvents.class).publish("user:" + user.id(), "run.updated", Map.of("runId", "after"));
            return onA.count(line -> line.contains("\"runId\":\"after\"")) >= 1
                    && onB.count(line -> line.contains("\"runId\":\"after\"")) >= 1;
        });
        Thread.sleep(1500);
        assertThat(onA.count(line -> line.equals("event: resync"))).isEqualTo(1);
        assertThat(onB.count(line -> line.equals("event: resync"))).isEqualTo(1);
    }
}
