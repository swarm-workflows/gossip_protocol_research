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

import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.MessageId;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.vrg.rapid.messaging.IBroadcaster;
import com.vrg.rapid.messaging.IMessagingClient;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;


/**
 * Simple best-effort broadcaster.
 */
final class UnicastToAllBroadcaster implements IBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(UnicastToAllBroadcaster.class);
    private final IMessagingClient messagingClient;
    private List<Endpoint> recipients = Collections.emptyList();
    private List<Endpoint> fullMembership = Collections.emptyList();
    private List<Endpoint> difference = Collections.emptyList();

    UnicastToAllBroadcaster(final IMessagingClient messagingClient) {
        this.messagingClient = messagingClient;
    }

    @Override
    @CanIgnoreReturnValue
    public synchronized List<ListenableFuture<RapidResponse>> broadcast(final RapidRequest msg) {
        final List<ListenableFuture<RapidResponse>> futures = new ArrayList<>(recipients.size());
        LOG.trace("unicastToAll.broadcast " + messagingClient.getAddress() + "size=" + recipients.size());
        // List<Endpoint> noResponseEndpoints = messagingClient.getLatencyMap().keySet().stream()
        // .filter(e -> messagingClient.getLatencyMap().
        // getOrDefault(e, -1L) == -1L) // Filter available endpoints
        // .collect(Collectors.toList());
        List<Endpoint> availableEndpoints = new ArrayList<>(fullMembership);
        // List<Endpoint> availableEndpoints = fullMembership;
        // for (Endpoint endpoint : recipients) {
        //     if (!noResponseEndpoints.contains(endpoint)) {
        //         availableEndpoints.add(endpoint);
        //     }
        // }
        // recipients = availableEndpoints;
        // availableEndpoints = new ArrayList<>();
        // for (Endpoint endpoint : fullMembership) {
        //     if (!noResponseEndpoints.contains(endpoint)) {
        //         availableEndpoints.add(endpoint);
        //     }
        // }
        // Random random = new Random(messagingClient.getAddress().getPort());
        Collections.shuffle(availableEndpoints);
        int count = 0;
        // for (final Endpoint recipient: availableEndpoints) {
        for (final Endpoint recipient: recipients) {
            // if (noResponseEndpoints.contains(recipient)) {
            //     continue;
            // }
            // if(count == 3) break;
            Endpoint target = recipient;
            messagingClient.sendMessageBestEffort(target, msg);
            count++;
        }
        for (final Endpoint recipient: availableEndpoints) {
            if(count == 6) break;
            // if (noResponseEndpoints.contains(recipient)) {
            //     continue;
            // }
            Endpoint target = recipient;
            messagingClient.sendMessageBestEffort(target, msg);
            count++;
        }
        return futures;
    }

    @Override
    public synchronized void setMembership(final List<Endpoint> recipients, final List<Endpoint> fullMembership) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("setMembership {}", Utils.loggable(recipients));
        }
        // Randomize the sequence of nodes that will receive a broadcast from this node for each configuration
        final List<Endpoint> arr = new ArrayList<>(recipients);
        Collections.shuffle(arr, ThreadLocalRandom.current());
        this.recipients = arr;
        this.fullMembership = new ArrayList<>(fullMembership);
        
        // this.recipients.remove(messagingClient.getAddress());
        // this.fullMembership.remove(messagingClient.getAddress());

        // HashSet<Endpoint> recipientSet = new HashSet<>(recipients);

        // 筛选出 fullMembership 中不在 recipients 中的元素
        // this.difference = new ArrayList<>();
        // for (Endpoint endpoint : fullMembership) {
        //     if (!recipientSet.contains(endpoint)) {
        //         difference.add(endpoint);
        //     }
        // }
    }
}