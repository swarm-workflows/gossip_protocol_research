package com.vrg;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * A basic watermark buffer that delivers messages about a node if and only if:
 * - there are H messages about the node.
 * - there is no other node with more than L but less than H messages about it.
 */
@DefaultQualifier(value = NonNull.class, locations = {TypeUseLocation.ALL})
public class WatermarkBuffer {
    private static final int K_MIN = 3;
    private final int K;
    private final int H;
    private final int L;
    private final AtomicInteger flushCounter = new AtomicInteger(0);
    private final AtomicInteger safetyValve = new AtomicInteger(0);
    private final Map<InetSocketAddress, AtomicInteger> incarnationNumbers;
    private final Map<InetSocketAddress, AtomicInteger> updateCounters;
    private final ArrayList<Node> readyList = new ArrayList<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Consumer<LinkUpdateMessage> deliverCallback;

    public WatermarkBuffer(final int K, final int H, final int L,
                           Consumer<LinkUpdateMessage> deliverCallback) {
        if (H > K || L > H || K < K_MIN) {
            throw new IllegalArgumentException("Arguments do not satisfy K > H >= L >= 0:" +
                                               " (K: " + K + ", H: " + H + ", L: " + L);
        }
        this.K = K;
        this.H = H;
        this.L = L;
        this.updateCounters = new HashMap<>();
        this.incarnationNumbers = new HashMap<>();
        this.deliverCallback = deliverCallback;
    }

    public int getNumDelivers() {
        return flushCounter.get();
    }

    public final int ReceiveLinkUpdateMessage(final LinkUpdateMessage msg) {
        try {
            rwLock.writeLock().lock();

            final AtomicInteger incarnation = incarnationNumbers.computeIfAbsent(msg.getSrc(),
                                             (k) -> new AtomicInteger(msg.getIncarnation()));

            if (incarnation.get() > msg.getIncarnation()) {
                return -1;
            }
            // One approach here is to complain
            assert (incarnation.get() >= msg.getIncarnation());

            final AtomicInteger counter = updateCounters.computeIfAbsent(msg.getSrc(),
                                             (k) -> new AtomicInteger(0));
            final int value = counter.incrementAndGet();

            if (value == L) {
                safetyValve.incrementAndGet();
            }

            if (value == H) {
                readyList.add(new Node(msg.getSrc()));
                final int safetyValveValue = safetyValve.decrementAndGet();

                if (safetyValveValue == 0) {
                    this.flushCounter.incrementAndGet();
                    final int flushCount = readyList.size();
                    for (Node n: readyList) {
                        @Nullable final AtomicInteger updateCounter = updateCounters.get(n.address);
                        @Nullable final AtomicInteger incarnationCounter = incarnationNumbers.get(n.address);
                        if (updateCounter == null) {
                            throw new RuntimeException("Node to be flushed not in UpdateCounters map: " + n.address);
                        }
                        if (incarnationCounter == null) {
                            throw new RuntimeException("Node to be flushed not in incarnationNumbers map: " + n.address);
                        }
                        updateCounter.set(0);
                        incarnationCounter.incrementAndGet();
                        deliverCallback.accept(msg);
                    }
                    readyList.clear();
                    return flushCount;
                }
            }

            return 0;
        }
        finally {
            rwLock.writeLock().unlock();
        }
    }
}