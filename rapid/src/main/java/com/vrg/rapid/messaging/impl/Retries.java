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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * @param <T> The type of the response.
     * @return Returns a ListenableFuture of type T, that hosts the result of the supplied {@code call}.
     */
    @CanIgnoreReturnValue
    static <T> ListenableFuture<T> callWithRetries(final Supplier<ListenableFuture<T>> call,
                                                   final Endpoint remote, final int retries,
                                                   final Runnable onCallFailure,
                                                   final ExecutorService backgroundExecutor) {
        LOG.info("callWithRetries!\n");
        final SettableFuture<T> settable = SettableFuture.create();
        startCallWithRetry(call, remote, settable, retries, onCallFailure, backgroundExecutor);
        return settable;
    }

    /**
     * Adapted from https://github.com/spotify/futures-extra/.../AsyncRetrier.java
     */
    @SuppressWarnings("checkstyle:illegalcatch")
    private static <T> void startCallWithRetry(final Supplier<ListenableFuture<T>> call, final Endpoint remote,
                                               final SettableFuture<T> signal, final int retries,
                                               final Runnable onCallFailure, final ExecutorService backgroundExecutor) {
        if (Thread.currentThread().isInterrupted()) {
            signal.setException(new InterruptedException("Thread has been interrupted"));
            return;
        }
        final ListenableFuture<T> callFuture = call.get();
        Futures.addCallback(callFuture, new FutureCallback<T>() {
            @Override
            public void onSuccess(final T result) {
                signal.set(result);
            }

            @Override
            public void onFailure(final Throwable throwable) {
                onCallFailure.run();
                LOG.error("Retrying call to {} because of exception {}", remote, throwable);
                handleFailure(call, remote, signal, retries, throwable, onCallFailure, backgroundExecutor);
            }
        }, backgroundExecutor);
    }

    /**
     * Adapted from https://github.com/spotify/futures-extra/.../AsyncRetrier.java
     */
    private static <T> void handleFailure(final Supplier<ListenableFuture<T>> code, final Endpoint remote,
                                          final SettableFuture<T> future, final int retries, final Throwable t,
                                          final Runnable onCallFailure, final ExecutorService backgroundExecutor) {
        LOG.info("handleFailure!\n");
        if (retries > 0) {
            startCallWithRetry(code, remote, future, retries - 1, onCallFailure, backgroundExecutor);
        } else {
            future.setException(t);
        }
    }
}
