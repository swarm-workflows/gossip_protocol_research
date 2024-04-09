/*
 * Copyright © 2016 - 2020 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an “AS IS” BASIS, without warranties or conditions of any kind,
 * EITHER EXPRESS OR IMPLIED. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.vrg.rapid.messaging.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.vrg.rapid.Settings;
import com.vrg.rapid.SharedResources;
import com.vrg.rapid.messaging.IMessagingClient;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.MembershipServiceGrpc;
import com.vrg.rapid.pb.MembershipServiceGrpc.MembershipServiceFutureStub;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

// import java.time.LocalDateTime;
// import java.time.format.DateTimeFormatter;



/**
 * MessagingServiceGrpc client.
 */
public class GrpcClient implements IMessagingClient {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcClient.class);
    private static final int DEFAULT_BUF_SIZE = 4096;
    public static final boolean DEFAULT_GRPC_USE_IN_PROCESS_TRANSPORT = false;
    public static final int DEFAULT_GRPC_TIMEOUT_MS = 1000;
    public static final int DEFAULT_GRPC_DEFAULT_RETRIES = 5;
    public static final int DEFAULT_GRPC_JOIN_TIMEOUT = DEFAULT_GRPC_TIMEOUT_MS * 5;
    public static final int DEFAULT_GRPC_PROBE_TIMEOUT = 1000;

    private final Endpoint address;
    private final LoadingCache<Endpoint, Channel> channelMap;
    private final ExecutorService grpcExecutor;
    private final ExecutorService backgroundExecutor;
    @Nullable private final EventLoopGroup eventLoopGroup;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final ISettings settings;

    @VisibleForTesting
    public GrpcClient(final Endpoint address) {
        this(address, new SharedResources(address), new Settings());
    }

    @VisibleForTesting
    public GrpcClient(final Endpoint address, final ISettings settings) {
        this(address, new SharedResources(address), settings);
    }

    public GrpcClient(final Endpoint address, final SharedResources sharedResources, final ISettings settings) {
        this.address = address;
        this.settings = settings;
        this.grpcExecutor = sharedResources.getClientChannelExecutor();
        this.backgroundExecutor = sharedResources.getBackgroundExecutor();
        this.eventLoopGroup = settings.getUseInProcessTransport() ? null : sharedResources.getEventLoopGroup();
        final RemovalListener<Endpoint, Channel> removalListener =
                removal -> shutdownChannel((ManagedChannel) removal.getValue());
        this.channelMap = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.SECONDS)
                .removalListener(removalListener)
                .build(new CacheLoader<Endpoint, Channel>() {
                    @Override
                    public Channel load(final Endpoint endpoint) {
                        return getChannel(endpoint);
                    }
                });
    }

    /**
     * From IMessagingClient
     */
    @Override
    public ListenableFuture<RapidResponse> sendMessage(final Endpoint remote, final RapidRequest msg) {
        Objects.requireNonNull(remote);
        Objects.requireNonNull(msg);
        // final LocalDateTime now = LocalDateTime.now();
        // final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // final String formattedDateTime = now.format(formatter); // "1986-04-08 12:30"
        // LOG.info("retry send_message from {} to {}, message_size={}, t={}", address, 
        //     remote, msg.getSerializedSize(), formattedDateTime);
        final Supplier<ListenableFuture<RapidResponse>> call = () -> {
            final MembershipServiceFutureStub stub = getFutureStub(remote)
                    .withDeadlineAfter(getTimeoutForMessageMs(msg),
                            TimeUnit.MILLISECONDS);
            return stub.sendRequest(msg);
        };
        final Runnable onCallFailure = () -> channelMap.invalidate(remote);
        return Retries.callWithRetries(call, remote, settings.getGrpcDefaultRetries(), onCallFailure,
                                       backgroundExecutor);
    }

    /**
     * From IMessagingClient
     */
    @Override
    public ListenableFuture<RapidResponse> sendMessageBestEffort(final Endpoint remote, final RapidRequest msg) {
        Objects.requireNonNull(msg);
        // final LocalDateTime now = LocalDateTime.now();
        // final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // final String formattedDateTime = now.format(formatter); // "1986-04-08 12:30"
        // LOG.info("best effort send_message from {} to {}, message_size={}, t={}", address, 
        //     remote, msg.getSerializedSize(), formattedDateTime);
        try {
            return backgroundExecutor.submit(() -> {
                final Supplier<ListenableFuture<RapidResponse>> call = () -> {
                    final MembershipServiceFutureStub stub;
                    stub = getFutureStub(remote).withDeadlineAfter(getTimeoutForMessageMs(msg), TimeUnit.MILLISECONDS);
                    return stub.sendRequest(msg);
                };
                final Runnable onCallFailure = () -> channelMap.invalidate(remote);
                return Retries.callWithRetries(call, remote, 0, onCallFailure, backgroundExecutor);
            }).get();
        } catch (final InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return Futures.immediateFailedFuture(e);
        }
    }

    /**
     * Recover resources. For future use in case we provide custom grpcExecutor for the ManagedChannels.
     */
    @Override
    public void shutdown() {
        isShuttingDown.set(true);
        channelMap.invalidateAll();
    }

    private MembershipServiceFutureStub getFutureStub(final Endpoint remote) {
        if (isShuttingDown.get()) {
            throw new ShuttingDownException("GrpcClient is shutting down");
        }
        final Channel channel = channelMap.getUnchecked(remote);
        return MembershipServiceGrpc.newFutureStub(channel);
    }

    private void shutdownChannel(final ManagedChannel channel) {
        channel.shutdown();
    }

    private Channel getChannel(final Endpoint remote) {
        // TODO: allow configuring SSL/TLS
        final Channel channel;
        LOG.debug("Creating channel from {} to {}", address, remote);

        if (settings.getUseInProcessTransport()) {
            channel = InProcessChannelBuilder
                    .forName(remote.toString())
                    .executor(grpcExecutor)
                    .usePlaintext(true)
                    .idleTimeout(10, TimeUnit.SECONDS)
                    .build();
        } else {
            channel = NettyChannelBuilder
                    .forAddress(remote.getHostname().toStringUtf8(), remote.getPort())
                    .executor(grpcExecutor)
                    .eventLoopGroup(eventLoopGroup)
                    .usePlaintext(true)
                    .idleTimeout(10, TimeUnit.SECONDS)
                    .withOption(ChannelOption.SO_REUSEADDR, true)
                    .withOption(ChannelOption.SO_SNDBUF, DEFAULT_BUF_SIZE)
                    .withOption(ChannelOption.SO_RCVBUF, DEFAULT_BUF_SIZE)
                    .build();
        }

        return channel;
    }

    /**
     * TODO: These timeouts should be on the Rapid side of the IMessagingClient API.
     *
     * @param msg RapidRequest
     * @return timeout to use for the RapidRequest message
     */
    private int getTimeoutForMessageMs(final RapidRequest msg) {
        switch (msg.getContentCase()) {
            case PROBEMESSAGE:
                return settings.getGrpcProbeTimeoutMs();
            case JOINMESSAGE:
                return settings.getGrpcJoinTimeoutMs();
            default:
                return settings.getGrpcTimeoutMs();
        }
    }

    public interface ISettings {
        boolean getUseInProcessTransport();

        int getGrpcTimeoutMs();

        int getGrpcDefaultRetries();

        int getGrpcJoinTimeoutMs();

        int getGrpcProbeTimeoutMs();
    }

    public static class ShuttingDownException extends RuntimeException {
        ShuttingDownException(final String msg) {
            super(msg);
        }
    }
}
