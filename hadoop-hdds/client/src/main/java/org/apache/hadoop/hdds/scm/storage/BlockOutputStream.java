/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hdds.scm.storage;
import com.google.common.base.Preconditions;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.scm.XceiverClientReply;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.common.Checksum;
import org.apache.hadoop.ozone.common.ChecksumData;
import org.apache.hadoop.ozone.common.OzoneChecksumException;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumType;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChunkInfo;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.BlockData;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.KeyValue;
import org.apache.hadoop.hdds.client.BlockID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.apache.hadoop.hdds.scm.storage.ContainerProtocolCalls
    .putBlockAsync;
import static org.apache.hadoop.hdds.scm.storage.ContainerProtocolCalls
    .writeChunkAsync;

/**
 * An {@link OutputStream} used by the REST service in combination with the
 * SCMClient to write the value of a key to a sequence
 * of container chunks.  Writes are buffered locally and periodically written to
 * the container as a new chunk.  In order to preserve the semantics that
 * replacement of a pre-existing key is atomic, each instance of the stream has
 * an internal unique identifier.  This unique identifier and a monotonically
 * increasing chunk index form a composite key that is used as the chunk name.
 * After all data is written, a putKey call creates or updates the corresponding
 * container key, and this call includes the full list of chunks that make up
 * the key data.  The list of chunks is updated all at once.  Therefore, a
 * concurrent reader never can see an intermediate state in which different
 * chunks of data from different versions of the key data are interleaved.
 * This class encapsulates all state management for buffering and writing
 * through to the container.
 */
public class BlockOutputStream extends OutputStream {
  public static final Logger LOG =
      LoggerFactory.getLogger(BlockOutputStream.class);

  private BlockID blockID;
  private final String key;
  private final String traceID;
  private final BlockData.Builder containerBlockData;
  private XceiverClientManager xceiverClientManager;
  private XceiverClientSpi xceiverClient;
  private final ContainerProtos.ChecksumType checksumType;
  private final int bytesPerChecksum;
  private final String streamId;
  private int chunkIndex;
  private int chunkSize;
  private final long streamBufferFlushSize;
  private final long streamBufferMaxSize;
  private final long watchTimeout;
  private BufferPool bufferPool;
  // The IOException will be set by response handling thread in case there is an
  // exception received in the response. If the exception is set, the next
  // request will fail upfront.
  private IOException ioException;
  private ExecutorService responseExecutor;

  // the effective length of data flushed so far
  private long totalDataFlushedLength;

  // effective data write attempted so far for the block
  private long writtenDataLength;

  // total data which has been successfully flushed and acknowledged
  // by all servers
  private long totalAckDataLength;

  // future Map to hold up all putBlock futures
  private ConcurrentHashMap<Long,
      CompletableFuture<ContainerProtos.ContainerCommandResponseProto>>
      futureMap;
  // map containing mapping for putBlock logIndex to to flushedDataLength Map.

  // The map should maintain the keys (logIndexes) in order so that while
  // removing we always end up updating incremented data flushed length.
  private ConcurrentSkipListMap<Long, Long> commitIndex2flushedDataMap;

  private List<DatanodeDetails> failedServers;

