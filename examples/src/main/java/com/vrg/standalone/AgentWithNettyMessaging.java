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

package com.vrg.standalone;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.vrg.rapid.Cluster;
import com.vrg.rapid.messaging.impl.NettyClientServer;
import com.vrg.rapid.pb.Endpoint;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * An example to demonstrate how to plugin a custom messaging implementation to Rapid.
 *
 */
public class AgentWithNettyMessaging extends StandaloneAgent {
    private static final Logger LOG = LoggerFactory.getLogger(AgentWithNettyMessaging.class);
    private static final int SLEEP_INTERVAL_MS = 1000;
    private static final int MAX_TRIES = 400;
    @Nullable private Cluster cluster = null;

    private AgentWithNettyMessaging(final HostAndPort listenAddress, final HostAndPort publicAddress, 
                                final HostAndPort seedAddress) {
        super(listenAddress, publicAddress, seedAddress);
    }

    @Override
    public void startCluster() throws IOException, InterruptedException {
        final Endpoint endpoint = Endpoint.newBuilder()
                                          .setHostname(ByteString.copyFromUtf8(listenAddress.getHost()))
                                          .setPort(listenAddress.getPort()).build();

        // To use your own messaging implementation with Rapid, supply an instance each of IMessagingClient
        // and IMessagingServer to Cluster.Builder.setMessagingClientServer().
        //
        // In this example, we use an object NettyClientServer which implements both the IMessagingClient
        // and IMessagingServer interfaces.
        final NettyClientServer nettyMessaging = new NettyClientServer(endpoint);
        if (listenAddress.equals(seedAddress)) {
            cluster = new Cluster.Builder(listenAddress, publicAddress)
                    .setMessagingClientAndServer(nettyMessaging, nettyMessaging)
                    .start();

        } else {
            cluster = new Cluster.Builder(listenAddress, publicAddress)
                    .setMessagingClientAndServer(nettyMessaging, nettyMessaging)
                    .join(seedAddress);
        }
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE_PROPOSAL,
                this::onViewChangeProposal);
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE,
                this::onViewChange);
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.KICKED,
                this::onKicked);
    }

    /**
     * Prints the current membership
     */
    private void printClusterMembership() {
        LOG.info("Node {} -- cluster size {}", listenAddress, cluster.getMembershipSize());
    }

    public static void main(final String[] args) throws ParseException {
        final Options options = new Options();
        options.addRequiredOption("l", "listenAddress", true, "The listening addresses Rapid Cluster instances");
        options.addRequiredOption("s", "seedAddress", true, "The seed node's address for the bootstrap protocol");
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd = parser.parse(options, args);

        // Get CLI options
        final HostAndPort listenAddress = HostAndPort.fromString(cmd.getOptionValue("listenAddress"));
        final HostAndPort publicAddress = HostAndPort.fromString(cmd.getOptionValue("publicAddress"));
        final HostAndPort seedAddress = HostAndPort.fromString(cmd.getOptionValue("seedAddress"));

        // Bring up Rapid node
        try {
            final AgentWithNettyMessaging agent = new AgentWithNettyMessaging(listenAddress, publicAddress,
                                                                                seedAddress);
            agent.startCluster();
            for (int i = 0; i < MAX_TRIES; i++) {
                agent.printClusterMembership();
                Thread.sleep(SLEEP_INTERVAL_MS);
            }
        } catch (final IOException | InterruptedException e) {
            LOG.error("Exception thrown by StandaloneAgent {}", e);
            Thread.currentThread().interrupt();
        }
    }
}