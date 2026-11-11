package com.flowcortex.app.chat;

import com.flowcortex.media.api.MediaJobs;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The one application context every chat IT and the run request-key IT share: chat, credits and Product Video
 * on, the provider and the event seam stubbed; the chat ITs price echo at 10 and probe at 40 in the seeded
 * {@code rate_card} rows around each test. A test that needs a different
 * setting flips it at runtime through {@link ChatTestConfig}; a property of its own would build another
 * context, and the 1 GB test heap holds only a handful. {@code MediaJobs} is a spy so a test can count its calls.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.chat.enabled=true",
        "flowcortex.product-video.enabled=true",
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
@Import(ChatTestConfig.class)
@MockitoSpyBean(types = MediaJobs.class)
public @interface ChatIt {
}
