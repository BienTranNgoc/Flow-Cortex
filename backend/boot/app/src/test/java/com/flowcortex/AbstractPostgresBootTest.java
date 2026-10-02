package com.flowcortex;

import java.net.URI;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Shared Testcontainers Postgres wiring for :boot:app tests that boot the real application
 * context — no H2; every DB-backed test uses Testcontainers Postgres via Boot's {@code
 * @ServiceConnection}.
 *
 * <p>Static field on this shared base so every subclass reuses the same instance — one
 * container for the whole {@code :boot:app} test run, not one per class. Public (field
 * protected) so subclasses outside this package can extend it.
 *
 * <p>Started via a plain static initializer, deliberately WITHOUT {@code @Container}/{@code
 * @Testcontainers}: JUnit's extension stops an inherited {@code @Container} field at the end of
 * the OWNING class's lifecycle, which races a mid-suite restart against a Spring context cached
 * from an earlier class — still holding a Hikari pool bound to the old, stale port (surfaced
 * once a third {@code @SpringBootTest} class joined the suite). {@code @ServiceConnection} only
 * needs the field + annotation, not the lifecycle extension — so this is start-once, stop-never
 * (Ryuk reaps it when the JVM exits).
 *
 * <p>The broker follows the same start-once pattern: with the AMQP starter on the classpath the
 * {@code rabbit} health indicator reports DOWN without one, which turns {@code /actuator/health}
 * into 503 for every context. So does the object store, with both buckets created once. The
 * backend signs upload URLs for a public endpoint that differs from its internal one only in the
 * host name, so a test can tell which one a URL was signed for.
 */
public abstract class AbstractPostgresBootTest {

    protected static final String UPLOAD_BUCKET = "flowcortex-uploads";
    protected static final String MEDIA_BUCKET = "flowcortex-media";

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.6-management");

    protected static final MinIOContainer SILO = new MinIOContainer(DockerImageName.parse(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .asCompatibleSubstituteFor("minio/minio"));

    static {
        POSTGRES.start();
        RABBIT.start();
        SILO.start();
        try (S3Client store = objectStore()) {
            store.createBucket(bucket -> bucket.bucket(UPLOAD_BUCKET));
            store.createBucket(bucket -> bucket.bucket(MEDIA_BUCKET));
        }
    }

    @DynamicPropertySource
    static void objectStoreProperties(DynamicPropertyRegistry registry) {
        registry.add("flowcortex.media.s3.endpoint", AbstractPostgresBootTest::internalEndpoint);
        registry.add("flowcortex.media.s3.public-endpoint", AbstractPostgresBootTest::publicEndpoint);
        registry.add("flowcortex.media.s3.access-key", SILO::getUserName);
        registry.add("flowcortex.media.s3.secret-key", SILO::getPassword);
    }

    protected static String internalEndpoint() {
        return "http://localhost:" + SILO.getMappedPort(9000);
    }

    protected static String publicEndpoint() {
        return "http://127.0.0.1:" + SILO.getMappedPort(9000);
    }

    /** A root-credential client for arranging and inspecting objects; the caller closes it. */
    protected static S3Client objectStore() {
        return S3Client.builder()
                .endpointOverride(URI.create(internalEndpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(SILO.getUserName(), SILO.getPassword())))
                .build();
    }
}
