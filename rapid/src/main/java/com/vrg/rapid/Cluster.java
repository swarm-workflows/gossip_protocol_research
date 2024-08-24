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

import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.vrg.rapid.messaging.IMessagingClient;
import com.vrg.rapid.messaging.IMessagingServer;
import com.vrg.rapid.messaging.impl.GrpcClient;
import com.vrg.rapid.messaging.impl.GrpcServer;
import com.vrg.rapid.monitoring.IEdgeFailureDetectorFactory;
import com.vrg.rapid.monitoring.impl.PingPongFailureDetector;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.JoinStatusCode;
import com.vrg.rapid.pb.Metadata;
import com.vrg.rapid.pb.NodeId;
import com.vrg.rapid.pb.PreJoinMessage;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import io.grpc.ExperimentalApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vrg.rapid.pb.Endpoint;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;


/**
 * The public API for Rapid. Users create Cluster objects using either Cluster.start()
 * or Cluster.join(), depending on whether the user is starting a new cluster or not:
 *
 * <pre>
 * {@code
 *   Endpoint seedAddress = Endpoint.hostFromString("127.0.0.1", 1234);
 *   Cluster c = Cluster.Builder(seedAddress).start();
 *   ...
 *   Endpoint joinerAddress = Endpoint.hostFromString("127.0.0.1", 1235);
 *   Cluster c = Cluster.Builder(joinerAddress).join(seedAddress);
 * }
 * </pre>
 *
 * The API does not yet support a node, as identified by a hostname and port, to
 * be part of multiple separate clusters.
 */
public final class Cluster {
    private static final Logger LOG = LoggerFactory.getLogger(Cluster.class);
    private static final int K = 10;
    private static final int H = 9;
    private static final int L = 4;
    private static final int RETRIES = 5;
    private final MembershipService membershipService;
    private final IMessagingServer rpcServer;
    private final SharedResources sharedResources;
    private final Endpoint listenAddress;
    private final Endpoint publicAddress;
    private boolean hasShutdown = false;

    private Cluster(final IMessagingServer rpcServer,
                    final MembershipService membershipService,
                    final SharedResources sharedResources,
                    final Endpoint listenAddress,
                    final Endpoint publicAddress) {
        this.membershipService = membershipService;
        this.rpcServer = rpcServer;
        this.sharedResources = sharedResources;
        this.listenAddress = listenAddress;
        this.publicAddress = publicAddress;
    }

    /**
     * Returns the list of endpoints currently in the membership set.
     *
     * @return list of endpoints in the membership set
     * @throws IllegalStateException when trying to get the memberlist after shutting down
     */
    public List<Endpoint> getMemberlist() {
        if (hasShutdown) {
            throw new IllegalStateException("Can't access the memberlist after having shut down");
        }
        return membershipService.getMembershipView();
    }

    /**
     * Returns the number of endpoints currently in the membership set.
     *
     * @return the number of endpoints in the membership set
     * @throws IllegalStateException when trying to get the membership size after shutting down
     */
    public int getMembershipSize() {
        if (hasShutdown) {
            throw new IllegalStateException("Can't access the memberlist after having shut down");
        }
        return membershipService.getMembershipSize();
    }

    /**
     * Returns the list of endpoints currently in the membership set.
     *
     * @return list of endpoints in the membership set
     * @throws IllegalStateException when trying to get the cluster metadata after shutting down
     */
    public Map<Endpoint, Metadata> getClusterMetadata() {
        if (hasShutdown) {
            throw new IllegalStateException("Can't access the memberlist after having shut down");
        }
        return membershipService.getMetadata();
    }

    /**
     * Register callbacks for cluster events.
     *
     * @param event Cluster event to subscribe to
     * @param callback Callback to be executed when {@code event} occurs.
     */
    public void registerSubscription(final ClusterEvents event,
                                     final Consumer<ClusterStatusChange> callback) {
        membershipService.registerSubscription(event, callback);
    }

