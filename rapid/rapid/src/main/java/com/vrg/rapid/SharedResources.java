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

package com.vrg.rapid;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vrg.rapid.pb.Endpoint;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Holds all executors and ELGs that are shared across a single instance of Rapid.
 */
public class SharedResources {
    private static final Logger LOG = LoggerFactory.getLogger(SharedResources.class);
    private static final int DEFAULT_THREADS = 1;
    @Nullable private EventLoopGroup eventLoopGroup = null;
    private final ExecutorService backgroundExecutor;
    private final ExecutorService serverExecutor;
    private final ExecutorService clientChannelExecutor;
    private final ExecutorService protocolExecutor;
    private final ScheduledExecutorService scheduledTasksExecutor;
    private final Endpoint address;

    public SharedResources(final Endpoint address) {
        this.address = address;
        this.serverExecutor = newNamedThreadPool(DEFAULT_THREADS, "server-exec", address);
        this.clientChannelExecutor = newNamedThreadPool(DEFAULT_THREADS, "client-exec", address);
        this.backgroundExecutor = newNamedThreadPool(DEFAULT_THREADS, "bg", address);
        this.protocolExecutor = Executors.newSingleThreadExecutor(newNamedThreadFactory("protocol", address));
        this.scheduledTasksExecutor = Executors.newSingleThreadScheduledExecutor(
                                                    newNamedThreadFactory("msbg", address));
    }

    /**
     * The ELG used by GrpcClient and RpcServer
     */
    public synchronized EventLoopGroup getEventLoopGroup() {
        // Lazily initialized because this is not required for tests that use InProcessChannel/Server.
        if (eventLoopGroup == null) {
            eventLoopGroup = new NioEventLoopGroup(DEFAULT_THREADS, newFastLocalThreadFactory("elg", address));
        }
        return eventLoopGroup;
    }

    /**
     * Used by background tasks like retries in GrpcClient
     */
    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    /**
     * The RpcServer application executor
     */
    public ExecutorService getServerExecutor() {
        return serverExecutor;
    }

    /**
     * The GrpcClient application executor
     */
    public ExecutorService getClientChannelExecutor() {
        return clientChannelExecutor;
    }

    /**
     * Executes the protocol logic in MembershipService.
     */
    public ExecutorService getProtocolExecutor() {
        return protocolExecutor;
    }

    /**
     * Executes periodic background tasks in MembershipService.
     */
    public ScheduledExecutorService getScheduledTasksExecutor() {
        return scheduledTasksExecutor;
    }

    /**
     * Shuts down resources.
     */
    synchronized void shutdown() {
        serverExecutor.shutdownNow();
        protocolExecutor.shutdownNow();
        clientChannelExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().awaitUninterruptibly(0, TimeUnit.SECONDS);
        }
    }

    /**
     * Executors and ELGs that interact with Netty benefit from FastThreadLocalThreads, and therefore
     * use Netty's DefaultThreadFactory.
     */
    private DefaultThreadFactory newFastLocalThreadFactory(final String poolName, final Endpoint address) {
        return new DefaultThreadFactory(poolName + "-" + address.getHostname().toStringUtf8() + ":"
                + address.getPort(), true);
    }

    /**
     * Standard threads with an exception handler.
     */
    private ThreadFactory newNamedThreadFactory(final String poolName, final Endpoint address) {
        final String namePrefix = poolName + "-" + address.getHostname().toStringUtf8() + ":" + address.getPort();
        return new ThreadFactoryBuilder()
                .setNameFormat(namePrefix + "-%d")
                .setDaemon(true)
                .setUncaughtExceptionHandler(
                    (t, e) -> LOG.error("{} caught exception: {} {}", t.getName(), t, e)
                ).build();
    }

    /**
     * TPE with a rejected execution handler specified.
     */
    private ThreadPoolExecutor newNamedThreadPool(final int threads, final String poolName, final Endpoint address) {
        final ThreadPoolExecutor tpe = new ThreadPoolExecutor(threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                newNamedThreadFactory(poolName, address));
        tpe.setRejectedExecutionHandler(new BackgroundExecutorRejectionHandler());
        return tpe;
    }

    static class BackgroundExecutorRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(final Runnable r, final ThreadPoolExecutor executor) {
            LOG.info("Running a task submitted to the background executor after it was shutdown()");
            r.run();
        }
    }
}
