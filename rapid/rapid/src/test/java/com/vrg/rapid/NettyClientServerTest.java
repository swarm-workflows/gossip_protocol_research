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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.vrg.rapid.messaging.impl.NettyClientServer;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.ProbeMessage;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NettyClientServerTest {

    /**
     * Tests NettyClientServer messaging from many clients to one server
     */
    @Test
    public void sendMessageNetty() throws IOException, InterruptedException, ExecutionException {
        Cluster serverInstance = null;
        try {
            final int numClients = 100;
            final Endpoint server = Endpoint.newBuilder().setHostname(ByteString.copyFromUtf8("127.0.0.1"))
                    .setPort(9000).build();
            final SharedResources resources = new SharedResources(server);
            final NettyClientServer serverMessaging = new NettyClientServer(server, resources);
            serverInstance = new Cluster.Builder(server)
                    .setMessagingClientAndServer(serverMessaging, serverMessaging)
                    .start();
            assertNotNull(serverInstance);
            final SharedResources shared = new SharedResources(Endpoint.getDefaultInstance());
            final List<NettyClientServer> ncs = new ArrayList<>();
            for (int i = 0; i < numClients; i++) {
                final Endpoint clientEp = Endpoint.newBuilder()
                        .setHostname(ByteString.copyFromUtf8("127.0.0.1"))
                        .setPort(9002 + i).build();
                ncs.add(new NettyClientServer(clientEp, shared));
            }

            final List<ListenableFuture<RapidResponse>> futures = new ArrayList<>();
            final RapidRequest messageToSend = RapidRequest.newBuilder()
                    .setProbeMessage(ProbeMessage.getDefaultInstance())
                    .build();
            for (final NettyClientServer nc : ncs) {
                futures.add(nc.sendMessage(server, messageToSend));
            }
            final List<RapidResponse> responses = Futures.allAsList(futures).get();
            assertNotNull(responses);
            assertEquals(responses.size(), numClients);
        } finally {
            if (serverInstance != null) {
                serverInstance.shutdown();
            }
        }
    }

    /**
     * Tests NettyClientServer messaging from one client to many servers
     */
    @Test
    @SuppressWarnings("all")
    public void sendMessageNettyMultipleServers() throws IOException, InterruptedException, ExecutionException {
        final List<Cluster> clusters = new ArrayList<>(10);
        try {
            final int numServers = 10;
            final SharedResources resources = new SharedResources(Endpoint.getDefaultInstance());

            for (int i = 0; i < numServers; i++) {
                final Endpoint server = Endpoint.newBuilder().setHostname(ByteString.copyFromUtf8("127.0.0.1"))
                        .setPort(9001 + i).build();
                final NettyClientServer serverMessaging = new NettyClientServer(server, resources);
                final Cluster cluster = new Cluster.Builder(server)
                        .setMessagingClientAndServer(serverMessaging, serverMessaging)
                        .start();
                clusters.add(cluster);
            }
            final SharedResources resources2 = new SharedResources(Endpoint.getDefaultInstance());

            final Endpoint clientEp = Endpoint.newBuilder().setHostname(ByteString.copyFromUtf8("127.0.0.1"))
                    .setPort(9000).build();
            final NettyClientServer clientMessaging = new NettyClientServer(clientEp, resources2);
            for (int i = 0; i < numServers; i++) {
                final Endpoint server = Endpoint.newBuilder().setHostname(ByteString.copyFromUtf8("127.0.0.1"))
                        .setPort(9001 + i).build();
                final RapidRequest msg = RapidRequest.newBuilder().setProbeMessage(ProbeMessage.getDefaultInstance())
                        .build();
                final RapidResponse rapidResponse1 = clientMessaging.sendMessage(server, msg).get();
                assertNotNull(rapidResponse1);
                final RapidResponse rapidResponse2 = clientMessaging.sendMessage(server, msg).get();
                assertNotNull(rapidResponse2);
                final RapidResponse rapidResponse3 = clientMessaging.sendMessage(server, msg).get();
                assertNotNull(rapidResponse3);
            }
        } finally {
            clusters.forEach(Cluster::shutdown);
        }
    }
}
