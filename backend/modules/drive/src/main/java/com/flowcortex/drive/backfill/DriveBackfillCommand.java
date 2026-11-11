package com.flowcortex.drive.backfill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one-off operator command: it exists only when {@code flowcortex.drive.backfill.mode} is set, runs that mode
 * and ends the process with the mode's exit code, so a normal start never runs it.
 */
@Configuration(proxyBeanMethods = false)
class DriveBackfillCommand {

    @Bean
    @ConditionalOnProperty("flowcortex.drive.backfill.mode")
    ApplicationRunner driveBackfillRunner(DriveBackfill backfill, ApplicationContext context,
            @Value("${flowcortex.drive.backfill.mode}") String mode,
            @Value("${flowcortex.drive.backfill.save:false}") boolean save) {
        return args -> {
            int code = backfill.run(mode, save);
            System.exit(SpringApplication.exit(context, () -> code));
        };
    }
}