  /**
   * Creates a new BlockOutputStream.
   *
   * @param blockID              block ID
   * @param key                  chunk key
   * @param xceiverClientManager client manager that controls client
   * @param pipeline             pipeline where block will be written
   * @param traceID              container protocol call args
   * @param chunkSize            chunk size
   * @param bufferPool           pool of buffers
   * @param streamBufferFlushSize flush size
   * @param streamBufferMaxSize   max size of the currentBuffer
   * @param watchTimeout          watch timeout
   * @param checksumType          checksum type
   * @param bytesPerChecksum      Bytes per checksum
   */
  @SuppressWarnings("parameternumber")
  public BlockOutputStream(BlockID blockID, String key,
      XceiverClientManager xceiverClientManager, Pipeline pipeline,
      String traceID, int chunkSize, long streamBufferFlushSize,
      long streamBufferMaxSize, long watchTimeout, BufferPool bufferPool,
      ChecksumType checksumType, int bytesPerChecksum)
      throws IOException {
    this.blockID = blockID;
    this.key = key;
    this.traceID = traceID;
    this.chunkSize = chunkSize;
    KeyValue keyValue =
        KeyValue.newBuilder().setKey("TYPE").setValue("KEY").build();
    this.containerBlockData =
        BlockData.newBuilder().setBlockID(blockID.getDatanodeBlockIDProtobuf())
            .addMetadata(keyValue);
    this.xceiverClientManager = xceiverClientManager;
    this.xceiverClient = xceiverClientManager.acquireClient(pipeline);
    this.streamId = UUID.randomUUID().toString();
    this.chunkIndex = 0;
    this.streamBufferFlushSize = streamBufferFlushSize;
    this.streamBufferMaxSize = streamBufferMaxSize;
    this.watchTimeout = watchTimeout;
    this.bufferPool = bufferPool;
    this.checksumType = checksumType;
    this.bytesPerChecksum = bytesPerChecksum;

    // A single thread executor handle the responses of async requests
    responseExecutor = Executors.newSingleThreadExecutor();
    commitIndex2flushedDataMap = new ConcurrentSkipListMap<>();
    totalAckDataLength = 0;
    futureMap = new ConcurrentHashMap<>();
    totalDataFlushedLength = 0;
    writtenDataLength = 0;
    failedServers = Collections.emptyList();
  }

  public BlockID getBlockID() {
    return blockID;
  }

  public long getTotalSuccessfulFlushedData() {
    return totalAckDataLength;
  }

  public long getWrittenDataLength() {
    return writtenDataLength;
  }

  public List<DatanodeDetails> getFailedServers() {
    return failedServers;
  }

