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
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.vrg.rapid.Settings;
import com.vrg.rapid.SharedResources;
import com.vrg.rapid.Utils;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Random;
// import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
// import java.util.concurrent.ScheduledFuture;
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
    private static final int DEFAULT_BUF_SIZE = 8192;
    public static final boolean DEFAULT_GRPC_USE_IN_PROCESS_TRANSPORT = false;
    public final Map<String, Double> latencyCache = new HashMap<>();
    public static final int DEFAULT_GRPC_TIMEOUT_MS = 3000;
    public static final int DEFAULT_GRPC_DEFAULT_RETRIES = 10;
    public static final int DEFAULT_GRPC_JOIN_TIMEOUT = DEFAULT_GRPC_TIMEOUT_MS * 15;
    // public static final int DEFAULT_GRPC_JOIN_TIMEOUT = DEFAULT_GRPC_TIMEOUT_MS * 5;
    public static final int DEFAULT_GRPC_PROBE_TIMEOUT = 3000;

    private final Endpoint address;
    private final LoadingCache<Endpoint, Channel> channelMap;
    private final Map<String, Long> latencyMap;
    private final ExecutorService grpcExecutor;
    private final ExecutorService backgroundExecutor;
     // Declare a ScheduledExecutorService
    private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    // private final double meanLatency = 50; // Mean latency in milliseconds
    // private final double stdDevLatency = 15; // Standard deviation in milliseconds
    private final double meanLatency = 50; // Mean latency in milliseconds
    private final double stdDevLatency = 10; // Standard deviation in milliseconds
     // 模拟延迟异常时的参数
    private double lambda = 0;  // 0.00001 --> 1 error / 500s 
    private double minErrorLatency = 100;  // 错误延迟最小值
    private double maxErrorLatency = 500;  // 错误延迟最大值
    final Random randomPoisson = new Random();

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
        this.latencyMap = new ConcurrentHashMap<>();
        this.latencyMap.put(Utils.stringFromHost(address), (long)0);
    }

    // private int generatePoissonEvent(double lambda) {
    //     double L = Math.exp(-lambda);
    //     double p = 1.0;
    //     int k = 0;

    //     do {
    //         k++;
    //         p *= randomPoisson.nextDouble();
    //     } while (p > L);

    //     return k - 1;  // 返回事件发生的次数
    // }

    // Method to calculate latency based on sender and receiver ports
    // @Override
    public double getLatency(final Endpoint sender, final Endpoint receiver) {
        final String key = sender.getPort() < receiver.getPort()
        ? sender.getPort() + "-" + receiver.getPort()
        : receiver.getPort() + "-" + sender.getPort();

// Check if the latency for this pair is already computed

        if (latencyCache.containsKey(key)) {
            double latency_ = latencyCache.get(key);
                    // 模拟延迟错误：基于泊松过程决定是否发生延迟异常
        //     double poissonSample = generatePoissonEvent(lambda);
        // if (poissonSample > 0) {
        //     double errorLatency = Math.min(maxErrorLatency, Math.max(minErrorLatency, randomPoisson.nextDouble() * maxErrorLatency));
        //     // System.out.println("Network error detected, introducing latency error: " + errorLatency + " ms.");
        //     return latency_ + errorLatency;  // 返回默认延迟加上延迟错误
        // }

            return latency_;
        }
        final Random random = new Random();
        // Combine sender and receiver port into a hash for determinism
        final int hash_1 = Integer.hashCode(sender.getPort() * 31 + receiver.getPort());
        final int hash_2 = Integer.hashCode(receiver.getPort() * 31 + sender.getPort());
        final int combinedHash = hash_1 ^ hash_2;
        random.setSeed(combinedHash); // Seed the random generator
        
        // Generate a Gaussian value and scale it to mean and standard deviation
        final double gaussian = random.nextGaussian();
        final double latency = Math.min(300, Math.max(meanLatency + gaussian * stdDevLatency, 5));
        // final double latency = Math.max(meanLatency + gaussian * stdDevLatency, 5);
        
        latencyCache.put(key, latency);

        // Ensure latency is non-negative
        return latency;
    }

    @Override
    public ListenableFuture<RapidResponse> sendMessage(final Endpoint remote, final RapidRequest msg) {
        Objects.requireNonNull(remote);
        Objects.requireNonNull(msg);
        SettableFuture<RapidResponse> resultFutureSettable = SettableFuture.create();
        SettableFuture<RapidResponse> resultFuture = SettableFuture.create();
        if (isShuttingDown.get()) {
            // 如果正在关闭，立即返回异常
            // throw new IllegalStateException("Cannot send message: Client is shutting down");
            resultFutureSettable.setException(new IllegalStateException("Cannot send message: Client is shutting down"));
            return resultFutureSettable;
        }
        final long startTime = System.nanoTime();
        // 延迟50毫秒后执行实际RPC调用
        scheduledExecutor.schedule(() -> {
            if (isShuttingDown.get()) {
                // 在关闭状态中，直接设置异常
                resultFutureSettable.setException(new IllegalStateException("Task cancelled: Client is shutting down"));
                return;
            }
            final Supplier<ListenableFuture<RapidResponse>> call = () -> {
                final MembershipServiceFutureStub stub = getFutureStub(remote)
                        .withDeadlineAfter(getTimeoutForMessageMs(msg), TimeUnit.MILLISECONDS);
                return stub.sendRequest(msg);
            };

            final Runnable onCallFailure = () -> channelMap.invalidate(remote);

            // 使用Retries进行RPC调用
            final SettableFuture<ResponseWithLatency> rpcFutureWithLatency = Retries.callWithRetries(
                call, 
                remote, 
                settings.getGrpcDefaultRetries(), 
                onCallFailure, 
                backgroundExecutor, 
                msg,
                latencyMap,
                startTime,
                getTimeoutForMessageMs(msg)
            );

            Futures.addCallback(Futures.transform(
                rpcFutureWithLatency,
                ResponseWithLatency::getResponse, // Extract the RapidResponse from ResponseWithLatency
                MoreExecutors.directExecutor()   // Use direct executor to run the transformation on the same thread
            ), new RapidResponseFutureCallback(resultFuture),
                                MoreExecutors.directExecutor());

        }, (int) getLatency(address, remote), TimeUnit.MILLISECONDS);

        return resultFuture;
        
        // final long startTime = System.nanoTime();
        // final Supplier<ListenableFuture<RapidResponse>> call = () -> {
        //     final MembershipServiceFutureStub stub = getFutureStub(remote)
        //             .withDeadlineAfter(getTimeoutForMessageMs(msg),
        //                     TimeUnit.MILLISECONDS);
        //     return stub.sendRequest(msg);
        // };
        // final Runnable onCallFailure = () -> channelMap.invalidate(remote);
        // final SettableFuture<ResponseWithLatency> rpcFutureWithLatency = 
        // Retries.callWithRetries(call, remote, settings.getGrpcDefaultRetries(), onCallFailure,
        //                                backgroundExecutor,  msg,
        //                                        latencyMap,
        //                                        startTime,
        //                                        getTimeoutForMessageMs(msg));
        // return  Futures.transform(
        //             rpcFutureWithLatency,
        //             ResponseWithLatency::getResponse, // Extract the RapidResponse from ResponseWithLatency
        //             MoreExecutors.directExecutor()   // Use direct executor to run the transformation on the same thread
        //         );
    }
   
    // Use the scheduledExecutor for scheduling tasks with delays
    @Override
    public ListenableFuture<RapidResponse> sendMessageBestEffort(final Endpoint remote, final RapidRequest msg) {
        Objects.requireNonNull(msg);
        final SettableFuture<RapidResponse> resultFuture = SettableFuture.create();
        if (isShuttingDown.get()) {
            // 如果正在关闭，立即返回异常
            // throw new IllegalStateException("Cannot send message: Client is shutting down");
            // SettableFuture<RapidResponse> resultFutureSettable = SettableFuture.create();
            resultFuture.setException(new IllegalStateException("Cannot send message: Client is shutting down"));
            return resultFuture;
        }
        final long startTime = System.nanoTime();
        // Schedule the delayed execution
        scheduledExecutor.schedule(() -> {
            if (isShuttingDown.get()) {
                // 在关闭状态中，直接设置异常
                resultFuture.setException(new IllegalStateException("Task cancelled: Client is shutting down"));
                return;
            }
            final Supplier<ListenableFuture<RapidResponse>> call = () -> {
                final MembershipServiceFutureStub stub = getFutureStub(remote)
                        .withDeadlineAfter(getTimeoutForMessageMs(msg), TimeUnit.MILLISECONDS);
                return stub.sendRequest(msg);
            };

            final Runnable onCallFailure = () -> channelMap.invalidate(remote);

            final ListenableFuture<ResponseWithLatency> rpcFutureWithLatency =
                Retries.callWithRetries(call, remote, 0, onCallFailure, 
                backgroundExecutor, msg, latencyMap, startTime,
                getTimeoutForMessageMs(msg));

            Futures.addCallback(Futures.transform(
                rpcFutureWithLatency,
                ResponseWithLatency::getResponse, // Extract the RapidResponse from ResponseWithLatency
                MoreExecutors.directExecutor()   // Use direct executor to run the transformation on the same thread
            ), new RapidResponseFutureCallback(resultFuture),
             MoreExecutors.directExecutor());

        }, (int) getLatency(address, remote), TimeUnit.MILLISECONDS);

        return resultFuture;
        // final long startTime = System.nanoTime();
        // final Supplier<ListenableFuture<RapidResponse>> call = () -> {
        //     final MembershipServiceFutureStub stub = getFutureStub(remote)
        //             .withDeadlineAfter(getTimeoutForMessageMs(msg),
        //                     TimeUnit.MILLISECONDS);
        //     return stub.sendRequest(msg);
        // };
        // final Runnable onCallFailure = () -> channelMap.invalidate(remote);
        // final SettableFuture<ResponseWithLatency> rpcFutureWithLatency = 
        // Retries.callWithRetries(call, remote, 0, onCallFailure,
        //                                backgroundExecutor,  msg,
        //                                        latencyMap,
        //                                        startTime,
        //                                        getTimeoutForMessageMs(msg));
        // return  Futures.transform(
        //             rpcFutureWithLatency,
        //             ResponseWithLatency::getResponse, // Extract the RapidResponse from ResponseWithLatency
        //             MoreExecutors.directExecutor()   // Use direct executor to run the transformation on the same thread
        //         );
    }


    /**
     * Recover resources. For future use in case we provide custom grpcExecutor for the ManagedChannels.
     */
    @Override
    public void shutdown() {
        isShuttingDown.set(true);
        channelMap.invalidateAll();
        scheduledExecutor.shutdown();
        scheduledExecutor.shutdownNow();
        // try {
        //     // 等待正在执行的任务完成
        //     if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
        //         // System.err.println("Forcing shutdown: Tasks did not finish in time");
        //         // 强制终止所有任务
        //         scheduledExecutor.shutdownNow();
        //     }
        // } catch (InterruptedException e) {
        //     Thread.currentThread().interrupt();
        //     // System.err.println("Shutdown interrupted");
        //     scheduledExecutor.shutdownNow();
        // }

        // System.out.println("Client shutdown complete.");
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
    
    @Override
    public Endpoint getAddress() {
        return address;
    }

    // Define the static inner class at the bottom of your file or somewhere appropriate:
    // @Override
    private static class RapidResponseFutureCallback implements FutureCallback<RapidResponse> {
        private final SettableFuture<RapidResponse> resultFuture;

        RapidResponseFutureCallback(final SettableFuture<RapidResponse> resultFuture) {
            this.resultFuture = resultFuture;
        }

        @Override
        public void onSuccess(final RapidResponse result) {
            // latencyMap.put(endpoint, latencyMs);
            resultFuture.set(result);
        }

        @Override
        public void onFailure(final Throwable t) {
            resultFuture.setException(t);
        }
    }
    
    public Map<String, Long> getLatencyMap(){
        return latencyMap;
    }

}
