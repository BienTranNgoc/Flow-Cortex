package com.flowcortex.app.media;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.FlowCortexApplication;
import com.github.dockerjava.api.DockerClient;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * For tests that break the broker on purpose — a queue with other arguments, a broker that does
 * not answer — or that count what the backend's listeners do, which the listeners of every
 * cached test context on the shared broker would otherwise share. The application is started by
 * hand on a broker of its own, in a fresh virtual host per test, so the output can be captured
 * from the first line of startup.
 */
abstract class AbstractIsolatedBrokerIT extends AbstractPostgresBootTest {

    protected static final RabbitMQContainer ISOLATED_BROKER = new RabbitMQContainer("rabbitmq:4.3.6-management");

    static {
        ISOLATED_BROKER.start();
    }

    /** A new, empty virtual host the admin user may use. */
    protected static String newVirtualHost() throws IOException, InterruptedException {
        String vhost = "it-" + UUID.randomUUID();
        ISOLATED_BROKER.execInContainer("rabbitmqctl", "add_vhost", vhost);
        ISOLATED_BROKER.execInContainer("rabbitmqctl", "set_permissions", "-p", vhost,
                ISOLATED_BROKER.getAdminUsername(), ".*", ".*", ".*");
        return vhost;
    }

    /**
     * A new, empty database on the shared Postgres. Needed when the test leaves failed event
     * publications behind: the resubmitter of every other running context would take them.
     */
    protected static String newDatabase() throws SQLException {
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        try (java.sql.Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return database;
    }

    /** The application on {@code vhost} and the default database; {@code customizer} may add initializers. */
    protected static ConfigurableApplicationContext startApplication(String vhost,
            Consumer<SpringApplicationBuilder> customizer, String... extraArgs) {
        return startApplication(vhost, POSTGRES.getJdbcUrl(), customizer, extraArgs);
    }

    protected static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    protected static ConfigurableApplicationContext startApplication(String vhost, String jdbcUrl,
            Consumer<SpringApplicationBuilder> customizer, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.rabbitmq.host=" + ISOLATED_BROKER.getHost(),
                "--spring.rabbitmq.port=" + ISOLATED_BROKER.getAmqpPort(),
                "--spring.rabbitmq.username=" + ISOLATED_BROKER.getAdminUsername(),
                "--spring.rabbitmq.password=" + ISOLATED_BROKER.getAdminPassword(),
                "--spring.rabbitmq.virtual-host=" + vhost));
        args.addAll(List.of(extraArgs));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(FlowCortexApplication.class);
        customizer.accept(builder);
        return builder.run(args.toArray(String[]::new));
    }

    protected static Connection brokerConnection(String vhost) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ISOLATED_BROKER.getHost());
        factory.setPort(ISOLATED_BROKER.getAmqpPort());
        factory.setUsername(ISOLATED_BROKER.getAdminUsername());
        factory.setPassword(ISOLATED_BROKER.getAdminPassword());
        factory.setVirtualHost(vhost);
        return factory.newConnection();
    }

    /** Freezes the broker: connections stay open but nothing answers, so a new one cannot be made. */
    protected static void pauseBroker() {
        docker().pauseContainerCmd(ISOLATED_BROKER.getContainerId()).exec();
    }

    protected static void unpauseBroker() {
        docker().unpauseContainerCmd(ISOLATED_BROKER.getContainerId()).exec();
    }

    /** For a {@code finally}: the next test must find the broker running whatever this one did. */
    protected static void unpauseBrokerIfPaused() {
        if (Boolean.TRUE.equals(docker().inspectContainerCmd(ISOLATED_BROKER.getContainerId()).exec().getState()
                .getPaused())) {
            unpauseBroker();
        }
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }
}
