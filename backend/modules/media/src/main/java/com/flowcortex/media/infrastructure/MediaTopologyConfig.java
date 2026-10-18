package com.flowcortex.media.infrastructure;

import com.flowcortex.media.application.MediaProperties;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.apache.commons.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

/**
 * The broker topology the media and provider runtimes expect, declared by this backend only.
 * Each runtime gets a direct exchange, an unrouted queue, job/result/progress quorum queues,
 * a dedicated dead-letter exchange and a DLQ per route.
 */
@Configuration(proxyBeanMethods = false)
class MediaTopologyConfig {

    static final String EXCHANGE = "media";
    static final String UNROUTED = "media.unrouted";
    static final String DEAD_LETTER_EXCHANGE = "media.dlx";
    static final String JOBS = "media.jobs";
    static final String RESULTS = "media.results";
    static final String PROGRESS = "media.progress";
    static final String JOB_KEY = "job";
    static final String RESULT_KEY = "result";
    static final String PROGRESS_KEY = "progress";
    static final String PROVIDER_EXCHANGE = "provider";
    static final String PROVIDER_UNROUTED = "provider.unrouted";
    static final String PROVIDER_DEAD_LETTER_EXCHANGE = "provider.dlx";
    static final String PROVIDER_JOBS = "provider.jobs";
    static final String PROVIDER_RESULTS = "provider.results";
    static final String PROVIDER_PROGRESS = "provider.progress";
    /**
     * Video clips run for minutes, so they get their own queue and worker pool: a clip in progress
     * must not hold a script, storyboard or speech job past its pickup deadline.
     */
    static final String PROVIDER_VIDEO_JOBS = "provider.video.jobs";
    static final String VIDEO_JOB_KEY = "video.job";
    private static final String VIDEO_CAPABILITY = "generate_video";
    /**
     * Returns of a job the broker counts before it dead-letters it. A constant, not a setting: queue
     * arguments are immutable once the queue exists, so a new value means re-creating the job queues.
     * {@code providerJobDeliveryLimit} in {@code contracts/limits.json} mirrors it, and the provider
     * worker's transient-retry budget equals it, so its terminal result arrives before the broker
     * dead-letters the job; change all three together.
     */
    static final int JOB_DELIVERY_LIMIT = 3;

