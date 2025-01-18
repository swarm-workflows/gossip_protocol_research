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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

class Retries {

    private static final Logger LOG = LoggerFactory.getLogger(Retries.class);

    /**
     * Takes a call and retries it, returning the result as soon as it completes or the exception
     * caught from the last retry attempt.
     *
     * Adapted from https://github.com/spotify/futures-extra/.../AsyncRetrier.java
     *
     * @param call A supplier of a ListenableFuture, representing the call being retried.
     * @param retries The number of retry attempts to be performed before giving up
     * @param <RapidResponse> The type of the response.
     * @return Returns a ListenableFuture of type T, that hosts the result of the supplied {@code call}.
     */
    @CanIgnoreReturnValue
    static SettableFuture<ResponseWithLatency> callWithRetries(final Supplier<ListenableFuture<RapidResponse>> call,
                                                   final Endpoint remote, final int retries,
                                                   final Runnable onCallFailure,
                                                   final ExecutorService backgroundExecutor,
                                                final RapidRequest msg, final Map<Endpoint, Long> latencyMap) {
        final SettableFuture<ResponseWithLatency> settable = SettableFuture.create();
        startCallWithRetry(call, remote, settable, retries, onCallFailure, backgroundExecutor, msg, latencyMap);
        return settable;
    }

    /**
     * Adapted from https://github.com/spotify/futures-extra/.../AsyncRetrier.java
     */
    @SuppressWarnings("checkstyle:illegalcatch")
    private static void startCallWithRetry(final Supplier<ListenableFuture<RapidResponse>> call, final Endpoint remote,
                                               final SettableFuture<ResponseWithLatency> signal, final int retries,
                                               final Runnable onCallFailure, 
                                               final ExecutorService backgroundExecutor, 
                                               final RapidRequest msg, final Map<Endpoint, Long> latencyMap) {
        if (Thread.currentThread().isInterrupted()) {
            signal.setException(new InterruptedException("Thread has been interrupted"));
            return;
        }
        final long startTime = System.nanoTime();
        final ListenableFuture<RapidResponse> callFuture = call.get();
        Futures.addCallback(callFuture, new FutureCallback<RapidResponse>() {
            
            @Override
            public void onSuccess(final RapidResponse result) {
                long endTime = System.nanoTime();
                long latencyMs = (endTime - startTime) / 1_000_000;
                // result.setLatencyMs(latencyMs);
                latencyMap.put(remote, latencyMs);
                ResponseWithLatency wrappedResult = new ResponseWithLatency(result, latencyMs);
                signal.set(wrappedResult);
            }

            @Override
            public void onFailure(final Throwable throwable) {
                onCallFailure.run();
                // LOG.error("Retrying call to {} because of exception {}",
                //  remote, throwable);
                //   assert false : "Execution halted for debugging purposes in onFailure.";
                
                handleFailure(call, remote, signal, retries, throwable, onCallFailure, 
                backgroundExecutor, msg, latencyMap);
            }
        }, backgroundExecutor);
    }

    /**
     * Adapted from https://github.com/spotify/futures-extra/.../AsyncRetrier.java
     */
    private static void handleFailure(final Supplier<ListenableFuture<RapidResponse>> code, final Endpoint remote,
                                          final SettableFuture<ResponseWithLatency> future, final int retries, final Throwable t,
                                          final Runnable onCallFailure, 
                                          final ExecutorService backgroundExecutor,
                                          final RapidRequest msg,
                                          final Map<Endpoint, Long> latencyMap) {
        if (retries > 0) {
            startCallWithRetry(code, remote, future, retries - 1, onCallFailure, backgroundExecutor, msg, latencyMap);
        } else {
            latencyMap.put(remote, (long)-1);
            future.setException(t);
        }
    }
}
