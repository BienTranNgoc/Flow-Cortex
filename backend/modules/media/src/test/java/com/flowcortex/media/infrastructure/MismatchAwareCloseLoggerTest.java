package com.flowcortex.media.infrastructure;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;

/** A queue-argument mismatch closes the channel; that close is a WARN only while the topology is being declared. */
class MismatchAwareCloseLoggerTest {

    private final Log log = mock(Log.class);
    private final ShutdownSignalException mismatch = new ShutdownSignalException(false, false,
            new AMQImpl.Channel.Close(406, "PRECONDITION_FAILED - inequivalent arg 'x-delivery-limit' for queue "
                    + "'media.jobs' in vhost '/'", 50, 10), null);

    @Test
    void whileTheTopologyIsBeingDeclaredTheMismatchIsAWarning() {
        new MediaTopologyConfig.MismatchAwareCloseLogger(() -> true).log(log, "Shutdown Signal", mismatch);

        verify(log).warn(anyString());
        verify(log, never()).error(anyString());
    }

    @Test
    void outsideTheDeclarationItIsLoggedAsTheConnectionFactoryWould() {
        new MediaTopologyConfig.MismatchAwareCloseLogger(() -> false).log(log, "Shutdown Signal", mismatch);

        verify(log).error(anyString());
        verify(log, never()).warn(anyString());
    }
}
