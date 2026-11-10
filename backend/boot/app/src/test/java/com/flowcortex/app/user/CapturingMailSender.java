package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.MailParts;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Records every message instead of opening an SMTP connection. While held, a send blocks until
 * released or for five seconds, whichever comes first. A test can also make every send slow, or
 * refuse it the way an SMTP relay that is down would.
 */
class CapturingMailSender extends JavaMailSenderImpl {

    record CapturedMail(List<String> to, String subject, String body) {
    }

    private final List<CapturedMail> sent = new CopyOnWriteArrayList<>();

    private final ObjectProvider<ThreadPoolTaskExecutor> executor;

    private volatile CountDownLatch held = new CountDownLatch(0);

    private volatile Duration delay = Duration.ZERO;

    private volatile boolean refusing;

    CapturingMailSender(ObjectProvider<ThreadPoolTaskExecutor> executor) {
        this.executor = executor;
    }

    void hold() {
        held = new CountDownLatch(1);
    }

    void release() {
        held.countDown();
    }

    void delayEachSend(Duration perMessage) {
        delay = perMessage;
    }

    void refuseSends(boolean refuse) {
        refusing = refuse;
    }

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
        try {
            held.await(5, TimeUnit.SECONDS);
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (refusing) {
            throw new MailSendException("relay refused the message");
        }
        for (MimeMessage message : mimeMessages) {
            try {
                Address[] recipients = message.getAllRecipients();
                sent.add(new CapturedMail(Arrays.stream(recipients).map(Address::toString).toList(),
                        message.getSubject(), MailParts.textOf(message)));
            } catch (MessagingException | IOException ex) {
                throw new IllegalStateException("Could not read a captured message", ex);
            }
        }
    }

    /** Every mail to the address once the app's task executor, which sends account mail, has drained. */
    List<CapturedMail> sentTo(String address) {
        awaitQueuedSends();
        return sentSoFar(address);
    }

    List<CapturedMail> sentSoFar(String address) {
        return sent.stream().filter(message -> message.to().contains(address)).toList();
    }

    /** Two idle polls in a row: a worker between taking a task and starting it reads as idle once. */
    void awaitQueuedSends() {
        ThreadPoolExecutor pool = executor.getObject().getThreadPoolExecutor();
        Instant deadline = Instant.now().plusSeconds(10);
        int idlePolls = 0;
        while (idlePolls < 2 && Instant.now().isBefore(deadline)) {
            idlePolls = pool.getActiveCount() == 0 && pool.getQueue().isEmpty() ? idlePolls + 1 : 0;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The first capture group of {@code link} in the last mail to the address. */
    String lastTokenFor(String address, Pattern link) {
        List<CapturedMail> messages = sentTo(address);
        assertThat(messages).as("mail to %s", address).isNotEmpty();
        Matcher matcher = link.matcher(messages.getLast().body());
        assertThat(matcher.find()).as("a link in: %s", messages.getLast().body()).isTrue();
        return matcher.group(1);
    }
}