    /**
     * Gracefully leaves the cluster by informing observers of the intent and then shuts down the entire system
     */
    public void leaveGracefully() {
        LOG.debug("Leaving the membership group and shutting down");
        membershipService.leave();
        shutdown();
    }

    /**
     * Shuts down the entire system
     */
    public void shutdown() {
        LOG.debug("Shutting down RpcServer and MembershipService");
        rpcServer.shutdown();
        membershipService.shutdown();
        sharedResources.shutdown();
        this.hasShutdown = true;
    }

    public static class Builder {
        private final Endpoint listenAddress;
        private final Endpoint publicAddress;
        @Nullable private IEdgeFailureDetectorFactory edgeFailureDetector = null;
        private Metadata metadata = Metadata.getDefaultInstance();
        private Settings settings = new Settings();
        private final Map<ClusterEvents, List<Consumer<ClusterStatusChange>>> subscriptions =
                new EnumMap<>(ClusterEvents.class);

        // These fields are initialized at the beginning of start() and join()
        @Nullable private IMessagingClient messagingClient = null;
        @Nullable private IMessagingServer messagingServer = null;
        @Nullable private SharedResources sharedResources = null;

        /**
         * Instantiates a builder for a Rapid Cluster node that will listen on the given {@code listenAddress}
         *
         * @param listenAddress The listen address of the node being instantiated
         */
        public Builder(final HostAndPort listenAddress, final HostAndPort publicAddress) {
            this.listenAddress = Endpoint.newBuilder()
                    .setHostname(ByteString.copyFromUtf8(listenAddress.getHost()))
                    .setPort(listenAddress.getPort())
                    .build();
            this.publicAddress = Endpoint.newBuilder()
                    .setHostname(ByteString.copyFromUtf8(publicAddress.getHost()))
                    .setPort(publicAddress.getPort())
                    .build();
        }

        /**
         * Instantiates a builder for a Rapid Cluster node that will listen on the given {@code listenAddress}
         *
         * @param listenAddress The listen address of the node being instantiated
         */
        Builder(final Endpoint listenAddress, final Endpoint publicAddress) {
            this.listenAddress = listenAddress;
            this.publicAddress = publicAddress;
        }

        /**
         * Set static application-specific metadata that is associated with the node being instantiated.
         * This may include tags like "role":"frontend".
         *
         * @param metadata A map specifying a set of key-value tags.
         */
        @ExperimentalApi
        public Builder setMetadata(final Map<String, ByteString> metadata) {
            Objects.requireNonNull(metadata);
            this.metadata = Metadata.newBuilder().putAllMetadata(metadata).build();
            return this;
        }

        /**
         * Set a link failure detector to use for observers to watch their subjects.
         *
         * @param edgeFailureDetector A link failure detector used as input for Rapid's failure detection.
         */
        @ExperimentalApi
        public Builder setEdgeFailureDetectorFactory(final IEdgeFailureDetectorFactory edgeFailureDetector) {
            Objects.requireNonNull(edgeFailureDetector);
            this.edgeFailureDetector = edgeFailureDetector;
            return this;
        }

        /**
         * This is used to register subscriptions for different events
         */
        @ExperimentalApi
        public Builder addSubscription(final ClusterEvents event,
                                       final Consumer<ClusterStatusChange> callback) {
            this.subscriptions.computeIfAbsent(event, k -> new ArrayList<>());
            this.subscriptions.get(event).add(callback);
            return this;
        }

        /**
         * This is used to configure Cluster properties such as timeouts
         */
        public Builder useSettings(final Settings settings) {
            this.settings = settings;
            return this;
        }

        /**
         * Supply the messaging client and server to use.
         */
        public Builder setMessagingClientAndServer(final IMessagingClient messagingClient,
                                                   final IMessagingServer messagingServer) {
            this.messagingClient = messagingClient;
            this.messagingServer = messagingServer;
            return this;
        }

