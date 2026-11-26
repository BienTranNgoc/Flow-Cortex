package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The switch gates the routes and the jobs, never recording: an owning module's delete must index while it is off. */
@Testcontainers
@SpringBootTest(properties = "flowcortex.trash.enabled=false")
@Import(TrashTestProviders.class)
class TrashBinSwitchedOffIT extends AbstractPostgresBootTest {

    @Autowired
    private TrashBin bin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void recordWorksWithTheSwitchOff() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> bin.record(
                new TrashRecord("demo", "p1", UUID.randomUUID(), UUID.randomUUID(), "Bản nháp", "trash.location.demo")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_entry", Integer.class)).isEqualTo(1);
    }
}
