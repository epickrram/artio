/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.replication;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader.SessionReader;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.util.CustomMatchers.hasResult;

/**
 * Test technically breaches encapsulation of ClusterSubscription
 * deliberate tradeoff to avoid additional indirection and test complexity.
 */
public class ClusterSubscriptionTest
{
    private static final int CLUSTER_STREAM_ID = 1;
    private static final int LEADER = 1;
    private static final int OTHER_LEADER = 2;
    private static final int THIRD_LEADER = 3;

    private Subscription dataSubscription = mock(Subscription.class);
    private Subscription controlSubscription = mock(Subscription.class);
    private Header header = mock(Header.class);
    private Image leaderDataImage = mock(Image.class);
    private Image otherLeaderDataImage = mock(Image.class);
    private Image thirdLeaderDataImage = mock(Image.class);
    private ControlledFragmentHandler handler = mock(ControlledFragmentHandler.class);

    private ArchiveReader archiveReader = mock(ArchiveReader.class);
    private SessionReader otherLeaderArchiveReader = mock(SessionReader.class);

    private ClusterSubscription clusterSubscription = new ClusterSubscription(
        dataSubscription, CLUSTER_STREAM_ID, controlSubscription, archiveReader);

    @Before
    public void setUp()
    {
        leaderImageAvailable();
        otherLeaderImageAvailable();
        imageAvailable(thirdLeaderDataImage, THIRD_LEADER);

        when(handler.onFragment(any(), anyInt(), anyInt(), any())).thenReturn(CONTINUE);

        when(header.reservedValue()).thenReturn(ReservedValue.ofClusterStreamId(CLUSTER_STREAM_ID));

        archiveReaderAvailable();
    }

    @Test
    public void shouldUpdatePositionWhenAcknowledged()
    {
        onConsensusHeartbeatPoll(1, LEADER, 1, 0, 1);

        onConsensusHeartbeatPoll(1, LEADER, 2, 1, 2);

        assertState(1, LEADER, 2);
    }

    @Test
    public void shouldStashUpdatesWithGap()
    {
        onConsensusHeartbeatPoll(1, LEADER, 1, 0, 1);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, 4, 2, 4);