        /**
         * Start a cluster without joining. Required to bootstrap a seed node.
         *
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster start() throws IOException {
            Objects.requireNonNull(listenAddress);
            sharedResources = new SharedResources(listenAddress);
            messagingServer = messagingServer != null
                            ? messagingServer
                            : new GrpcServer(listenAddress, sharedResources, settings.getUseInProcessTransport());
            messagingClient = messagingClient != null
                                ? messagingClient
                                : new GrpcClient(listenAddress, sharedResources, settings);
            final NodeId currentIdentifier = Utils.nodeIdFromUUID(UUID.randomUUID());
            final MembershipView membershipView = new MembershipView(K, Collections.singletonList(currentIdentifier),
                    Collections.singletonList(publicAddress));
            final MultiNodeCutDetector cutDetector = new MultiNodeCutDetector(K, H, L);
            edgeFailureDetector = edgeFailureDetector != null ? edgeFailureDetector
                    : new PingPongFailureDetector.Factory(listenAddress, messagingClient);

            final Map<Endpoint, Metadata> metadataMap = metadata.getMetadataCount() > 0
                                                    ? Collections.singletonMap(publicAddress, metadata)
                                                    : Collections.emptyMap();
            final MembershipService membershipService = new MembershipService(publicAddress,
                    cutDetector, membershipView, sharedResources, settings,
                                            messagingClient, edgeFailureDetector, metadataMap, subscriptions);
            messagingServer.setMembershipService(membershipService);
            messagingServer.start();
            return new Cluster(messagingServer, membershipService, sharedResources, listenAddress, publicAddress);
        }


        /**
         * Joins an existing cluster, using {@code seedAddress} to bootstrap.
         *
         * @param seedHostAndPort Seed node for the bootstrap protocol
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster join(final HostAndPort seedHostAndPort) throws IOException, InterruptedException {
            final Endpoint seedAddress = Endpoint.newBuilder()
                    .setHostname(ByteString.copyFromUtf8(seedHostAndPort.getHost()))
                    .setPort(seedHostAndPort.getPort())
                    .build();
            return join(seedAddress);
        }

        /**
         * Joins an existing cluster, using {@code seedAddress} to bootstrap.
         *
         * @param seedAddress Seed node for the bootstrap protocol
         * @throws IOException Thrown if we cannot successfully start a server
         */
        Cluster join(final Endpoint seedAddress) throws IOException, InterruptedException {
            NodeId currentIdentifier = Utils.nodeIdFromUUID(UUID.randomUUID());
            sharedResources = new SharedResources(listenAddress);
            messagingServer = messagingServer != null
                    ? messagingServer
                    : new GrpcServer(listenAddress, sharedResources, settings.getUseInProcessTransport());
            messagingClient = messagingClient != null
                    ? messagingClient
                    : new GrpcClient(listenAddress, sharedResources, settings);
            messagingServer.start();
            for (int attempt = 0; attempt < RETRIES; attempt++) {
                try {
                    return joinAttempt(seedAddress, currentIdentifier, attempt);
                } catch (final ExecutionException | JoinPhaseTwoException e) {
                    LOG.error("Join message to seed {} returned an exception: {}", Utils.loggable(seedAddress), e);
                } catch (final JoinPhaseOneException e) {
                    /*
                     * These are error responses from a seed node that warrant a retry.
                     */
                    final JoinResponse result = e.getJoinPhaseOneResult();
                    switch (result.getStatusCode()) {
                        case CONFIG_CHANGED:
                            LOG.error("CONFIG_CHANGED received from {}. Retrying.", Utils.loggable(result.getSender()));
                            break;
                        case UUID_ALREADY_IN_RING:
                            LOG.error("UUID_ALREADY_IN_RING received from {}. Retrying.",
                                    Utils.loggable(result.getSender()));
                            currentIdentifier = Utils.nodeIdFromUUID(UUID.randomUUID());
                            break;
                        case MEMBERSHIP_REJECTED:
                            LOG.error("Membership rejected by {}. Retrying.", Utils.loggable(result.getSender()));
                            break;
                        default:
                            throw new JoinException("Unrecognized status code");
                    }
                }
            }
            messagingServer.shutdown();
            messagingClient.shutdown();
            sharedResources.shutdown();
            throw new JoinException("Join attempt unsuccessful " + Utils.loggable(publicAddress));
        }