    @Bean
    Declarables mediaTopology(MediaProperties properties) {
        DirectExchange media = ExchangeBuilder.directExchange(EXCHANGE).durable(true).alternate(UNROUTED).build();
        FanoutExchange unrouted = ExchangeBuilder.fanoutExchange(UNROUTED).durable(true).build();
        DirectExchange deadLetters = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED).quorum().build();
        Queue jobs = QueueBuilder.durable(JOBS).quorum()
                .deliveryLimit(JOB_DELIVERY_LIMIT)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .withArgument("x-consumer-timeout", properties.consumerTimeout().toMillis())
                .build();
        Queue results = QueueBuilder.durable(RESULTS).quorum().deadLetterExchange(DEAD_LETTER_EXCHANGE).build();
        Queue progress = QueueBuilder.durable(PROGRESS).quorum().deadLetterExchange(DEAD_LETTER_EXCHANGE).build();
        Queue jobsDlq = QueueBuilder.durable(JOBS + ".dlq").quorum().build();
        Queue resultsDlq = QueueBuilder.durable(RESULTS + ".dlq").quorum().build();
        Queue progressDlq = QueueBuilder.durable(PROGRESS + ".dlq").quorum().build();
        DirectExchange provider = ExchangeBuilder.directExchange(PROVIDER_EXCHANGE).durable(true)
                .alternate(PROVIDER_UNROUTED).build();
        FanoutExchange providerUnrouted = ExchangeBuilder.fanoutExchange(PROVIDER_UNROUTED).durable(true).build();
        DirectExchange providerDeadLetters = ExchangeBuilder.directExchange(PROVIDER_DEAD_LETTER_EXCHANGE)
                .durable(true).build();
        Queue providerUnroutedQueue = QueueBuilder.durable(PROVIDER_UNROUTED).quorum().build();
        Queue providerJobs = QueueBuilder.durable(PROVIDER_JOBS).quorum()
                .deliveryLimit(JOB_DELIVERY_LIMIT)
                .deadLetterExchange(PROVIDER_DEAD_LETTER_EXCHANGE)
                .withArgument("x-consumer-timeout", properties.consumerTimeout().toMillis())
                .build();
        Queue providerResults = QueueBuilder.durable(PROVIDER_RESULTS).quorum()
                .deadLetterExchange(PROVIDER_DEAD_LETTER_EXCHANGE).build();
        Queue providerProgress = QueueBuilder.durable(PROVIDER_PROGRESS).quorum()
                .deadLetterExchange(PROVIDER_DEAD_LETTER_EXCHANGE).build();
        Queue providerVideoJobs = QueueBuilder.durable(PROVIDER_VIDEO_JOBS).quorum()
                .deliveryLimit(JOB_DELIVERY_LIMIT)
                .deadLetterExchange(PROVIDER_DEAD_LETTER_EXCHANGE)
                .withArgument("x-consumer-timeout", properties.consumerTimeout().toMillis())
                .build();
        Queue providerJobsDlq = QueueBuilder.durable(PROVIDER_JOBS + ".dlq").quorum().build();
        Queue providerVideoJobsDlq = QueueBuilder.durable(PROVIDER_VIDEO_JOBS + ".dlq").quorum().build();
        Queue providerResultsDlq = QueueBuilder.durable(PROVIDER_RESULTS + ".dlq").quorum().build();
        Queue providerProgressDlq = QueueBuilder.durable(PROVIDER_PROGRESS + ".dlq").quorum().build();
        return new Declarables(media, unrouted, deadLetters,
                unroutedQueue, jobs, results, progress, jobsDlq, resultsDlq, progressDlq,
                BindingBuilder.bind(unroutedQueue).to(unrouted),
                bind(jobs, media, JOB_KEY), bind(results, media, RESULT_KEY), bind(progress, media, PROGRESS_KEY),
                bind(jobsDlq, deadLetters, JOB_KEY), bind(resultsDlq, deadLetters, RESULT_KEY),
                bind(progressDlq, deadLetters, PROGRESS_KEY), provider, providerUnrouted, providerDeadLetters,
                providerUnroutedQueue, providerJobs, providerResults, providerProgress,
                providerJobsDlq, providerResultsDlq, providerProgressDlq,
                BindingBuilder.bind(providerUnroutedQueue).to(providerUnrouted),
                bind(providerJobs, provider, JOB_KEY), bind(providerResults, provider, RESULT_KEY),
                bind(providerProgress, provider, PROGRESS_KEY),
                bind(providerJobsDlq, providerDeadLetters, JOB_KEY),
                bind(providerResultsDlq, providerDeadLetters, RESULT_KEY),
                bind(providerProgressDlq, providerDeadLetters, PROGRESS_KEY),
                providerVideoJobs, providerVideoJobsDlq,
                bind(providerVideoJobs, provider, VIDEO_JOB_KEY),
                bind(providerVideoJobsDlq, providerDeadLetters, VIDEO_JOB_KEY));
    }

    /** The routing key a provider job of {@code capability} is published with. */
    static String providerJobKey(String capability) {
        return VIDEO_CAPABILITY.equals(capability) ? VIDEO_JOB_KEY : JOB_KEY;
    }

    /**
     * Declares nothing on its own: {@link MediaTopologyCheck} declares once at startup, so a
     * mismatch is reported once and decides readiness instead of surfacing on each new connection.
     */
    @Bean
    RabbitAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        return admin;
    }

    /**
     * A queue declared with other arguments than the broker holds closes the channel, which the
     * connection factory logs at ERROR; when {@link MediaTopologyCheck} is the one declaring, it
     * reports the mismatch as the one ERROR with the fix, so that close is logged at WARN.
     */
    @Bean
    static BeanPostProcessor topologyMismatchReportedOnce() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractConnectionFactory connectionFactory) {
                    connectionFactory.setCloseExceptionLogger(new MismatchAwareCloseLogger(MediaTopologyCheck::isDeclaring));
                }
                return bean;
            }
        };
    }

    /** A message rejected as invalid goes to its DLQ at once; retrying cannot make it valid. */
    @Bean
    RabbitListenerRetrySettingsCustomizer invalidMessagesAreNotRetried() {
        return settings -> settings.setExceptionPredicate(failure -> !rejected(failure));
    }

    /** A message that could not be recorded names its job when it is dead-lettered. */
    @Bean
    MessageRecoverer unrecordedMessagesNameTheirJob() {
        return new UnrecordedMessageRecoverer();
    }

    private static boolean rejected(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }

    static final class MismatchAwareCloseLogger extends AbstractConnectionFactory.DefaultChannelCloseLogger {

        private final BooleanSupplier declaring;

        MismatchAwareCloseLogger(BooleanSupplier declaring) {
            this.declaring = declaring;
        }

        @Override
        public void log(Log logger, String message, Throwable cause) {
            if (declaring.getAsBoolean() && cause instanceof ShutdownSignalException signal
                    && signal.getReason() instanceof AMQP.Channel.Close close
                    && close.getReplyCode() == AMQP.PRECONDITION_FAILED && close.getReplyText().contains("inequivalent arg")) {
                logger.warn(message + ": " + close.getReplyText());
                return;
            }
            super.log(logger, message, cause);
        }
    }

    /** After the last retry: one WARN naming the job, then the message goes to its DLQ. */
    static final class UnrecordedMessageRecoverer extends RejectAndDontRequeueRecoverer {

        private static final Logger LOG = LoggerFactory.getLogger(UnrecordedMessageRecoverer.class);

        @Override
        public void recover(Message message, Throwable cause) {
            if (!rejected(cause)) {
                LOG.warn("Media message for job {} could not be recorded after its retries and goes to its dead-letter"
                        + " queue: {}", Objects.requireNonNullElse(InboundMessages.jobIdOf(message.getBody()), "<unknown>"),
                        NestedExceptionUtils.getMostSpecificCause(cause).getMessage());
            }
            super.recover(message, cause);
        }
    }

    private static Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
