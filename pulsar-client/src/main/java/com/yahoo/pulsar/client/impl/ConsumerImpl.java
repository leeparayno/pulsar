/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

import java.io.IOException;
import java.util.BitSet;
import java.util.NavigableMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.pulsar.client.api.Consumer;
import com.yahoo.pulsar.client.api.ConsumerConfiguration;
import com.yahoo.pulsar.client.api.Message;
import com.yahoo.pulsar.client.api.MessageId;
import com.yahoo.pulsar.client.api.PulsarClientException;
import com.yahoo.pulsar.client.util.FutureUtil;
import com.yahoo.pulsar.common.api.Commands;
import com.yahoo.pulsar.common.api.PulsarDecoder;
import com.yahoo.pulsar.common.api.proto.PulsarApi;
import com.yahoo.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import com.yahoo.pulsar.common.api.proto.PulsarApi.CommandAck.ValidationError;
import com.yahoo.pulsar.common.api.proto.PulsarApi.CompressionType;
import com.yahoo.pulsar.common.api.proto.PulsarApi.MessageIdData;
import com.yahoo.pulsar.common.api.proto.PulsarApi.MessageMetadata;
import com.yahoo.pulsar.common.api.proto.PulsarApi.ProtocolVersion;
import com.yahoo.pulsar.common.compression.CompressionCodec;
import com.yahoo.pulsar.common.compression.CompressionCodecProvider;
import com.yahoo.pulsar.common.util.XXHashChecksum;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.Timeout;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class ConsumerImpl extends ConsumerBase {

    private final long consumerId;

    // Number of messages that have delivered to the application. Every once in a while, this number will be sent to the
    // broker to notify that we are ready to get (and store in the incoming messages queue) more messages
    private final AtomicInteger availablePermits;

    private long subscribeTimeout;
    private final int partitionIndex;

    private final int receiverQueueRefillThreshold;
    private final CompressionCodecProvider codecProvider;

    private volatile boolean waitingOnReceiveForZeroQueueSize = false;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final ConcurrentSkipListMap<MessageIdImpl, BitSet> batchMessageAckTracker;

    private final ConsumerStats stats;

    ConsumerImpl(PulsarClientImpl client, String topic, String subscription, ConsumerConfiguration conf,
            ExecutorService listenerExecutor, CompletableFuture<Consumer> subscribeFuture) {
        this(client, topic, subscription, conf, listenerExecutor, -1, subscribeFuture);
    }

    ConsumerImpl(PulsarClientImpl client, String topic, String subscription, ConsumerConfiguration conf,
            ExecutorService listenerExecutor, int partitionIndex, CompletableFuture<Consumer> subscribeFuture) {
        super(client, topic, subscription, conf, listenerExecutor, subscribeFuture, true /* use growable queue */);
        this.consumerId = client.newConsumerId();
        this.availablePermits = new AtomicInteger(0);
        this.subscribeTimeout = System.currentTimeMillis() + client.getConfiguration().getOperationTimeoutMs();
        this.partitionIndex = partitionIndex;
        this.receiverQueueRefillThreshold = conf.getReceiverQueueSize() / 2;
        this.codecProvider = new CompressionCodecProvider();
        batchMessageAckTracker = new ConcurrentSkipListMap<>();
        if (client.getConfiguration().getStatsIntervalSeconds() > 0) {
            stats = new ConsumerStats(client, conf, this);
        } else {
            stats = ConsumerStats.CONSUMER_STATS_DISABLED;
        }
        grabCnx();
    }

    @Override
    public CompletableFuture<Void> unsubscribeAsync() {
        if (state.get() == State.Closing || state.get() == State.Closed) {
            return FutureUtil
                    .failedFuture(new PulsarClientException.AlreadyClosedException("Consumer was already closed"));
        }
        final CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        if (isConnected()) {
            state.set(State.Closing);
            long requestId = client.newRequestId();
            ByteBuf unsubscribe = Commands.newUnsubscribe(consumerId, requestId);
            ClientCnx cnx = cnx();
            cnx.sendRequestWithId(unsubscribe, requestId).thenRun(() -> {
                cnx.removeConsumer(consumerId);
                log.info("[{}][{}] Successfully unsubscribed from topic", topic, subscription);
                if (unAckedMessageTracker != null) {
                    unAckedMessageTracker.close();
                }
                unsubscribeFuture.complete(null);
                state.set(State.Closed);
            }).exceptionally(e -> {
                log.error("[{}][{}] Failed to unsubscribe: {}", topic, subscription, e.getCause().getMessage());
                unsubscribeFuture.completeExceptionally(e.getCause());
                state.set(State.Ready);
                return null;
            });
        } else {
            unsubscribeFuture.completeExceptionally(new PulsarClientException("Not connected to broker"));
        }
        return unsubscribeFuture;
    }

    @Override
    protected Message internalReceive() throws PulsarClientException {
        if (conf.getReceiverQueueSize() == 0) {
            return fetchSingleMessageFromBroker();
        }
        Message message;
        try {
            message = incomingMessages.take();
            messageProcessed(message);
            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.incrementNumReceiveFailed();
            throw new PulsarClientException(e);
        }
    }

    @Override
    protected CompletableFuture<Message> internalReceiveAsync() {

        CompletableFuture<Message> result = new CompletableFuture<Message>();
        Message message = null;
        try {
            lock.writeLock().lock();
            message = incomingMessages.poll(0, TimeUnit.MILLISECONDS);
            if (message == null) {
                pendingReceives.add(result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(e);
        } finally {
            lock.writeLock().unlock();
        }

        if (message == null && conf.getReceiverQueueSize() == 0) {
            receiveMessages(cnx(), 1);
        } else if (message != null) {
            messageProcessed(message);
            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            }
            result.complete(message);
        }

        return result;
    }

    private synchronized Message fetchSingleMessageFromBroker() throws PulsarClientException {
        checkArgument(conf.getReceiverQueueSize() == 0);

        // Just being cautious
        if (incomingMessages.size() > 0) {
            log.error("The incoming message queue should never be greater than 0 when Queue size is 0");
            incomingMessages.clear();
        }

        Message message;
        try {
            // is cnx is null or if the connection breaks the connectionOpened function will send the flow again
            waitingOnReceiveForZeroQueueSize = true;
            if (isConnected()) {
                receiveMessages(cnx(), 1);
            }
            do {
                message = incomingMessages.take();
                ClientCnx msgCnx = ((MessageImpl) message).getCnx();
                // synchronized need to prevent race between connectionOpened and the check "msgCnx == cnx()"
                synchronized (ConsumerImpl.this) {
                    // if message received due to an old flow - discard it and wait for the message from the
                    // latest flow command
                    if (msgCnx == cnx()) {
                        waitingOnReceiveForZeroQueueSize = false;
                        break;
                    }
                }
            } while (true);

            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            }
            stats.updateNumMsgsReceived(message);
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.incrementNumReceiveFailed();
            throw new PulsarClientException(e);
        } finally {
            // Finally blocked is invoked in case the block on incomingMessages is interrupted
            waitingOnReceiveForZeroQueueSize = false;
            // Clearing the queue in case there was a race with messageReceived
            incomingMessages.clear();
        }
    }

    @Override
    protected Message internalReceive(int timeout, TimeUnit unit) throws PulsarClientException {
        Message message;
        try {
            message = incomingMessages.poll(timeout, unit);
            if (message != null) {
                messageProcessed(message);
                if (unAckedMessageTracker != null) {
                    unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
                }
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.incrementNumReceiveFailed();
            throw new PulsarClientException(e);
        }
    }

    // we may not be able to ack message being acked by client. However messages in prior
    // batch may be ackable
    private void ackMessagesInEarlierBatch(BatchMessageIdImpl batchMessageId, MessageIdImpl message) {
        // get entry before this message and ack that message on broker
        MessageIdImpl lowerKey = batchMessageAckTracker.lowerKey(message);
        if (lowerKey != null) {
            NavigableMap entriesUpto = batchMessageAckTracker.headMap(lowerKey, true);
            for (Object key : entriesUpto.keySet()) {
                entriesUpto.remove(key);
            }
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] ack prior message {} to broker on cumulative ack for message {}", subscription,
                        consumerId, lowerKey, batchMessageId);
            }
            sendAcknowledge(lowerKey, AckType.Cumulative);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] no messages prior to message {}", subscription, consumerId, batchMessageId);
            }
        }
    }

    boolean markAckForBatchMessage(BatchMessageIdImpl batchMessageId, AckType ackType) {
        // we keep track of entire batch and so need MessageIdImpl and cannot use BatchMessageIdImpl
        MessageIdImpl message = new MessageIdImpl(batchMessageId.getLedgerId(), batchMessageId.getEntryId(),
                batchMessageId.getPartitionIndex());
        BitSet bitSet = batchMessageAckTracker.get(message);
        if (bitSet == null) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] message not found {} for ack {}", subscription, consumerId, batchMessageId,
                        ackType);
            }
            return true;
        }
        int batchIndex = batchMessageId.getBatchIndex();
        int batchSize = bitSet.length();
        if (ackType == AckType.Individual) {
            bitSet.clear(batchIndex);
        } else {
            // +1 since to argument is exclusive
            bitSet.clear(0, batchIndex + 1);
        }
        // all messages in this batch have been acked
        if (bitSet.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] can ack message to broker {}, acktype {}, cardinality {}, length {}", subscription,
                        consumerName, batchMessageId, ackType, bitSet.cardinality(), bitSet.length());
            }
            if (ackType == AckType.Cumulative) {
                batchMessageAckTracker.keySet().removeIf(m -> (m.compareTo(message) <= 0));
            }
            batchMessageAckTracker.remove(message);
            // increment Acknowledge-msg counter with number of messages in batch only if AckType is Individual. 
            // CumulativeAckType is handled while sending ack to broker 
            if (ackType == AckType.Individual) {
                stats.incrementNumAcksSent(batchSize);
            }
            return true;
        } else {
            // we cannot ack this message to broker. but prior message may be ackable
            if (ackType == AckType.Cumulative) {
                ackMessagesInEarlierBatch(batchMessageId, message);
            }
            if (log.isDebugEnabled()) {
                int outstandingAcks = batchMessageAckTracker.get(message).cardinality();
                log.debug("[{}] [{}] cannot ack message to broker {}, acktype {}, pending acks - {}", subscription,
                        consumerName, batchMessageId, ackType, outstandingAcks);
            }
        }
        return false;
    }

    // if we are consuming a mix of batch and non-batch messages then cumulative ack on non-batch messages
    // should clean up the ack tracker as well
    private void updateBatchAckTracker(MessageIdImpl message, AckType ackType) {
        if (batchMessageAckTracker.isEmpty()) {
            return;
        }
        MessageIdImpl lowerKey = batchMessageAckTracker.lowerKey(message);
        if (lowerKey != null) {
            NavigableMap entriesUpto = batchMessageAckTracker.headMap(lowerKey, true);
            for (Object key : entriesUpto.keySet()) {
                entriesUpto.remove(key);
            }
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] updated batch ack tracker up to message {} on cumulative ack for message {}",
                        subscription, consumerId, lowerKey, message);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] no messages to clean up prior to message {}", subscription, consumerId, message);
            }
        }

    }

    /**
     * helper method that returns current state of data structure used to track acks for batch messages
     * 
     * @return true if all batch messages have been acknowledged
     */
    public boolean isBatchingAckTrackerEmpty() {
        return batchMessageAckTracker.isEmpty();
    }

    @Override
    protected CompletableFuture<Void> doAcknowledge(MessageId messageId, AckType ackType) {
        checkArgument(messageId instanceof MessageIdImpl);
        if (state.get() != State.Ready && state.get() != State.Connecting) {
            stats.incrementNumAcksFailed();
            return FutureUtil.failedFuture(new PulsarClientException("Consumer not ready. State: " + state.get()));
        }

        if (messageId instanceof BatchMessageIdImpl) {
            if (markAckForBatchMessage((BatchMessageIdImpl) messageId, ackType)) {
                // all messages in batch have been acked so broker can be acked via sendAcknowledge()
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] acknowledging message - {}, acktype {}", subscription, consumerName, messageId,
                            ackType);
                }
            } else {
                // other messages in batch are still pending ack.
                return CompletableFuture.completedFuture(null);
            }

        }
        // if we got a cumulative ack on non batch message, check if any earlier batch messages need to be removed
        // from batch message tracker
        if (ackType == AckType.Cumulative && !(messageId instanceof BatchMessageIdImpl)) {
            updateBatchAckTracker((MessageIdImpl) messageId, ackType);
        }
        return sendAcknowledge(messageId, ackType);
    }

    private CompletableFuture<Void> sendAcknowledge(MessageId messageId, AckType ackType) {
        MessageIdImpl msgId = (MessageIdImpl) messageId;
        final ByteBuf cmd = Commands.newAck(consumerId, msgId.getLedgerId(), msgId.getEntryId(), ackType, null);

        // There's no actual response from ack messages
        final CompletableFuture<Void> ackFuture = new CompletableFuture<Void>();

        if (isConnected()) {
            cnx().ctx().writeAndFlush(cmd).addListener(new GenericFutureListener<Future<Void>>() {
                @Override
                public void operationComplete(Future<Void> future) throws Exception {
                    if (future.isSuccess()) {
                        if (ackType == AckType.Individual) {
                            if (unAckedMessageTracker != null) {
                                unAckedMessageTracker.remove(msgId);
                            }
                            // increment counter by 1 for non-batch msg
                            if (!(messageId instanceof BatchMessageIdImpl)) {
                                stats.incrementNumAcksSent(1);
                            }
                        } else if (ackType == AckType.Cumulative) {
                            if (unAckedMessageTracker != null) {
                                int ackedMessages = unAckedMessageTracker.removeMessagesTill(msgId);
                                stats.incrementNumAcksSent(ackedMessages);
                            }
                        }
                        ackFuture.complete(null);
                    } else {
                        stats.incrementNumAcksFailed();
                        ackFuture.completeExceptionally(new PulsarClientException(future.cause()));
                    }
                }
            });
        } else {
            stats.incrementNumAcksFailed();
            ackFuture
                    .completeExceptionally(new PulsarClientException("Not connected to broker. State: " + state.get()));
        }

        return ackFuture;
    }

    @Override
    void connectionOpened(final ClientCnx cnx) {
        clientCnx.set(cnx);
        cnx.registerConsumer(consumerId, this);

        log.info("[{}][{}] Subscribing to topic on cnx {}", topic, subscription, cnx.ctx().channel());

        long requestId = client.newRequestId();
        cnx.sendRequestWithId(
                Commands.newSubscribe(topic, subscription, consumerId, requestId, getSubType(), consumerName),
                requestId).thenRun(() -> {
                    synchronized (ConsumerImpl.this) {
                        incomingMessages.clear();
                        if (unAckedMessageTracker != null) {
                            unAckedMessageTracker.clear();
                        }
                        if (changeToReadyState()) {
                            log.info("[{}][{}] Subscribed to topic on {} -- consumer: {}", topic, subscription,
                                    cnx.channel().remoteAddress(), consumerId);

                            availablePermits.set(0);
                            // If the connection is reset and someone is waiting for the messages
                            // send a flow command
                            if (waitingOnReceiveForZeroQueueSize) {
                                receiveMessages(cnx, 1);
                            }
                        } else {
                            // Consumer was closed while reconnecting, close the connection to make sure the broker
                            // drops the consumer on its side
                            state.set(State.Closed);
                            cnx.removeConsumer(consumerId);
                            cnx.channel().close();
                            return;
                        }
                    }

                    resetBackoff();

                    boolean firstTimeConnect = subscribeFuture.complete(this);
                    // if the consumer is not partitioned or is re-connected and is partitioned, we send the flow
                    // command to receive messages
                    if (!(firstTimeConnect && partitionIndex > -1) && conf.getReceiverQueueSize() != 0) {
                        receiveMessages(cnx, conf.getReceiverQueueSize());
                    }
                }).exceptionally((e) -> {
                    cnx.removeConsumer(consumerId);
                    if (state.get() == State.Closing || state.get() == State.Closed) {
                        // Consumer was closed while reconnecting, close the connection to make sure the broker
                        // drops the consumer on its side
                        cnx.channel().close();
                        return null;
                    }
                    log.warn("[{}][{}] Failed to subscribe to topic on {}", topic, subscription,
                            cnx.channel().remoteAddress());
                    if (e.getCause() instanceof PulsarClientException
                            && isRetriableError((PulsarClientException) e.getCause())
                            && System.currentTimeMillis() < subscribeTimeout) {
                        reconnectLater(e.getCause());
                        return null;
                    }

                    if (!subscribeFuture.isDone()) {
                        // unable to create new consumer, fail operation
                        state.set(State.Failed);
                        subscribeFuture.completeExceptionally(e);
                        client.cleanupConsumer(this);
                    } else {
                        // consumer was subscribed and connected but we got some error, keep trying
                        reconnectLater(e.getCause());
                    }
                    return null;
                });
    }

    /**
     * send the flow command to have the broker start pushing messages
     */
    void receiveMessages(ClientCnx cnx, int numMessages) {
        if (cnx != null) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Adding {} additional permits", topic, subscription, numMessages);
            }

            cnx.ctx().writeAndFlush(Commands.newFlow(consumerId, numMessages), cnx.ctx().voidPromise());
        }
    }

    @Override
    void connectionFailed(PulsarClientException exception) {
        if (System.currentTimeMillis() > subscribeTimeout && subscribeFuture.completeExceptionally(exception)) {
            state.set(State.Failed);
            client.cleanupConsumer(this);
        }
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (state.get() == State.Closing || state.get() == State.Closed) {
            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.close();
            }
            return CompletableFuture.completedFuture(null);
        }

        if (!isConnected()) {
            log.info("[{}] [{}] Closed Consumer (not connected)", topic, subscription);
            state.set(State.Closed);
            batchMessageAckTracker.clear();
            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.close();
            }
            client.cleanupConsumer(this);
            return CompletableFuture.completedFuture(null);
        }

        Timeout timeout = stats.getStatTimeout();
        if (timeout != null) {
            timeout.cancel();
        }

        state.set(State.Closing);

        long requestId = client.newRequestId();
        ByteBuf cmd = Commands.newCloseConsumer(consumerId, requestId);

        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        ClientCnx cnx = cnx();
        cnx.sendRequestWithId(cmd, requestId).handle((v, exception) -> {
            cnx.removeConsumer(consumerId);
            if (exception == null || !cnx.ctx().channel().isActive()) {
                log.info("[{}] [{}] Closed consumer", topic, subscription);
                state.set(State.Closed);
                batchMessageAckTracker.clear();
                if (unAckedMessageTracker != null) {
                    unAckedMessageTracker.close();
                }
                closeFuture.complete(null);
                client.cleanupConsumer(this);
            } else {
                closeFuture.completeExceptionally(exception);
            }
            return null;
        });

        return closeFuture;
    }

    void messageReceived(MessageIdData messageId, ByteBuf headersAndPayload, ClientCnx cnx) {
        if (log.isDebugEnabled()) {
            log.debug("[{}][{}] Received message: {}", topic, subscription, messageId);
        }

        MessageMetadata msgMetadata = null;
        ByteBuf payload = headersAndPayload;
        try {
            msgMetadata = Commands.parseMessageMetadata(payload);
        } catch (Throwable t) {
            discardCorruptedMessage(messageId, cnx, ValidationError.ChecksumMismatch);
            return;
        }

        ByteBuf uncompressedPayload = uncompressPayloadIfNeeded(messageId, msgMetadata, payload, cnx);
        if (uncompressedPayload == null) {
            // Message was discarded on decompression error
            return;
        }

        if (!verifyChecksum(messageId, msgMetadata, uncompressedPayload, cnx)) {
            // Message discarded for checksum error
            return;
        }

        final int numMessages = msgMetadata.getNumMessagesInBatch();

        if (numMessages == 1 && !msgMetadata.hasNumMessagesInBatch()) {
            final MessageImpl message = new MessageImpl(messageId, msgMetadata, uncompressedPayload,
                    getPartitionIndex(), cnx);
            uncompressedPayload.release();
            msgMetadata.recycle();

            try {
                lock.readLock().lock();
                // Enqueue the message so that it can be retrieved when application calls receive()
                // if the conf.getReceiverQueueSize() is 0 then discard message if no one is waiting for it.
                // if asyncReceive is waiting then notify callback without adding to incomingMessages queue
                boolean asyncReceivedWaiting = !pendingReceives.isEmpty();
                if ((conf.getReceiverQueueSize() != 0 || waitingOnReceiveForZeroQueueSize) && !asyncReceivedWaiting) {
                    incomingMessages.add(message);
                }
                if (asyncReceivedWaiting) {
                    notifyPendingReceivedCallback(message, null);
                }
            } finally {
                lock.readLock().unlock();
            }
        } else {
            if (conf.getReceiverQueueSize() == 0) {
                log.warn(
                        "Closing consumer [{}]-[{}] due to unsupported received batch-message with zero receiver queue size",
                        subscription, consumerName);
                // close connection
                closeAsync().handle((ok, e) -> {
                    // notify callback with failure result
                    notifyPendingReceivedCallback(null,
                            new PulsarClientException.InvalidMessageException(
                                    format("Unsupported Batch message with 0 size receiver queue for [%s]-[%s] ",
                                            subscription, consumerName)));
                    return null;
                });
            } else {
                // handle batch message enqueuing; uncompressed payload has all messages in batch
                receiveIndividualMessagesFromBatch(msgMetadata, uncompressedPayload, messageId, cnx);
            }
            uncompressedPayload.release();
            msgMetadata.recycle();
        }

        if (listener != null) {
            // Trigger the notification on the message listener in a separate thread to avoid blocking the networking
            // thread while the message processing happens
            listenerExecutor.execute(() -> {
                for (int i = 0; i < numMessages; i++) {
                    Message msg;
                    try {
                        msg = internalReceive();
                    } catch (PulsarClientException e) {
                        log.warn("[{}] [{}] Failed to dequeue the message for listener", topic, subscription, e);
                        return;
                    }

                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}][{}] Calling message listener for message {}", topic, subscription, msg);
                        }
                        listener.received(ConsumerImpl.this, msg);
                    } catch (Throwable t) {
                        log.error("[{}][{}] Message listener error in processing message: {}", topic, subscription, msg,
                                t);
                    }
                }
            });
        }
    }

    /**
     * Notify waiting asyncReceive request with the received message
     *
     * @param message
     */
    void notifyPendingReceivedCallback(final MessageImpl message, Exception exception) {
        if (!pendingReceives.isEmpty()) {
            // fetch receivedCallback from queue
            CompletableFuture<Message> receivedFuture = pendingReceives.poll();
            if (exception == null) {
                checkNotNull(message, "received message can't be null");
                // add message to unAckedMessage tracker
                if (unAckedMessageTracker != null) {
                    unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
                }
                if (receivedFuture != null) {
                    if (conf.getReceiverQueueSize() == 0) {
                        // return message to receivedCallback
                        receivedFuture.complete(message);
                    } else {
                        // increase permits for available message-queue
                        messageProcessed(message);
                        // return message to receivedCallback
                        listenerExecutor.execute(() -> receivedFuture.complete(message));
                    }
                }
            } else {
                listenerExecutor.execute(() -> receivedFuture.completeExceptionally(exception));
            }
        }
    }

    void receiveIndividualMessagesFromBatch(MessageMetadata msgMetadata, ByteBuf uncompressedPayload,
            MessageIdData messageId, ClientCnx cnx) {
        int batchSize = msgMetadata.getNumMessagesInBatch();

        // create ack tracker for entry aka batch
        BitSet bitSet = new BitSet(batchSize);
        MessageIdImpl batchMessage = new MessageIdImpl(messageId.getLedgerId(), messageId.getEntryId(),
                getPartitionIndex());
        bitSet.set(0, batchSize);
        if (log.isDebugEnabled()) {
            log.debug("[{}] [{}] added bit set for message {}, cardinality {}, length {}", subscription, consumerName,
                    batchMessage, bitSet.cardinality(), bitSet.length());
        }
        batchMessageAckTracker.put(batchMessage, bitSet);
        try {
            for (int i = 0; i < batchSize; ++i) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] processing message num - {} in batch", subscription, consumerName, i);
                }
                PulsarApi.SingleMessageMetadata.Builder singleMessageMetadataBuilder = PulsarApi.SingleMessageMetadata
                        .newBuilder();
                ByteBuf singleMessagePayload = Commands.deSerializeSingleMessageInBatch(uncompressedPayload,
                        singleMessageMetadataBuilder, i, batchSize);
                BatchMessageIdImpl batchMessageIdImpl = new BatchMessageIdImpl(messageId.getLedgerId(),
                        messageId.getEntryId(), getPartitionIndex(), i);
                final MessageImpl message = new MessageImpl(batchMessageIdImpl, msgMetadata,
                        singleMessageMetadataBuilder.build(), singleMessagePayload, cnx);
                lock.readLock().lock();
                if (pendingReceives.isEmpty()) {
                    incomingMessages.add(message);
                } else {
                    notifyPendingReceivedCallback(message, null);
                }
                lock.readLock().unlock();
                singleMessagePayload.release();
                singleMessageMetadataBuilder.recycle();
            }
        } catch (IOException e) {
            //
            log.warn("[{}] [{}] unable to obtain message in batch", subscription, consumerName);
            batchMessageAckTracker.remove(batchMessage);
            discardCorruptedMessage(messageId, cnx, ValidationError.BatchDeSerializeError);
        }
        if (log.isDebugEnabled()) {
            log.debug("[{}] [{}] enqueued messages in batch. queue size - {}, available queue size - {}", subscription,
                    consumerName, incomingMessages.size(), incomingMessages.remainingCapacity());
        }
    }

    /**
     * Record the event that one message has been processed by the application.
     *
     * Periodically, it sends a Flow command to notify the broker that it can push more messages
     */
    private synchronized void messageProcessed(Message msg) {
        ClientCnx currentCnx = cnx();
        ClientCnx msgCnx = ((MessageImpl) msg).getCnx();

        if (msgCnx != currentCnx) {
            // The processed message did belong to the old queue that was cleared after reconnection.
            return;
        }

        increaseAvailablePermits(currentCnx);
        stats.updateNumMsgsReceived(msg);
    }

    private void increaseAvailablePermits(ClientCnx currentCnx) {
        int available = availablePermits.incrementAndGet();

        while (available >= receiverQueueRefillThreshold) {
            if (availablePermits.compareAndSet(available, 0)) {
                receiveMessages(currentCnx, available);
                break;
            } else {
                available = availablePermits.get();
            }
        }
    }

    private ByteBuf uncompressPayloadIfNeeded(MessageIdData messageId, MessageMetadata msgMetadata, ByteBuf payload,
            ClientCnx currentCnx) {
        CompressionType compressionType = msgMetadata.getCompression();
        CompressionCodec codec = codecProvider.getCodec(compressionType);
        int uncompressedSize = msgMetadata.getUncompressedSize();
        if (uncompressedSize > PulsarDecoder.MaxMessageSize) {
            // Uncompressed size is itself corrupted since it cannot be bigger than the MaxMessageSize
            log.error("[{}][{}] Got corrupted uncompressed message size {} at {}", topic, subscription,
                    uncompressedSize, messageId);
            discardCorruptedMessage(messageId, currentCnx, ValidationError.UncompressedSizeCorruption);
            return null;
        }

        try {
            ByteBuf uncompressedPayload = codec.decode(payload, uncompressedSize);
            return uncompressedPayload;
        } catch (IOException e) {
            log.error("[{}][{}] Failed to decompress message with {} at {}: {}", topic, subscription, compressionType,
                    messageId, e.getMessage(), e);
            discardCorruptedMessage(messageId, currentCnx, ValidationError.DecompressionError);
            return null;
        }
    }

    private boolean verifyChecksum(MessageIdData messageId, MessageMetadata msgMetadata, ByteBuf payload,
            ClientCnx currentCnx) {
        if (!msgMetadata.hasChecksum()) {
            // No checksum to validate
            return true;
        }

        long storedChecksum = msgMetadata.getChecksum();
        long computedChecksum = XXHashChecksum.computeChecksum(payload);

        if (storedChecksum == computedChecksum) {
            return true;
        } else {
            log.error(
                    "[{}][{}] Checksum mismatch for message at {}:{}. Received content:\n{}"
                            + "\nReceived checksum: 0x{} -- Computed checksum: 0x{}",
                    topic, subscription, messageId.getLedgerId(), messageId.getEntryId(),
                    ByteBufUtil.prettyHexDump(payload), Long.toHexString(storedChecksum),
                    Long.toHexString(computedChecksum));
            discardCorruptedMessage(messageId, currentCnx, ValidationError.ChecksumMismatch);
            return false;
        }
    }

    private void discardCorruptedMessage(MessageIdData messageId, ClientCnx currentCnx,
            ValidationError validationError) {
        log.error("[{}][{}] Discarding corrupted message at {}:{}", topic, subscription, messageId.getLedgerId(),
                messageId.getEntryId());
        ByteBuf cmd = Commands.newAck(consumerId, messageId.getLedgerId(), messageId.getEntryId(), AckType.Individual,
                validationError);
        currentCnx.ctx().writeAndFlush(cmd, currentCnx.ctx().voidPromise());
        increaseAvailablePermits(currentCnx);
        stats.incrementNumReceiveFailed();
    }

    @Override
    String getHandlerName() {
        return subscription;
    }

    @Override
    public boolean isConnected() {
        return clientCnx.get() != null && (state.get() == State.Ready);
    }

    int getPartitionIndex() {
        return partitionIndex;
    }

    public int getAvailablePermits() {
        return availablePermits.get();
    }

    public int numMessagesInQueue() {
        return incomingMessages.size();
    }

    @Override
    public void redeliverUnacknowledgedMessages() {
        ClientCnx cnx = cnx();
        if (isConnected() && cnx.getRemoteEndpointProtocolVersion() >= ProtocolVersion.v2.getNumber()) {
            if (unAckedMessageTracker != null) {
                unAckedMessageTracker.clear();
            }
            cnx.ctx().writeAndFlush(Commands.newRedeliverUnacknowledgedMessages(consumerId), cnx.ctx().voidPromise());
            return;
        }
        if (cnx == null || (state.get() == State.Connecting)) {
            log.warn("[{}] Client Connection needs to be establised for redelivery of unacknowledged messages", this);
        } else {
            log.warn("[{}] Reconnecting the client to redeliver the messages.", this);
            cnx.ctx().close();
        }
    }

    @Override
    public ConsumerStats getStats() {
        if (stats instanceof ConsumerStatsDisabled) {
            return null;
        }
        return stats;
    }

    private static final Logger log = LoggerFactory.getLogger(ConsumerImpl.class);

}