        /**
         * A single attempt by a node to join a cluster. This includes phase one, where it contacts
         * a seed node to receive a list of observers to contact and the configuration to join. If successful,
         * it triggers phase two where it contacts those observers who then vouch for the joiner's admission
         * into the cluster.
         */
        private Cluster joinAttempt(final Endpoint seedAddress, final NodeId currentIdentifier, final int attempt)
                                                                throws ExecutionException, InterruptedException {
            assert messagingClient != null;
            final RapidRequest preJoinMessage = Utils.toRapidRequest(PreJoinMessage.newBuilder()
                                                                            .setSender(publicAddress)
                                                                            .setNodeId(currentIdentifier)
                                                                            .build());
            final JoinResponse joinPhaseOneResult = messagingClient.sendMessage(seedAddress, preJoinMessage)
                                                                   .get()
                                                                   .getJoinResponse();

            /*
             * Either the seed node indicates it is safe to join, or it indicates that we're already
             * part of the configuration (which happens due to a race condition where we retry a join
             * after a timeout while the cluster has added us -- see below).
             */
            LOG.info("seedAddress:{}", seedAddress);
            if (joinPhaseOneResult.getStatusCode() != JoinStatusCode.SAFE_TO_JOIN
                    && joinPhaseOneResult.getStatusCode() != JoinStatusCode.HOSTNAME_ALREADY_IN_RING) {
                LOG.info("Unsafe!");
                throw new JoinPhaseOneException(joinPhaseOneResult);
            }
            else {
                LOG.info("Safe!");
            }
            LOG.info("seedAddress:{}", seedAddress);
            /*
             * HOSTNAME_ALREADY_IN_RING is a special case. If the joinPhase2 request times out before
             * the join confirmation arrives from an observer, a client may re-try a join by contacting
             * the seed and get this response. It should simply get the configuration streamed to it.
             * To do that, that client tries the join protocol but with a configuration id of -1.
             */
            final long configurationToJoin = joinPhaseOneResult.getStatusCode()
                    == JoinStatusCode.HOSTNAME_ALREADY_IN_RING ? -1 : joinPhaseOneResult.getConfigurationId();
            LOG.info("{} is trying a join under configuration {} (attempt {})",
                      Utils.loggable(publicAddress), configurationToJoin, attempt);

            /*
             * Phase one complete. Now send a phase two message to all our observers, and if there is a valid
             * response, construct a Cluster object based on it.
             */
            final Optional<JoinResponse> response = sendJoinPhase2Messages(joinPhaseOneResult,
                    configurationToJoin, currentIdentifier)
                    .stream()
                    .filter(Objects::nonNull)
                    .map(RapidResponse::getJoinResponse)
                    .filter(r -> r.getStatusCode() == JoinStatusCode.SAFE_TO_JOIN)
                    .filter(r -> r.getConfigurationId() != configurationToJoin)
                    .findFirst();
            if (response.isPresent()) {
                return createClusterFromJoinResponse(response.get());
            }
            throw new JoinPhaseTwoException();
        }

