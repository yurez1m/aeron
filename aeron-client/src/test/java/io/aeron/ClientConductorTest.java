/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.command.ControlProtocolEvents;
import io.aeron.command.CorrelatedMessageFlyweight;
import io.aeron.command.ErrorResponseFlyweight;
import io.aeron.command.PublicationBuffersReadyFlyweight;
import io.aeron.exceptions.ConductorServiceTimeoutException;
import io.aeron.exceptions.DriverTimeoutException;
import io.aeron.exceptions.RegistrationException;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.ErrorHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.*;
import org.agrona.concurrent.broadcast.CopyBroadcastReceiver;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.ToIntFunction;

import static io.aeron.ErrorCode.INVALID_CHANNEL;
import static io.aeron.logbuffer.LogBufferDescriptor.PARTITION_COUNT;
import static io.aeron.logbuffer.LogBufferDescriptor.TERM_MIN_LENGTH;
import static java.lang.Boolean.TRUE;
import static java.nio.ByteBuffer.allocateDirect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class ClientConductorTest
{
    private static final int TERM_BUFFER_LENGTH = TERM_MIN_LENGTH;

    protected static final int SESSION_ID_1 = 13;
    protected static final int SESSION_ID_2 = 15;

    private static final String CHANNEL = "aeron:udp?endpoint=localhost:40124";
    private static final int STREAM_ID_1 = 2;
    private static final int STREAM_ID_2 = 4;
    private static final int SEND_BUFFER_CAPACITY = 1024;
    private static final int COUNTER_BUFFER_LENGTH = 1024;

    private static final long CORRELATION_ID = 2000;
    private static final long CORRELATION_ID_2 = 2002;
    private static final long CLOSE_CORRELATION_ID = 2001;
    private static final long UNKNOWN_CORRELATION_ID = 3000;

    private static final long KEEP_ALIVE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long AWAIT_TIMEOUT = 100;
    private static final long INTER_SERVICE_TIMEOUT_MS = 1000;
    private static final long PUBLICATION_CONNECTION_TIMEOUT_MS = 5000;

    private static final int SUBSCRIBER_POSITION_ID = 2;
    private static final int SUBSCRIBER_POSITION_REGISTRATION_ID = 4001;

    private static final String SOURCE_INFO = "127.0.0.1:40789";

    private final PublicationBuffersReadyFlyweight publicationReady = new PublicationBuffersReadyFlyweight();
    private final CorrelatedMessageFlyweight correlatedMessage = new CorrelatedMessageFlyweight();
    private final ErrorResponseFlyweight errorResponse = new ErrorResponseFlyweight();

    private final UnsafeBuffer publicationReadyBuffer = new UnsafeBuffer(allocateDirect(SEND_BUFFER_CAPACITY));
    private final UnsafeBuffer correlatedMessageBuffer = new UnsafeBuffer(allocateDirect(SEND_BUFFER_CAPACITY));
    private final UnsafeBuffer errorMessageBuffer = new UnsafeBuffer(allocateDirect(SEND_BUFFER_CAPACITY));

    private final CopyBroadcastReceiver mockToClientReceiver = mock(CopyBroadcastReceiver.class);

    private final UnsafeBuffer counterValuesBuffer = new UnsafeBuffer(allocateDirect(COUNTER_BUFFER_LENGTH));

    private long timeMs = 0;
    private final EpochClock epochClock = () -> timeMs += 10;

    private long timeNs = 0;
    private final NanoClock nanoClock = () -> timeNs += 10_000_000;

    private final ErrorHandler mockClientErrorHandler = spy(new PrintError());

    private DriverProxy driverProxy = mock(DriverProxy.class);
    private ClientConductor conductor;
    private AvailableImageHandler mockAvailableImageHandler = mock(AvailableImageHandler.class);
    private UnavailableImageHandler mockUnavailableImageHandler = mock(UnavailableImageHandler.class);
    private LogBuffersFactory logBuffersFactory = mock(LogBuffersFactory.class);
    private Lock mockClientLock = mock(Lock.class);
    private boolean suppressPrintError = false;

    @Before
    public void setUp() throws Exception
    {
        final Aeron.Context ctx = new Aeron.Context()
            .clientLock(mockClientLock)
            .epochClock(epochClock)
            .nanoClock(nanoClock)
            .toClientBuffer(mockToClientReceiver)
            .driverProxy(driverProxy)
            .logBuffersFactory(logBuffersFactory)
            .errorHandler(mockClientErrorHandler)
            .availableImageHandler(mockAvailableImageHandler)
            .unavailableImageHandler(mockUnavailableImageHandler)
            .keepAliveInterval(KEEP_ALIVE_INTERVAL)
            .driverTimeoutMs(AWAIT_TIMEOUT)
            .interServiceTimeout(TimeUnit.MILLISECONDS.toNanos(INTER_SERVICE_TIMEOUT_MS));

        ctx.countersValuesBuffer(counterValuesBuffer);

        when(mockClientLock.tryLock()).thenReturn(TRUE);

        when(driverProxy.addPublication(CHANNEL, STREAM_ID_1)).thenReturn(CORRELATION_ID);
        when(driverProxy.addPublication(CHANNEL, STREAM_ID_2)).thenReturn(CORRELATION_ID_2);
        when(driverProxy.removePublication(CORRELATION_ID)).thenReturn(CLOSE_CORRELATION_ID);
        when(driverProxy.addSubscription(anyString(), anyInt())).thenReturn(CORRELATION_ID);
        when(driverProxy.removeSubscription(CORRELATION_ID)).thenReturn(CLOSE_CORRELATION_ID);

        conductor = new ClientConductor(ctx);

        publicationReady.wrap(publicationReadyBuffer, 0);
        correlatedMessage.wrap(correlatedMessageBuffer, 0);
        errorResponse.wrap(errorMessageBuffer, 0);

        publicationReady.correlationId(CORRELATION_ID);
        publicationReady.registrationId(CORRELATION_ID);
        publicationReady.sessionId(SESSION_ID_1);
        publicationReady.streamId(STREAM_ID_1);
        publicationReady.logFileName(SESSION_ID_1 + "-log");

        correlatedMessage.correlationId(CLOSE_CORRELATION_ID);

        final UnsafeBuffer[] termBuffersSession1 = new UnsafeBuffer[PARTITION_COUNT];
        final UnsafeBuffer[] termBuffersSession2 = new UnsafeBuffer[PARTITION_COUNT];

        for (int i = 0; i < PARTITION_COUNT; i++)
        {
            termBuffersSession1[i] = new UnsafeBuffer(allocateDirect(TERM_BUFFER_LENGTH));
            termBuffersSession2[i] = new UnsafeBuffer(allocateDirect(TERM_BUFFER_LENGTH));
        }

        final UnsafeBuffer logMetaDataSession1 = new UnsafeBuffer(allocateDirect(TERM_BUFFER_LENGTH));
        final UnsafeBuffer logMetaDataSession2 = new UnsafeBuffer(allocateDirect(TERM_BUFFER_LENGTH));

        final MutableDirectBuffer header1 = DataHeaderFlyweight.createDefaultHeader(SESSION_ID_1, STREAM_ID_1, 0);
        final MutableDirectBuffer header2 = DataHeaderFlyweight.createDefaultHeader(SESSION_ID_2, STREAM_ID_2, 0);

        LogBufferDescriptor.storeDefaultFrameHeader(logMetaDataSession1, header1);
        LogBufferDescriptor.storeDefaultFrameHeader(logMetaDataSession2, header2);

        final LogBuffers logBuffersSession1 = mock(LogBuffers.class);
        final LogBuffers logBuffersSession2 = mock(LogBuffers.class);

        when(logBuffersFactory.map(SESSION_ID_1 + "-log")).thenReturn(logBuffersSession1);
        when(logBuffersFactory.map(SESSION_ID_2 + "-log")).thenReturn(logBuffersSession2);
        when(logBuffersFactory.map(SESSION_ID_1 + "-log")).thenReturn(logBuffersSession1);
        when(logBuffersFactory.map(SESSION_ID_2 + "-log")).thenReturn(logBuffersSession2);

        when(logBuffersSession1.duplicateTermBuffers()).thenReturn(termBuffersSession1);
        when(logBuffersSession2.duplicateTermBuffers()).thenReturn(termBuffersSession2);

        when(logBuffersSession1.metaDataBuffer()).thenReturn(logMetaDataSession1);
        when(logBuffersSession2.metaDataBuffer()).thenReturn(logMetaDataSession2);
    }

    // --------------------------------
    // Publication related interactions
    // --------------------------------

    @Test
    public void addPublicationShouldNotifyMediaDriver() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY,
            publicationReadyBuffer,
            (buffer) -> publicationReady.length());

        conductor.addPublication(CHANNEL, STREAM_ID_1);

        verify(driverProxy).addPublication(CHANNEL, STREAM_ID_1);
    }

    @Test
    public void addPublicationShouldMapLogFile() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY,
            publicationReadyBuffer,
            (buffer) -> publicationReady.length());

        conductor.addPublication(CHANNEL, STREAM_ID_1);

        verify(logBuffersFactory).map(SESSION_ID_1 + "-log");
    }

    @Test(expected = DriverTimeoutException.class, timeout = 5_000)
    public void addPublicationShouldTimeoutWithoutReadyMessage()
    {
        conductor.addPublication(CHANNEL, STREAM_ID_1);
    }

    @Test
    public void closingPublicationShouldNotifyMediaDriver() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY, publicationReadyBuffer, (buffer) -> publicationReady.length());

        final Publication publication = conductor.addPublication(CHANNEL, STREAM_ID_1);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) -> CorrelatedMessageFlyweight.LENGTH);

        publication.close();

        verify(driverProxy).removePublication(CORRELATION_ID);
    }

    @Test
    public void closingPublicationShouldPurgeCache() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY, publicationReadyBuffer, (buffer) -> publicationReady.length());

        final Publication firstPublication = conductor.addPublication(CHANNEL, STREAM_ID_1);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) -> CorrelatedMessageFlyweight.LENGTH);

        firstPublication.close();

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY, publicationReadyBuffer, (buffer) -> publicationReady.length());

        final Publication secondPublication = conductor.addPublication(CHANNEL, STREAM_ID_1);

        assertThat(firstPublication, not(sameInstance(secondPublication)));
    }

    @Test(expected = RegistrationException.class)
    public void shouldFailToClosePublicationOnMediaDriverError()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY, publicationReadyBuffer, (buffer) -> publicationReady.length());

        final Publication publication = conductor.addPublication(CHANNEL, STREAM_ID_1);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_ERROR,
            errorMessageBuffer,
            (buffer) ->
            {
                errorResponse.errorCode(INVALID_CHANNEL);
                errorResponse.errorMessage("channel unknown");
                errorResponse.offendingCommandCorrelationId(CLOSE_CORRELATION_ID);
                return errorResponse.length();
            });

        publication.close();
    }

    @Test(expected = RegistrationException.class)
    public void shouldFailToAddPublicationOnMediaDriverError()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_ERROR,
            errorMessageBuffer,
            (buffer) ->
            {
                errorResponse.errorCode(INVALID_CHANNEL);
                errorResponse.errorMessage("invalid channel");
                errorResponse.offendingCommandCorrelationId(CORRELATION_ID);
                return errorResponse.length();
            });

        conductor.addPublication(CHANNEL, STREAM_ID_1);
    }

    @Test
    public void closingPublicationDoesNotRemoveOtherPublications() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY, publicationReadyBuffer, (buffer) -> publicationReady.length());

        final Publication publication = conductor.addPublication(CHANNEL, STREAM_ID_1);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY,
            publicationReadyBuffer,
            (buffer) ->
            {
                publicationReady.streamId(STREAM_ID_2);
                publicationReady.sessionId(SESSION_ID_2);
                publicationReady.logFileName(SESSION_ID_2 + "-log");
                publicationReady.correlationId(CORRELATION_ID_2);
                publicationReady.registrationId(CORRELATION_ID_2);
                return publicationReady.length();
            });

        conductor.addPublication(CHANNEL, STREAM_ID_2);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) -> CorrelatedMessageFlyweight.LENGTH);

        publication.close();

        verify(driverProxy).removePublication(CORRELATION_ID);
        verify(driverProxy, never()).removePublication(CORRELATION_ID_2);
    }

    @Test
    public void shouldNotMapBuffersForUnknownCorrelationId() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY,
            publicationReadyBuffer,
            (buffer) ->
            {
                publicationReady.correlationId(UNKNOWN_CORRELATION_ID);
                publicationReady.registrationId(UNKNOWN_CORRELATION_ID);
                return publicationReady.length();
            });

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_PUBLICATION_READY,
            publicationReadyBuffer,
            (buffer) ->
            {
                publicationReady.correlationId(CORRELATION_ID);
                return publicationReady.length();
            });

        final Publication publication = conductor.addPublication(CHANNEL, STREAM_ID_1);
        conductor.doWork();

        verify(logBuffersFactory, times(1)).map(anyString());
        assertThat(publication.registrationId(), is(CORRELATION_ID));
    }

    // ---------------------------------
    // Subscription related interactions
    // ---------------------------------

    @Test
    public void addSubscriptionShouldNotifyMediaDriver() throws Exception
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) ->
            {
                correlatedMessage.correlationId(CORRELATION_ID);
                return CorrelatedMessageFlyweight.LENGTH;
            });

        conductor.addSubscription(CHANNEL, STREAM_ID_1);

        verify(driverProxy).addSubscription(CHANNEL, STREAM_ID_1);
    }

    @Test
    public void closingSubscriptionShouldNotifyMediaDriver()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) ->
            {
                correlatedMessage.correlationId(CORRELATION_ID);
                return CorrelatedMessageFlyweight.LENGTH;
            });

        final Subscription subscription = conductor.addSubscription(CHANNEL, STREAM_ID_1);

        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) ->
            {
                correlatedMessage.correlationId(CLOSE_CORRELATION_ID);
                return CorrelatedMessageFlyweight.LENGTH;
            });

        subscription.close();

        verify(driverProxy).removeSubscription(CORRELATION_ID);
    }

    @Test(expected = DriverTimeoutException.class, timeout = 5_000)
    public void addSubscriptionShouldTimeoutWithoutOperationSuccessful()
    {
        conductor.addSubscription(CHANNEL, STREAM_ID_1);
    }

    @Test(expected = RegistrationException.class)
    public void shouldFailToAddSubscriptionOnMediaDriverError()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_ERROR,
            errorMessageBuffer,
            (buffer) ->
            {
                errorResponse.errorCode(INVALID_CHANNEL);
                errorResponse.errorMessage("invalid channel");
                errorResponse.offendingCommandCorrelationId(CORRELATION_ID);
                return errorResponse.length();
            });

        conductor.addSubscription(CHANNEL, STREAM_ID_1);
    }

    @Test
    public void clientNotifiedOfNewImageShouldMapLogFile()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) ->
            {
                correlatedMessage.correlationId(CORRELATION_ID);
                return CorrelatedMessageFlyweight.LENGTH;
            });

        final Subscription subscription = conductor.addSubscription(CHANNEL, STREAM_ID_1);

        conductor.onAvailableImage(
            CORRELATION_ID,
            STREAM_ID_1,
            SESSION_ID_1,
            subscription.registrationId(),
            SUBSCRIBER_POSITION_ID,
            SESSION_ID_1 + "-log",
            SOURCE_INFO);

        verify(logBuffersFactory).map(eq(SESSION_ID_1 + "-log"));
    }

    @Test
    public void clientNotifiedOfNewAndInactiveImages()
    {
        whenReceiveBroadcastOnMessage(
            ControlProtocolEvents.ON_OPERATION_SUCCESS,
            correlatedMessageBuffer,
            (buffer) ->
            {
                correlatedMessage.correlationId(CORRELATION_ID);
                return CorrelatedMessageFlyweight.LENGTH;
            });

        final Subscription subscription = conductor.addSubscription(CHANNEL, STREAM_ID_1);

        conductor.onAvailableImage(
            CORRELATION_ID,
            STREAM_ID_1,
            SESSION_ID_1,
            subscription.registrationId(),
            SUBSCRIBER_POSITION_ID,
            SESSION_ID_1 + "-log",
            SOURCE_INFO);

        assertFalse(subscription.hasNoImages());
        assertTrue(subscription.isConnected());
        verify(mockAvailableImageHandler).onAvailableImage(any(Image.class));

        conductor.onUnavailableImage(CORRELATION_ID, STREAM_ID_1);

        verify(mockUnavailableImageHandler).onUnavailableImage(any(Image.class));
        assertTrue(subscription.hasNoImages());
        assertFalse(subscription.isConnected());
    }

    @Test
    public void shouldIgnoreUnknownNewImage()
    {
        conductor.onAvailableImage(
            CORRELATION_ID_2,
            STREAM_ID_2,
            SESSION_ID_2,
            SUBSCRIBER_POSITION_REGISTRATION_ID,
            SUBSCRIBER_POSITION_ID,
            SESSION_ID_2 + "-log",
            SOURCE_INFO);

        verify(logBuffersFactory, never()).map(anyString());
        verify(mockAvailableImageHandler, never()).onAvailableImage(any(Image.class));
    }

    @Test
    public void shouldIgnoreUnknownInactiveImage()
    {
        conductor.onUnavailableImage(CORRELATION_ID_2, STREAM_ID_2);

        verify(logBuffersFactory, never()).map(anyString());
        verify(mockUnavailableImageHandler, never()).onUnavailableImage(any(Image.class));
    }

    @Test
    public void shouldTimeoutInterServiceIfTooLongBetweenDoWorkCalls() throws Exception
    {
        suppressPrintError = true;

        conductor.doWork();

        timeNs += (TimeUnit.MILLISECONDS.toNanos(INTER_SERVICE_TIMEOUT_MS) + 1);

        conductor.doWork();

        verify(mockClientErrorHandler).onError(any(ConductorServiceTimeoutException.class));

        assertTrue(conductor.isClosed());
    }

    private void whenReceiveBroadcastOnMessage(
        final int msgTypeId, final MutableDirectBuffer buffer, final ToIntFunction<MutableDirectBuffer> filler)
    {
        doAnswer(
            (invocation) ->
            {
                final int length = filler.applyAsInt(buffer);
                conductor.driverListenerAdapter().onMessage(msgTypeId, buffer, 0, length);

                return 1;
            })
            .when(mockToClientReceiver).receive(any(MessageHandler.class));
    }

    private class PrintError implements ErrorHandler
    {
        public void onError(final Throwable throwable)
        {
            if (!suppressPrintError)
            {
                throwable.printStackTrace();
            }
        }
    }
}