        assertState(1, LEADER, 1);
    }

    @Test
    public void shouldTransitionBetweenLeadersWithDifferentPositionDeltas()
    {
        final int firstTermLength = 128;
        final int firstTermPosition = firstTermLength;
        final int firstTermStreamPosition = firstTermLength;
        final int secondTermLength = 256;
        final int secondTermStreamPosition = secondTermLength;
        final int secondTermPosition = firstTermPosition + secondTermLength;

        onConsensusHeartbeatPoll(1, LEADER, firstTermPosition, 0, firstTermStreamPosition);
        pollsMessageFragment(leaderDataImage, firstTermStreamPosition, CONTINUE);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermPosition, 0, secondTermStreamPosition);
        pollsMessageFragment(otherLeaderDataImage, secondTermStreamPosition, CONTINUE);

        assertState(2, OTHER_LEADER, secondTermStreamPosition);
        verifyReceivesFragment(firstTermLength);
        verifyReceivesFragment(secondTermLength);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldTransitionBetweenLeadersWithDifferentPositionDeltasWhenDataLagsControl()
    {
        imageNotAvailable(LEADER);
        imageNotAvailable(OTHER_LEADER);

        final int firstTermLength = 128;
        final int firstTermPosition = firstTermLength;
        final int firstTermStreamPosition = firstTermLength;
        final int secondTermLength = 256;
        final int secondTermStreamPosition = secondTermLength;
        final int secondTermPosition = firstTermPosition + secondTermLength;

        onConsensusHeartbeatPoll(1, LEADER, firstTermPosition, 0, firstTermStreamPosition);
        leaderImageAvailable();
        onConsensusHeartbeatPoll(1, LEADER, firstTermPosition, 0, firstTermStreamPosition);

        pollsMessageFragment(leaderDataImage, firstTermStreamPosition, CONTINUE);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermPosition, 0, secondTermStreamPosition);
        otherLeaderImageAvailable();
        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermPosition, 0, secondTermStreamPosition);

        pollsMessageFragment(otherLeaderDataImage, secondTermStreamPosition, CONTINUE);

        assertState(2, OTHER_LEADER, secondTermStreamPosition);
        verifyReceivesFragment(firstTermLength);
        verifyReceivesFragment(secondTermLength);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldApplyUpdatesWhenGapFilled()
    {
        shouldStashUpdatesWithGap();

        onConsensusHeartbeatPoll(1, LEADER, 2, 1, 2);

        assertState(1, LEADER, 2);

        clusterSubscription.hasMatchingFutureAck();

        assertState(2, OTHER_LEADER, 4);
    }

    @Test
    public void shouldStashUpdatesFromFutureLeadershipTerm()
    {
        onConsensusHeartbeatPoll(1, LEADER, 1, 0, 1);

        onConsensusHeartbeatPoll(3, THIRD_LEADER, 4, 2, 4);

        assertState(1, LEADER, 1);
    }

    @Test
    public void shouldUpdatePositionFromFutureLeadershipTerm()
    {
        shouldStashUpdatesFromFutureLeadershipTerm();

        onConsensusHeartbeatPoll(2, OTHER_LEADER, 2, 1, 2);

        assertState(2, OTHER_LEADER, 2);

        clusterSubscription.hasMatchingFutureAck();

        assertState(3, THIRD_LEADER, 4);
    }

    @Test
    public void shouldCommitUpdatesFromFutureLeadershipTermWithDifferentPositionDeltas()
    {
        // NB: uses different lengths to identify which leader was being polled in the handler verify
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final int thirdTermEnd = secondTermEnd + thirdTermLen;

        willReceiveConsensusHeartbeat(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermEnd, CONTINUE);

        onConsensusHeartbeatPoll(3, THIRD_LEADER, thirdTermEnd, 0, thirdTermLen);

        willReceiveConsensusHeartbeat(2, OTHER_LEADER, secondTermEnd, 0, secondTermLen);
        pollsMessageFragment(otherLeaderDataImage, secondTermLen, CONTINUE);

        pollsMessageFragment(thirdLeaderDataImage, thirdTermLen, CONTINUE);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragment(secondTermLen);
        verifyReceivesFragment(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldIgnoreUnagreedDataFromFormerLeadersPublication()
    {
        final int firstTermLen = 128;
        final int unagreedDataLen = 64;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final int thirdTermEnd = secondTermEnd + thirdTermLen;
        final int unagreedDataEnd = firstTermLen + unagreedDataLen;
        final int thirdTermStreamStart = unagreedDataEnd;
        final int thirdTermStreamEnd = thirdTermStreamStart + thirdTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermEnd, 0, secondTermLen);
        pollsMessageFragment(otherLeaderDataImage, secondTermLen, CONTINUE);

        onConsensusHeartbeatPoll(3, LEADER, thirdTermEnd, thirdTermStreamStart, thirdTermStreamEnd);
        pollsMessageFragment(leaderDataImage, unagreedDataEnd, unagreedDataLen, CONTINUE);
        pollsMessageFragment(leaderDataImage, thirdTermStreamEnd, thirdTermLen, CONTINUE);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragment(secondTermLen);
        verifyReceivesFragment(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void replicateClusterReplicationTestBug()
    {
        final int leaderShipTermId = 1;
        final int newPosition = 1376;

        // Subscription Heartbeat(leaderShipTerm=1, startPos=0, pos=0, leaderSessId=432774274)
        onConsensusHeartbeatPoll(leaderShipTermId, LEADER, 0, 0, 0);
        // Subscription Heartbeat(leaderShipTerm=1, startPos=0, pos=1376, leaderSessId=432774274)
        onConsensusHeartbeatPoll(leaderShipTermId, LEADER, newPosition, 0, newPosition);

        // Subscription onFragment(headerPosition=1376, consensusPosition=1376
        pollsMessageFragment(leaderDataImage, newPosition, CONTINUE);

        verify(leaderDataImage).controlledPoll(any(), eq(1));
        verifyReceivesFragment(newPosition);
    }

    // Scenario for resend tests:
    // A leader has committed data to a quorum of nodes excluding you, then it dies.
    // Your only way of receiving that data is through resend on the control stream
    // You may have missed some concensus messages as well.

    @Test
    public void shouldCommitResendDataIfNextThingInStream()
    {
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermEnd, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);
        onResend(secondTermLen, secondTermEnd, thirdTermLen);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldNotReceiveResendDataTwiceResendFirst()
    {
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermEnd, 0, secondTermLen);
        pollsMessageFragment(otherLeaderDataImage, secondTermLen, CONTINUE);

        onResend(secondTermLen, secondTermEnd, thirdTermLen);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldNotReceiveResendDataTwiceHeartbeatFirst()
    {
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, secondTermEnd, 0, secondTermLen);
        pollsMessageFragment(otherLeaderDataImage, secondTermLen, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);

        onResend(secondTermLen, secondTermEnd, thirdTermLen);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldContinueToReceiveNormalDataAfterAResend()
    {
        // 2nd and third chunks really same term from OTHER_LEADER
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final int thirdTermEnd = secondTermEnd + thirdTermLen;
        final int thirdTermStreamEnd = secondTermLen + thirdTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);

        onConsensusHeartbeatPoll(2, OTHER_LEADER, thirdTermEnd, secondTermLen, thirdTermStreamEnd);
        pollsMessageFragment(otherLeaderDataImage, thirdTermStreamEnd, thirdTermLen, CONTINUE);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragment(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldCommitFromLocalLogIfGapInSubscription()
    {
        // You might receive resends out of order, using the raft leader-probing mechanism for resends.
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final long thirdTermStreamStart = secondTermLen;
        final long thirdTermStreamEnd = thirdTermStreamStart + thirdTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        // You got netsplit when the data was sent out on the main data channel
        when(otherLeaderDataImage.position()).thenReturn(thirdTermStreamEnd);

        // But the data has been resend and archived by the follower.
        onResend(secondTermLen, secondTermEnd, thirdTermLen);
        dataWasArchived(thirdTermStreamStart, thirdTermStreamEnd, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);

        poll();

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldCommitFromLocalLogWhenArchiverLags()
    {
        archiveReaderUnavailable();

        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final long thirdTermStreamStart = secondTermLen;
        final long thirdTermStreamEnd = thirdTermStreamStart + thirdTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        // You got netsplit when the data was sent out on the main data channel
        when(otherLeaderDataImage.position()).thenReturn(thirdTermStreamEnd);

        // But the data has been resend and archived by the follower.
        onResend(secondTermLen, secondTermEnd, thirdTermLen);
        dataWasArchived(thirdTermStreamStart, thirdTermStreamEnd, CONTINUE);

        onResend(0, firstTermEnd, secondTermLen);

        poll();

        archiveReaderAvailable();
        poll();

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldCommitResendDataAtStart()
    {
        // You might receive resends out of order, using the raft leader-probing mechanism for resends.
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;

        poll();
        when(otherLeaderDataImage.position()).thenReturn(0L);
        onResend(1, 0, 0, firstTermLen, CONTINUE);

        onConsensusHeartbeatPoll(2, LEADER, secondTermEnd, 0, secondTermLen);
        pollsMessageFragment(leaderDataImage, secondTermLen, CONTINUE);

        verifyReceivesFragmentWithAnyHeader(firstTermLen);
        verifyReceivesFragment(secondTermLen);
        verifyNoOtherFragmentsReceived();
    }

    // Back Pressure Tests

    @Test
    public void shouldPollDataWhenBackPressured()
    {
        final int firstTermLen = 128;
        final int firstTermStreamPosition = firstTermLen;
        final int firstTermPosition = firstTermLen;

        backPressureNextCommit();

        willReceiveConsensusHeartbeat(
            1, LEADER, firstTermPosition, 0, firstTermStreamPosition);
        pollsMessageFragment(leaderDataImage, firstTermLen, ABORT);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        verifyReceivesFragment(firstTermLen, times(2));
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldCommitResendDataIfNextThingInStreamWhenBackPressured()
    {
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermEnd, CONTINUE);

        backPressureNextCommit();
        onResend(2, (long)0, firstTermEnd, secondTermLen, ABORT);
        onResend(0, firstTermEnd, secondTermLen);
        onResend(secondTermLen, secondTermEnd, thirdTermLen);

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen, times(2));
        verifyReceivesFragmentWithAnyHeader(thirdTermLen);
        verifyNoOtherFragmentsReceived();
    }

    @Test
    public void shouldCommitFromLocalLogIfGapInSubscriptionWhenBackPressured()
    {
        // You might receive resends out of order, using the raft leader-probing mechanism for resends.
        final int firstTermLen = 128;
        final int secondTermLen = 256;
        final int thirdTermLen = 384;
        final int firstTermEnd = firstTermLen;
        final int secondTermEnd = firstTermEnd + secondTermLen;
        final long thirdTermStreamStart = secondTermLen;
        final long thirdTermStreamEnd = thirdTermStreamStart + thirdTermLen;

        onConsensusHeartbeatPoll(1, LEADER, firstTermEnd, 0, firstTermLen);
        pollsMessageFragment(leaderDataImage, firstTermLen, CONTINUE);

        // You got netsplit when the data was sent out on the main data channel
        when(otherLeaderDataImage.position()).thenReturn(thirdTermStreamEnd);

        // But the data has been resend and archived by the follower.
        onResend(secondTermLen, secondTermEnd, thirdTermLen);

        onResend(0, firstTermEnd, secondTermLen);

        backPressureNextCommit();

        dataWasArchived(thirdTermStreamStart, thirdTermStreamEnd, ABORT);
        poll();

        dataWasArchived(thirdTermStreamStart, thirdTermStreamEnd, CONTINUE);
        poll();

        verifyReceivesFragment(firstTermLen);
        verifyReceivesFragmentWithAnyHeader(secondTermLen);
        verifyReceivesFragmentWithAnyHeader(thirdTermLen, times(2));
        verifyNoOtherFragmentsReceived();
    }

    private void backPressureNextCommit()
    {
        when(handler.onFragment(any(), anyInt(), anyInt(), any())).thenReturn(ABORT, CONTINUE);
    }

    private void dataWasArchived(
        final long streamStart, final long streamEnd, final Action expectedAction)
    {
        when(otherLeaderArchiveReader.readUpTo(
            eq(streamStart + DataHeaderFlyweight.HEADER_LENGTH), eq(streamEnd), any()))
            .then(
                (inv) ->
                {
                    callHandler(
                        streamEnd,
                        (int)(streamEnd - streamStart),
                        expectedAction,
                        inv,
                        2);
                    return expectedAction == ABORT ? streamStart : streamEnd;
                });
    }

    private void onResend(final long streamStartPosition, final int startPosition, final int resendLen)
    {
        onResend(2, streamStartPosition, startPosition, resendLen, CONTINUE);
    }

    private void onResend(
        final int leaderShipTerm,
        final long streamStartPosition,
        final int startPosition,
        final int resendLen,
        final Action expectedAction)
    {
        final UnsafeBuffer resendBuffer = new UnsafeBuffer(new byte[resendLen]);
        clusterSubscription.hasMatchingFutureAck();
        final Action action = clusterSubscription.onResend(
            OTHER_LEADER, leaderShipTerm, startPosition, streamStartPosition, resendBuffer, 0, resendLen);
        assertEquals(expectedAction, action);
    }

    private void verifyReceivesFragment(final int newStreamPosition)
    {
        verifyReceivesFragment(newStreamPosition, times(1));
    }

    private void verifyReceivesFragment(final int newStreamPosition, final VerificationMode times)
    {
        verify(handler, times).onFragment(any(UnsafeBuffer.class), eq(0), eq(newStreamPosition), eq(header));
    }

    private void verifyReceivesFragmentWithAnyHeader(final int newStreamPosition)
    {
        verifyReceivesFragmentWithAnyHeader(newStreamPosition, times(1));
    }

    private void verifyReceivesFragmentWithAnyHeader(final int newStreamPosition, final VerificationMode times)
    {
        verify(handler, times)
            .onFragment(any(UnsafeBuffer.class), eq(0), eq(newStreamPosition), any(Header.class));
    }

    private void pollsMessageFragment(
        final Image dataImage,
        final int streamPosition,
        final Action expectedAction)
    {
        pollsMessageFragment(dataImage, streamPosition, streamPosition, expectedAction);
    }

    private void pollsMessageFragment(
        final Image dataImage,
        final int streamPosition,
        final int length,
        final Action expectedAction)
    {
        when(dataImage.controlledPoll(any(), anyInt())).thenAnswer(
            (inv) ->
            {
                callHandler(streamPosition, length, expectedAction, inv, 0);
                if (expectedAction != ABORT)
                {
                    when(dataImage.position()).thenReturn((long)streamPosition);
                }
                return 1;
            }).then(inv -> 0);

        poll();
    }

    private void callHandler(
        final long streamPosition,
        final int length,
        final Action expectedAction,
        final InvocationOnMock inv,
        final int handlerArgumentIndex)
    {
        final ControlledFragmentHandler handler = (ControlledFragmentHandler)inv.getArguments()[handlerArgumentIndex];

        when(header.position()).thenReturn(streamPosition);

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[length]);
        final Action action = handler.onFragment(buffer, 0, length, header);
        assertEquals(expectedAction, action);
    }

    private void poll()
    {
        clusterSubscription.controlledPoll(handler, 1);
    }

    private void onConsensusHeartbeatPoll(
        final int leaderShipTerm,
        final int leaderSessionId,
        final long position,
        final long streamStartPosition,
        final long streamPosition)
    {
        clusterSubscription.hasMatchingFutureAck();
        clusterSubscription.onConsensusHeartbeat(
            leaderShipTerm, leaderSessionId, position, streamStartPosition, streamPosition);
    }

    private void willReceiveConsensusHeartbeat(
        final int leaderShipTerm,
        final int leader,
        final long position,
        final long streamStartPosition,
        final long streamPosition)
    {
        when(controlSubscription.controlledPoll(any(), anyInt())).then(
            (inv) ->
            {
                clusterSubscription.onConsensusHeartbeat(
                    leaderShipTerm, leader, position, streamStartPosition, streamPosition);
                return 1;
            }).thenReturn(0);
    }

    private void assertState(
        final int currentLeadershipTermId,
        final Integer leadershipSessionId,
        final long streamPosition)
    {
        assertThat(clusterSubscription,
            hasResult(
                "currentLeadershipTerm",
                ClusterSubscription::currentLeadershipTerm,
                equalTo(currentLeadershipTermId)));

        verify(dataSubscription, atLeastOnce()).imageBySessionId(eq(leadershipSessionId));

        assertThat(clusterSubscription,
            hasResult(
                "streamPosition",
                ClusterSubscription::streamPosition,
                equalTo(streamPosition)));
    }

    private void verifyNoOtherFragmentsReceived()
    {
        verifyNoMoreInteractions(handler);
    }

    private void archiveReaderAvailable()
    {
        archiveReader(otherLeaderArchiveReader);
    }

    private void archiveReaderUnavailable()
    {
        archiveReader(null);
    }

    private void archiveReader(final SessionReader archiveReader)
    {
        when(this.archiveReader.session(OTHER_LEADER)).thenReturn(archiveReader);
    }

    private void otherLeaderImageAvailable()
    {
        imageAvailable(otherLeaderDataImage, OTHER_LEADER);
    }

    private void leaderImageAvailable()
    {
        imageAvailable(leaderDataImage, LEADER);
    }

    private void imageAvailable(final Image image, final int aeronSessionId)
    {
        when(dataSubscription.imageBySessionId(aeronSessionId)).thenReturn(image);
        if (image != null)
        {
            when(image.sessionId()).thenReturn(aeronSessionId);
        }
    }

    private void imageNotAvailable(final int aeronSessionId)
    {
        imageAvailable(null, aeronSessionId);
    }

}
