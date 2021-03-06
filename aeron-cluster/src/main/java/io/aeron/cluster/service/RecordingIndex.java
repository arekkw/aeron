/*
 * Copyright 2017 Real Logic Ltd.
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
package io.aeron.cluster.service;

import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.aeron.cluster.service.ClusteredServiceContainer.Configuration.RECORDING_INDEX_FILE_NAME;
import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * An index of recording that make up the history of a log. Recordings are in chronological order.
 * <p>
 * The index is made up of log terms with optional snapshots to roll up state across one or more terms.
 */
public class RecordingIndex implements AutoCloseable
{
    public static final int RECORDING_TYPE_LOG = 0;
    public static final int RECORDING_TYPE_SNAPSHOT = 1;

    private static final int RECORDING_ID_OFFSET = 0;
    private static final int LOG_POSITION_OFFSET = RECORDING_ID_OFFSET + SIZE_OF_LONG;
    private static final int MESSAGE_INDEX_OFFSET = LOG_POSITION_OFFSET + SIZE_OF_LONG;
    private static final int RECORD_TYPE_OFFSET = MESSAGE_INDEX_OFFSET + SIZE_OF_LONG;

    private static final int RECORD_LENGTH = BitUtil.align(RECORD_TYPE_OFFSET + SIZE_OF_LONG, SIZE_OF_LONG);

    @FunctionalInterface
    public interface RecordingConsumer
    {
        /**
         * Accept a recording record from the index.
         *
         * @param recordingType of the record.
         * @param recordingId   in the archive for the recording.
         * @param logPosition   reached for the aggregate log by the start of this recording.
         * @param messageIndex  reached for the aggregate log by the start of this recording.
         */
        void accept(int recordingType, long recordingId, long logPosition, long messageIndex);
    }

    private final File recordingIndexFile;
    private final File newRecordingIndexFile;
    private final ByteBuffer buffer = ByteBuffer.allocate(RECORD_LENGTH);
    private FileChannel fileChannel;

    public RecordingIndex(final File dir, final long serviceId)
    {
        recordingIndexFile = indexLocation(dir, serviceId, false);
        newRecordingIndexFile = indexLocation(dir, serviceId, true);

        try
        {
            fileChannel = FileChannel.open(recordingIndexFile.toPath(), CREATE, READ, WRITE);
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    /**
     * Count of entries in the index.
     *
     * @return count of entries in the index.
     */
    public long entryCount()
    {
        long count = 0;

        try
        {
            count = fileChannel.size() / RECORD_LENGTH;
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return count;
    }

    public int forEachFromLastSnapshot(final RecordingConsumer consumer)
    {
        MappedByteBuffer mappedByteBuffer = null;
        int count = 0;

        try
        {
            final long length = fileChannel.size();

            mappedByteBuffer = fileChannel.map(READ_ONLY, 0, length);
            final UnsafeBuffer buffer = new UnsafeBuffer(mappedByteBuffer);

            final int snapshotOffset = findOffsetOfLatestSnapshot(buffer);

            for (int i = snapshotOffset; i < (int)length; i += RECORD_LENGTH)
            {
                consumer.accept(
                    buffer.getInt(i + RECORD_TYPE_OFFSET),
                    buffer.getLong(i + RECORDING_ID_OFFSET),
                    buffer.getLong(i + LOG_POSITION_OFFSET),
                    buffer.getLong(i + MESSAGE_INDEX_OFFSET));

                count++;
            }
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
        finally
        {
            IoUtil.unmap(mappedByteBuffer);
        }

        return count;
    }

    /**
     * Append an index entry for a raft term.
     *
     * @param recordingId  in the archive for the term.
     * @param logPosition  reached at the beginning of the term.
     * @param messageIndex reached at the beginning of the term.
     */
    public void appendLog(final long recordingId, final long logPosition, final long messageIndex)
    {
        append(RECORDING_TYPE_LOG, recordingId, logPosition, messageIndex);
    }

    /**
     * Append an index entry for a snapshot.
     *
     * @param recordingId  in the archive for the snapshot.
     * @param logPosition  reached for the snapshot.
     * @param messageIndex reached for the snapshot.
     */
    public void appendSnapshot(final long recordingId, final long logPosition, final long messageIndex)
    {
        append(RECORDING_TYPE_SNAPSHOT, recordingId, logPosition, messageIndex);
    }

    public static File indexLocation(final File dir, final long serviceId, final boolean isTmp)
    {
        final String suffix = isTmp ? ".tmp" : "";

        return new File(dir, Long.toString(serviceId) + "-" + RECORDING_INDEX_FILE_NAME + suffix);
    }

    public void close()
    {
        CloseHelper.quietClose(fileChannel);
    }

    private void append(
        final int recordingType, final long recordingId, final long logPosition, final long messageIndex)
    {
        try
        {
            final Path indexFilePath = recordingIndexFile.toPath();
            final Path newIndexFilePath = newRecordingIndexFile.toPath();

            fileChannel.close();

            Files.copy(indexFilePath, newIndexFilePath);

            final FileChannel newFileChannel = FileChannel.open(newIndexFilePath, WRITE, APPEND);

            buffer.putLong(RECORDING_ID_OFFSET, recordingId);
            buffer.putLong(LOG_POSITION_OFFSET, logPosition);
            buffer.putLong(MESSAGE_INDEX_OFFSET, messageIndex);
            buffer.putInt(RECORD_TYPE_OFFSET, recordingType);

            buffer.clear();
            newFileChannel.write(buffer);
            newFileChannel.force(true);
            newFileChannel.close();

            Files.move(newIndexFilePath, indexFilePath, REPLACE_EXISTING);

            fileChannel = FileChannel.open(indexFilePath, READ);
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    private static int findOffsetOfLatestSnapshot(final UnsafeBuffer unsafeBuffer)
    {
        for (int i = unsafeBuffer.capacity() - RECORD_LENGTH; i >= 0; i -= RECORD_LENGTH)
        {
            if (unsafeBuffer.getLong(i + RECORD_TYPE_OFFSET) == RECORDING_TYPE_SNAPSHOT)
            {
                return i;
            }
        }

        return 0;
    }
}