        /**
         * Identifies the set of observers to reach out to from the phase one message, and sends a join phase 2 message.
         */
        private List<RapidResponse> sendJoinPhase2Messages(final JoinResponse joinPhaseOneResult,
                                        final long configurationToJoin, final NodeId currentIdentifier)
                                                            throws ExecutionException, InterruptedException {
            assert messagingClient != null;
            // We have the list of observers. Now contact them as part of phase 2.
            final List<Endpoint> observerList = joinPhaseOneResult.getEndpointsList();
            final Map<Endpoint, List<Integer>> ringNumbersPerObserver = new HashMap<>(K);

            // Batch together requests to the same node.
            int ringNumber = 0;
            for (final Endpoint observer: observerList) {
                ringNumbersPerObserver.computeIfAbsent(observer, k -> new ArrayList<>()).add(ringNumber);
                ringNumber++;
            }

            final List<ListenableFuture<RapidResponse>> responseFutures = new ArrayList<>();
            for (final Map.Entry<Endpoint, List<Integer>> entry: ringNumbersPerObserver.entrySet()) {
                final JoinMessage msg = JoinMessage.newBuilder()
                        .setSender(publicAddress)
                        .setNodeId(currentIdentifier)
                        .setMetadata(metadata)
                        .setConfigurationId(configurationToJoin)
                        .addAllRingNumber(entry.getValue()).build();
                final RapidRequest request = Utils.toRapidRequest(msg);
                LOG.info("{} is sending a join-p2 to {} for config {}",
                        Utils.loggable(publicAddress), Utils.loggable(entry.getKey()),
                        configurationToJoin);
                final ListenableFuture<RapidResponse> call = messagingClient.sendMessage(entry.getKey(), request);
                responseFutures.add(call);
            }
            return Futures.successfulAsList(responseFutures).get();
        }

        /**
         * We have a valid JoinPhase2Response. Use the retrieved configuration to construct and return a Cluster object.
         */
        private Cluster createClusterFromJoinResponse(final JoinResponse response) {
            assert messagingClient != null && messagingServer != null && sharedResources != null;
            // Safe to proceed. Extract the list of endpoints and identifiers from the message,
            // assemble a MembershipService object and start an RpcServer.
            final List<Endpoint> allEndpoints = response.getEndpointsList();
            final List<NodeId> identifiersSeen = response.getIdentifiersList();
            final Map<Endpoint, Metadata> allMetadata = new HashMap<>();
            for (int i = 0; i < response.getMetadataKeysCount(); i++) {
                final Endpoint key = response.getMetadataKeys(i);
                final Metadata value = response.getMetadataValues(i);
                allMetadata.put(key, value);
            }

            assert !identifiersSeen.isEmpty();
            assert !allEndpoints.isEmpty();

            final MembershipView membershipViewFinal =
                    new MembershipView(K, identifiersSeen, allEndpoints);
            final MultiNodeCutDetector cutDetector = new MultiNodeCutDetector(K, H, L);
            edgeFailureDetector = edgeFailureDetector != null ? edgeFailureDetector
                                                  : new PingPongFailureDetector.Factory(publicAddress, messagingClient);
            final MembershipService membershipService =
                    new MembershipService(publicAddress, cutDetector, membershipViewFinal,
                           sharedResources, settings, messagingClient, edgeFailureDetector, allMetadata, subscriptions);
            messagingServer.setMembershipService(membershipService);
            if (LOG.isTraceEnabled()) {
                LOG.trace("{} has observers {}", publicAddress,
                        membershipViewFinal.getObserversOf(publicAddress));
                LOG.trace("{} has subjects {}", publicAddress,
                        membershipViewFinal.getObserversOf(publicAddress));
            }
            return new Cluster(messagingServer, membershipService, sharedResources, listenAddress, publicAddress);
        }
    }


    @Override
    public String toString() {
        return "Cluster:" + listenAddress.getHostname().toStringUtf8() + ":" + listenAddress.getPort()
         + publicAddress.getHostname().toStringUtf8() + ":" + listenAddress.getPort();
    }

    public static final class JoinException extends RuntimeException {
        JoinException(final String msg) {
            super(msg);
        }
    }

    static final class JoinPhaseOneException extends RuntimeException {
        final JoinResponse joinPhaseOneResult;

        JoinPhaseOneException(final JoinResponse joinPhaseOneResult) {
            this.joinPhaseOneResult = joinPhaseOneResult;
        }

        private JoinResponse getJoinPhaseOneResult() {
            return joinPhaseOneResult;
        }
    }

    static final class JoinPhaseTwoException extends RuntimeException {
    }
}
