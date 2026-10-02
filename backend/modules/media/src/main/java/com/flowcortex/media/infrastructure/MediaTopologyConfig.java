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
 * The broker topology the worker expects, declared by this backend only: a direct exchange
 * {@code media} whose unroutable messages are kept on {@code media.unrouted}, three quorum queues
 * that dead-letter to {@code media.dlx} under their original routing key, and a DLQ for each.
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
    /**
     * Returns of a job the broker counts before it dead-letters it. A constant, not a setting: queue
     * arguments are immutable once the queue exists, so a new value means re-creating {@code media.jobs}.
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
        return new Declarables(media, unrouted, deadLetters,
                unroutedQueue, jobs, results, progress, jobsDlq, resultsDlq, progressDlq,
                BindingBuilder.bind(unroutedQueue).to(unrouted),
                bind(jobs, media, JOB_KEY), bind(results, media, RESULT_KEY), bind(progress, media, PROGRESS_KEY),
                bind(jobsDlq, deadLetters, JOB_KEY), bind(resultsDlq, deadLetters, RESULT_KEY),
                bind(progressDlq, deadLetters, PROGRESS_KEY));
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
