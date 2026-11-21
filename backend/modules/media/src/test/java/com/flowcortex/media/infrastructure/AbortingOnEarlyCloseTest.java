package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;

class AbortingOnEarlyCloseTest {

    @SuppressWarnings("unchecked")
    private final ResponseInputStream<Object> body = mock(ResponseInputStream.class);

    @Test
    void closingBeforeTheEndAbortsTheConnection() throws IOException {
        when(body.read(any(byte[].class), anyInt(), anyInt())).thenReturn(4);
        AbortingOnEarlyClose stream = new AbortingOnEarlyClose(body);

        assertThat(stream.read(new byte[4], 0, 4)).isEqualTo(4);
        stream.close();

        verify(body).abort();
        verify(body, never()).close();
    }

    @Test
    void closingAfterTheEndClosesNormally() throws IOException {
        when(body.read()).thenReturn(-1);
        AbortingOnEarlyClose stream = new AbortingOnEarlyClose(body);

        assertThat(stream.read()).isEqualTo(-1);
        stream.close();

        verify(body).close();
        verify(body, never()).abort();
    }
}