  @Override
  public void write(int b) throws IOException {
    checkOpen();
    byte[] buf = new byte[1];
    buf[0] = (byte) b;
    write(buf, 0, 1);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    checkOpen();
    if (b == null) {
      throw new NullPointerException();
    }
    if ((off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length)
        || ((off + len) < 0)) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return;
    }
    while (len > 0) {
      int writeLen;

      // Allocate a buffer if needed. The buffer will be allocated only
      // once as needed and will be reused again for multiple blockOutputStream
      // entries.
      ByteBuffer  currentBuffer = bufferPool.allocateBufferIfNeeded();
      int pos = currentBuffer.position();
      writeLen =
          Math.min(chunkSize - pos % chunkSize, len);
      currentBuffer.put(b, off, writeLen);
      if (!currentBuffer.hasRemaining()) {
        writeChunk(currentBuffer);
      }
      off += writeLen;
      len -= writeLen;
      writtenDataLength += writeLen;
      if (shouldFlush()) {
        totalDataFlushedLength += streamBufferFlushSize;
        handlePartialFlush();
      }
      // Data in the bufferPool can not exceed streamBufferMaxSize
      if (isBufferPoolFull()) {
        handleFullBuffer();
      }
    }
  }

  private boolean shouldFlush() {
    return writtenDataLength % streamBufferFlushSize == 0;
  }

  private boolean isBufferPoolFull() {
    return bufferPool.computeBufferData() == streamBufferMaxSize;
  }
  /**
   * Will be called on the retryPath in case closedContainerException/
   * TimeoutException.
   * @param len length of data to write
   * @throws IOException if error occurred
   */

  // In this case, the data is already cached in the currentBuffer.
  public void writeOnRetry(long len) throws IOException {
    if (len == 0) {
      return;
    }
    int count = 0;
    Preconditions.checkArgument(len <= streamBufferMaxSize);
    while (len > 0) {
      long writeLen;
      writeLen = Math.min(chunkSize, len);
      if (writeLen == chunkSize) {
        writeChunk(bufferPool.getBuffer(count));
      }
      len -= writeLen;
      count++;
      writtenDataLength += writeLen;
      if (shouldFlush()) {
        // reset the position to zero as now we will be reading the
        // next buffer in the list
        totalDataFlushedLength += streamBufferFlushSize;
        handlePartialFlush();
      }

      // we should not call isBufferFull here. The buffer might already be full
      // as whole data is already cached in the buffer. We should just validate
      // if we wrote data of size streamBufferMaxSize to call for handling
      // full buffer condition.
      if (writtenDataLength == streamBufferMaxSize) {
        handleFullBuffer();
      }
    }
  }

  /**
   * just update the totalAckDataLength. In case of failure,
   * we will read the data starting from totalAckDataLength.
   */
  private void updateFlushIndex(List<Long> indexes) {
    Preconditions.checkArgument(!commitIndex2flushedDataMap.isEmpty());
    for (long index : indexes) {
      Preconditions.checkState(commitIndex2flushedDataMap.containsKey(index));
      long length = commitIndex2flushedDataMap.remove(index);

      // totalAckDataLength replicated yet should always be less than equal to
      // the current length being returned from commitIndex2flushedDataMap.
      // The below precondition would ensure commitIndex2flushedDataMap entries
      // are removed in order of the insertion to the map.
      Preconditions.checkArgument(totalAckDataLength < length);
      totalAckDataLength = length;
      LOG.debug("Total data successfully replicated: " + totalAckDataLength);
      futureMap.remove(totalAckDataLength);
      // Flush has been committed to required servers successful.
      // just release the current buffer from the buffer pool.

      // every entry removed from the putBlock future Map signifies
      // streamBufferFlushSize/chunkSize no of chunks successfully committed.
      // Release the buffers from the buffer pool to be reused again.
      int chunkCount = (int) (streamBufferFlushSize / chunkSize);
      for (int i = 0; i < chunkCount; i++) {
        bufferPool.releaseBuffer();
      }
    }
  }

  /**
   * This is a blocking call. It will wait for the flush till the commit index
   * at the head of the commitIndex2flushedDataMap gets replicated to all or
   * majority.
   * @throws IOException
   */
  private void handleFullBuffer() throws IOException {
    try {
      checkOpen();
      if (!futureMap.isEmpty()) {
        waitOnFlushFutures();
      }
    } catch (InterruptedException | ExecutionException e) {
      adjustBuffersOnException();
      throw new IOException(
          "Unexpected Storage Container Exception: " + e.toString(), e);
    }
    if (!commitIndex2flushedDataMap.isEmpty()) {
      watchForCommit(
          commitIndex2flushedDataMap.keySet().stream().mapToLong(v -> v)
              .min().getAsLong());
    }
  }

  private void adjustBuffers(long commitIndex) {
    List<Long> keyList = commitIndex2flushedDataMap.keySet().stream()
        .filter(p -> p <= commitIndex).collect(Collectors.toList());
    if (keyList.isEmpty()) {
      return;
    } else {
      updateFlushIndex(keyList);
    }
  }

  // It may happen that once the exception is encountered , we still might
  // have successfully flushed up to a certain index. Make sure the buffers
  // only contain data which have not been sufficiently replicated
  private void adjustBuffersOnException() {
    adjustBuffers(xceiverClient.getReplicatedMinCommitIndex());
  }

  /**
   * calls watchForCommit API of the Ratis Client. For Standalone client,
   * it is a no op.
   * @param commitIndex log index to watch for
   * @return minimum commit index replicated to all nodes
   * @throws IOException IOException in case watch gets timed out
   */
  private void watchForCommit(long commitIndex) throws IOException {
    checkOpen();
    Preconditions.checkState(!commitIndex2flushedDataMap.isEmpty());
    long index;
    try {
      XceiverClientReply reply =
          xceiverClient.watchForCommit(commitIndex, watchTimeout);
      if (reply == null) {
        index = 0;
      } else {
        List<DatanodeDetails> dnList = reply.getDatanodes();
        if (!dnList.isEmpty()) {
          if (failedServers.isEmpty()) {
            failedServers = new ArrayList<>();
          }
          failedServers.addAll(dnList);
        }
        index = reply.getLogIndex();
      }
      adjustBuffers(index);
    } catch (TimeoutException | InterruptedException | ExecutionException e) {
      LOG.warn("watchForCommit failed for index " + commitIndex, e);
      adjustBuffersOnException();
      throw new IOException(
          "Unexpected Storage Container Exception: " + e.toString(), e);
    }
  }

  private CompletableFuture<ContainerProtos.
      ContainerCommandResponseProto> handlePartialFlush()
      throws IOException {
    checkOpen();
    long flushPos = totalDataFlushedLength;
    String requestId =
        traceID + ContainerProtos.Type.PutBlock + chunkIndex + blockID;
    CompletableFuture<ContainerProtos.
        ContainerCommandResponseProto> flushFuture;
    try {
      XceiverClientReply asyncReply =
          putBlockAsync(xceiverClient, containerBlockData.build(), requestId);
      CompletableFuture<ContainerProtos.ContainerCommandResponseProto> future =
          asyncReply.getResponse();
      flushFuture = future.thenApplyAsync(e -> {
        try {
          validateResponse(e);
        } catch (IOException sce) {
          throw new CompletionException(sce);
        }
        // if the ioException is not set, putBlock is successful
        if (ioException == null) {
          LOG.debug(
              "Adding index " + asyncReply.getLogIndex() + " commitMap size "
                  + commitIndex2flushedDataMap.size());
          BlockID responseBlockID = BlockID.getFromProtobuf(
              e.getPutBlock().getCommittedBlockLength().getBlockID());
          Preconditions.checkState(blockID.getContainerBlockID()
              .equals(responseBlockID.getContainerBlockID()));
          // updates the bcsId of the block
          blockID = responseBlockID;
          // for standalone protocol, logIndex will always be 0.
          commitIndex2flushedDataMap.put(asyncReply.getLogIndex(), flushPos);
        }
        return e;
      }, responseExecutor).exceptionally(e -> {
        LOG.debug(
            "putBlock failed for blockID " + blockID + " with exception " + e
                .getLocalizedMessage());
        CompletionException ce =  new CompletionException(e);
        setIoException(ce);
        throw ce;
      });
    } catch (IOException | InterruptedException | ExecutionException e) {
      throw new IOException(
          "Unexpected Storage Container Exception: " + e.toString(), e);
    }
    futureMap.put(flushPos, flushFuture);
    return flushFuture;
  }

  @Override
  public void flush() throws IOException {
    if (xceiverClientManager != null && xceiverClient != null
        && bufferPool != null && bufferPool.getSize() > 0) {
      try {
        handleFlush();
      } catch (InterruptedException | ExecutionException e) {
        adjustBuffersOnException();
        throw new IOException(
            "Unexpected Storage Container Exception: " + e.toString(), e);
      }
    }
  }


  private void writeChunk(ByteBuffer buffer)
      throws IOException {
    // Please note : We are not flipping the slice when we write since
    // the slices are pointing the currentBuffer start and end as needed for
    // the chunk write. Also please note, Duplicate does not create a
    // copy of data, it only creates metadata that points to the data
    // stream.
    ByteBuffer chunk = buffer.duplicate();
    chunk.position(0);
    chunk.limit(buffer.position());
    writeChunkToContainer(chunk);
  }

  private void handleFlush()
      throws IOException, InterruptedException, ExecutionException {
    checkOpen();
    // flush the last chunk data residing on the currentBuffer
    if (totalDataFlushedLength < writtenDataLength) {
      ByteBuffer currentBuffer = bufferPool.getBuffer();
      int pos = currentBuffer.position();
      writeChunk(currentBuffer);
      totalDataFlushedLength += pos;
      handlePartialFlush();
    }
    waitOnFlushFutures();
    // just check again if the exception is hit while waiting for the
    // futures to ensure flush has indeed succeeded

    // irrespective of whether the commitIndex2flushedDataMap is empty
    // or not, ensure there is no exception set
    checkOpen();

  }

  @Override
  public void close() throws IOException {
    if (xceiverClientManager != null && xceiverClient != null
        && bufferPool != null && bufferPool.getSize() > 0) {
      try {
        handleFlush();
        if (!commitIndex2flushedDataMap.isEmpty()) {
          // wait for the last commit index in the commitIndex2flushedDataMap
          // to get committed to all or majority of nodes in case timeout
          // happens.
          long lastIndex =
              commitIndex2flushedDataMap.keySet().stream().mapToLong(v -> v)
                  .max().getAsLong();
          LOG.debug(
              "waiting for last flush Index " + lastIndex + " to catch up");
          watchForCommit(lastIndex);
        }
      } catch (InterruptedException | ExecutionException e) {
        adjustBuffersOnException();
        throw new IOException(
            "Unexpected Storage Container Exception: " + e.toString(), e);
      } finally {
        cleanup(false);
      }
      // TODO: Turn the below buffer empty check on whne Standalone pipeline
      // is removed in the write path in tests
      // Preconditions.checkArgument(buffer.position() == 0);
      // bufferPool.checkBufferPoolEmpty();

    }
  }


  private void waitOnFlushFutures()
      throws InterruptedException, ExecutionException {
    CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
        futureMap.values().toArray(new CompletableFuture[futureMap.size()]));
    // wait for all the transactions to complete
    combinedFuture.get();
  }

  private void validateResponse(
      ContainerProtos.ContainerCommandResponseProto responseProto)
      throws IOException {
    try {
      // if the ioException is already set, it means a prev request has failed
      // just throw the exception. The current operation will fail with the
      // original error
      if (ioException != null) {
        throw ioException;
      }
      ContainerProtocolCalls.validateContainerResponse(responseProto);
    } catch (StorageContainerException sce) {
      LOG.error("Unexpected Storage Container Exception: ", sce);
      setIoException(sce);
      throw sce;
    }
  }

  private void setIoException(Exception e) {
    if (ioException != null) {
      ioException =  new IOException(
          "Unexpected Storage Container Exception: " + e.toString(), e);
    }
  }

  public void cleanup(boolean invalidateClient) {
    if (xceiverClientManager != null) {
      xceiverClientManager.releaseClient(xceiverClient, invalidateClient);
    }
    xceiverClientManager = null;
    xceiverClient = null;
    if (futureMap != null) {
      futureMap.clear();
    }
    futureMap = null;
    if (commitIndex2flushedDataMap != null) {
      commitIndex2flushedDataMap.clear();
    }
    commitIndex2flushedDataMap = null;
    responseExecutor.shutdown();
  }

  /**
   * Checks if the stream is open or exception has occured.
   * If not, throws an exception.
   *
   * @throws IOException if stream is closed
   */
  private void checkOpen() throws IOException {
    if (xceiverClient == null) {
      throw new IOException("BlockOutputStream has been closed.");
    } else if (ioException != null) {
      adjustBuffersOnException();
      throw ioException;
    }
  }

  /**
   * Writes buffered data as a new chunk to the container and saves chunk
   * information to be used later in putKey call.
   *
   * @throws IOException if there is an I/O error while performing the call
   * @throws OzoneChecksumException if there is an error while computing
   * checksum
   */
  private void writeChunkToContainer(ByteBuffer chunk) throws IOException {
    int effectiveChunkSize = chunk.remaining();
    ByteString data = ByteString.copyFrom(chunk);
    Checksum checksum = new Checksum(checksumType, bytesPerChecksum);
    ChecksumData checksumData = checksum.computeChecksum(data);
    ChunkInfo chunkInfo = ChunkInfo.newBuilder()
        .setChunkName(DigestUtils.md5Hex(key) + "_stream_" + streamId +
            "_chunk_" + ++chunkIndex)
        .setOffset(0)
        .setLen(effectiveChunkSize)
        .setChecksumData(checksumData.getProtoBufMessage())
        .build();
    // generate a unique requestId
    String requestId =
        traceID + ContainerProtos.Type.WriteChunk + chunkIndex + chunkInfo
            .getChunkName();
    try {
      XceiverClientReply asyncReply =
          writeChunkAsync(xceiverClient, chunkInfo, blockID, data, requestId);
      CompletableFuture<ContainerProtos.ContainerCommandResponseProto> future =
          asyncReply.getResponse();
      future.thenApplyAsync(e -> {
        try {
          validateResponse(e);
        } catch (IOException sce) {
          future.completeExceptionally(sce);
        }
        return e;
      }, responseExecutor).exceptionally(e -> {
        LOG.debug(
            "writing chunk failed " + chunkInfo.getChunkName() + " blockID "
                + blockID + " with exception " + e.getLocalizedMessage());
        CompletionException ce = new CompletionException(e);
        setIoException(ce);
        throw ce;
      });
    } catch (IOException | InterruptedException | ExecutionException e) {
      throw new IOException(
          "Unexpected Storage Container Exception: " + e.toString(), e);
    }
    LOG.debug(
        "writing chunk " + chunkInfo.getChunkName() + " blockID " + blockID
            + " length " + effectiveChunkSize);
    containerBlockData.addChunks(chunkInfo);
  }
}
