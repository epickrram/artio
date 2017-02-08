/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.engine;

import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongLongConsumer;
import uk.co.real_logic.fix_gateway.engine.logger.Index;
import uk.co.real_logic.fix_gateway.engine.logger.IndexedPositionConsumer;
import uk.co.real_logic.fix_gateway.messages.FixMessageDecoder;
import uk.co.real_logic.fix_gateway.messages.MessageHeaderDecoder;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;

class SoloPositionSender implements Index
{
    private static final int MISSING_LIBRARY = -1;

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final FixMessageDecoder fixMessage = new FixMessageDecoder();
    private final Long2LongHashMap libraryIdToPosition = new Long2LongHashMap(MISSING_LIBRARY);
    private final LongLongConsumer resendPositionFunc = this::resendPosition;

    private final GatewayPublication publication;

    private int resendCount;

    SoloPositionSender(final GatewayPublication publication)
    {
        this.publication = publication;
    }

    @SuppressWarnings("FinalParameters")
    public void indexRecord(
        final DirectBuffer buffer,
        int offset,
        final int length,
        final int streamId,
        final int aeronSessionId,
        final long endPosition)
    {
        messageHeader.wrap(buffer, offset);

        if (messageHeader.templateId() == FixMessageDecoder.TEMPLATE_ID)
        {
            offset += MessageHeaderDecoder.ENCODED_LENGTH;

            fixMessage.wrap(buffer, offset, messageHeader.blockLength(), messageHeader.version());

            final int libraryId = fixMessage.libraryId();
            if (saveNewSentPosition(libraryId, endPosition))
            {
                libraryIdToPosition.remove(libraryId);
            }
            else
            {
                libraryIdToPosition.put(libraryId, endPosition);
            }
        }
    }

    public int doWork()
    {
        resendCount = 0;
        libraryIdToPosition.longForEach(resendPositionFunc);
        return resendCount;
    }

    private void resendPosition(final long libraryId, final long endPosition)
    {
        if (saveNewSentPosition((int) libraryId, endPosition))
        {
            libraryIdToPosition.remove(libraryId);
            resendCount++;
        }
    }

    private boolean saveNewSentPosition(final int libraryId, final long endPosition)
    {
        return publication.saveNewSentPosition(libraryId, endPosition) >= 0;
    }

    public void close()
    {
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
    }
}
