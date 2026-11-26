package com.flowcortex.app.asset;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Library routes over HTTP with spaces on and a real login. Rows are written straight into the asset
 * tables with explicit times, and files straight into the media bucket, so each test decides what the
 * routes see. Every asset statement the application prepares is recorded in {@link #STATEMENTS}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
@Import(AbstractAssetHttpIT.StatementCounting.class)
abstract class AbstractAssetHttpIT extends AbstractWorkflowHttpIT {

    protected static final String ASSETS = "/api/v1/assets";
    protected static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    private static final Pattern ASSET_TABLES = Pattern.compile("space_asset(?!_registration)");

    record ProblemView(int status, String title, String detail, java.util.Map<String, String> errors) {
    }

    /** Records every prepared statement that reads or writes an asset table. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StatementCounting {

        @Bean
        static BeanPostProcessor countPreparedStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource ? counting(dataSource) : bean;
                }
            };
        }

        private static DataSource counting(DataSource target) {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        return result instanceof Connection connection ? counting(connection) : result;
                    });
        }

        private static Connection counting(Connection target) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                        && ASSET_TABLES.matcher(sql).find()) {
                    STATEMENTS.add(sql);
                }
                return invoke(target, method, args);
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private SessionSpaces sessionSpaces;

    @MockitoSpyBean
    protected AssetFiles assetFiles;

    private final List<UUID> spacesSeeded = new ArrayList<>();
    private final List<UUID> registrationsSeeded = new ArrayList<>();

    @AfterEach
    void removeSeededRows() {
        for (UUID space : spacesSeeded) {
            jdbc.update("DELETE FROM space_asset WHERE space_id = ?", space);
        }
        for (UUID registration : registrationsSeeded) {
            jdbc.update("DELETE FROM space_asset_registration WHERE id = ?", registration);
        }
    }

    protected Member member(String prefix) {
        UserView user = newUser(prefix);
        UUID space = sessionSpaces.sessionSpace(user.id()).spaceId();
        spacesSeeded.add(space);
        return new Member(user.id(), space, loginAs(user));
    }

    /** One signed-in account with its space; seeding writes straight to the tables and the bucket. */
    protected final class Member {

        private final UUID userId;
        private final UUID spaceId;
        private final String token;

        Member(UUID userId, UUID spaceId, String token) {
            this.userId = userId;
            this.spaceId = spaceId;
            this.token = token;
        }

        UUID userId() {
            return userId;
        }

        /** An upload asset of {@code kind} with no stored object. */
        UUID upload(String kind, String title, Instant createdAt) {
            return insert(kind, title, "upload", UUID.randomUUID(), createdAt, mimeOf(kind), "it/" + UUID.randomUUID());
        }

        /** An upload asset used as an input of {@code runId}, with no stored object. */
        UUID input(String kind, String title, UUID runId, Instant createdAt) {
            return insert(kind, title, "upload", runId, createdAt, mimeOf(kind), "it/" + UUID.randomUUID());
        }

        /** A {@code run} output of {@code runId} with no stored object. */
        UUID output(String kind, String title, UUID runId, Instant createdAt) {
            return insert(kind, title, "run", runId, createdAt, mimeOf(kind), "it/" + UUID.randomUUID());
        }

        /** An upload asset whose object is stored in the media bucket with {@code mime} and {@code content}. */
        UUID storedAsset(String kind, String title, String mime, byte[] content, Instant createdAt) {
            String key = "it/" + UUID.randomUUID();
            putObject(key, mime, content);
            return insert(kind, title, "upload", UUID.randomUUID(), createdAt, mime, key);
        }

        String objectKeyOf(UUID assetId) {
            return jdbc.queryForObject("SELECT object_key FROM space_asset WHERE id = ?", String.class, assetId);
        }

        void putObject(String key, String mime, byte[] content) {
            try (S3Client store = objectStore()) {
                store.putObject(o -> o.bucket(MEDIA_BUCKET).key(key).contentType(mime), RequestBody.fromBytes(content));
            }
        }

        void deleteObject(String key) {
            try (S3Client store = objectStore()) {
                store.deleteObject(o -> o.bucket(MEDIA_BUCKET).key(key));
            }
        }

        void markMissing(UUID assetId) {
            jdbc.update("UPDATE space_asset SET missing_at = now() WHERE id = ?", assetId);
        }

        /** The registration row of a run's outputs, owned by this member unless {@code ownerId} says otherwise. */
        void registration(UUID runId, UUID ownerId, String status) {
            UUID id = UUID.randomUUID();
            registrationsSeeded.add(id);
            jdbc.update("INSERT INTO space_asset_registration (id, source_type, source_id, owner_id, status, "
                    + "created_at, updated_at) VALUES (?, 'run_outputs', ?, ?, ?, now(), now())", id, runId, ownerId,
                    status);
        }

        Instant missingAt(UUID assetId) {
            Timestamp at = jdbc.queryForObject("SELECT missing_at FROM space_asset WHERE id = ?", Timestamp.class,
                    assetId);
            return at == null ? null : at.toInstant();
        }

        <T> ResponseEntity<T> get(String path, Class<T> type) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            return restTemplate.exchange(ASSETS + path, HttpMethod.GET, new HttpEntity<>(headers), type);
        }

        private UUID insert(String kind, String title, String source, UUID runId, Instant createdAt, String mime,
                String objectKey) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO space_asset (id, space_id, owner_id, media_asset_id, source, kind, title, "
                    + "title_base, title_version, bucket, object_key, mime_type, bytes, run_id, run_step_id, "
                    + "workflow_id, workflow_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, "
                    + "NULL, NULL, ?)", id, spaceId, userId, UUID.randomUUID(), source, kind, title, title,
                    MEDIA_BUCKET, objectKey, mime, 1_000L, runId, source.equals("run") ? UUID.randomUUID() : null,
                    Timestamp.from(createdAt));
            return id;
        }

        private static String mimeOf(String kind) {
            return switch (kind) {
                case "video" -> "video/mp4";
                case "audio" -> "audio/mpeg";
                default -> "image/png";
            };
        }
    }
}
