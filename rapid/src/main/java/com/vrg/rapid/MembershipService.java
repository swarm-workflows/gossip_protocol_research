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
import java.util.concurrent.ThreadLocalRandom;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.vrg.rapid.messaging.IBroadcaster;
import com.vrg.rapid.messaging.IMessagingClient;
import com.vrg.rapid.monitoring.IEdgeFailureDetectorFactory;
import com.vrg.rapid.pb.AlertMessage;
import com.vrg.rapid.pb.BatchedAlertMessage;
import com.vrg.rapid.pb.EdgeStatus;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.JoinStatusCode;
import com.vrg.rapid.pb.LatencyMessage;
import com.vrg.rapid.pb.LeaveMessage;
import com.vrg.rapid.pb.Metadata;
import com.vrg.rapid.pb.NodeId;
import com.vrg.rapid.pb.PreJoinMessage;
import com.vrg.rapid.pb.ProbeMessage;
import com.vrg.rapid.pb.ProbeResponse;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Membership server class that implements the Rapid protocol.
 *
 * Note: This class is not thread-safe yet. RpcServer.start() uses a single threaded messagingExecutor during the server
 * initialization to make sure that only a single thread runs the process* methods.
 *
 */
@NotThreadSafe
public final class MembershipService {
    private static final Logger LOG = LoggerFactory.getLogger(MembershipService.class);
    static final int BATCHING_WINDOW_IN_MS = 100;
    private static final int DEFAULT_FAILURE_DETECTOR_INITIAL_DELAY_IN_MS = 0;
    static final int DEFAULT_FAILURE_DETECTOR_INTERVAL_IN_MS = 2000;
    private static final int LEAVE_MESSAGE_TIMEOUT = 2500;
    public final MembershipView membershipView;
    private final MultiNodeCutDetector cutDetection;
    public final Endpoint myAddr;
    public final IBroadcaster broadcaster;
    private final Map<Endpoint, LinkedBlockingDeque<SettableFuture<RapidResponse>>> joinersToRespondTo =
            new HashMap<>();
    private final Map<Endpoint, NodeId> joinerUuid = new HashMap<>();
    private final Map<Endpoint, Metadata> joinerMetadata = new HashMap<>();
    public final IMessagingClient messagingClient;
    private final MetadataManager metadataManager;
    private final List<ScheduledFuture<?>> latencyProbeJobs = new ArrayList<>();
    private List<ScheduledFuture<?>> broadcastJobs = new ArrayList<>();
    private List<ScheduledFuture<?>> dgroJobs = new ArrayList<>();
    // Event subscriptions
    private final Map<ClusterEvents, List<Consumer<ClusterStatusChange>>> subscriptions;

    //
    private FastPaxos fastPaxosInstance;

    // Fields used by batching logic.
    @GuardedBy("batchSchedulerLock")
    private long lastEnqueueTimestamp = -1;    // Timestamp
    @GuardedBy("batchSchedulerLock")
    private final LinkedBlockingQueue<AlertMessage> sendQueue = new LinkedBlockingQueue<>();
    private final Lock batchSchedulerLock = new ReentrantLock();
    private final ScheduledExecutorService backgroundTasksExecutor;
    private final ScheduledExecutorService latencyExecutor;
    private final ScheduledExecutorService broadcastExecutor;
    private final ScheduledExecutorService dgroExecutor;
    private ScheduledFuture<?> alertBatcherJob;
    private final List<ScheduledFuture<?>> failureDetectorJobs;
    private final SharedResources sharedResources;

    // Failure detector
    private final IEdgeFailureDetectorFactory fdFactory;

    // Fields used by consensus protocol
    private boolean announcedProposal = false;
    private final Object membershipUpdateLock = new Object();
    private final Settings settings;


    MembershipService(final Endpoint myAddr, final MultiNodeCutDetector cutDetection,
                      final MembershipView membershipView, final SharedResources sharedResources,
                      final Settings settings, final IMessagingClient messagingClient,
                      final IEdgeFailureDetectorFactory edgeFailureDetector) {
        this(myAddr, cutDetection, membershipView, sharedResources, settings, messagingClient,
             edgeFailureDetector, Collections.emptyMap(), new EnumMap<>(ClusterEvents.class));
    }

    MembershipService(final Endpoint myAddr, final MultiNodeCutDetector cutDetection,
                      final MembershipView membershipView, final SharedResources sharedResources,
                      final Settings settings, final IMessagingClient messagingClient,
                      final IEdgeFailureDetectorFactory edgeFailureDetector, final Map<Endpoint, Metadata> metadataMap,
                      final Map<ClusterEvents, List<Consumer<ClusterStatusChange>>> subscriptions) {
        this.myAddr = myAddr;
        this.settings = settings;
        this.membershipView = membershipView;
        this.cutDetection = cutDetection;
        this.sharedResources = sharedResources;
        this.metadataManager = new MetadataManager();
        this.metadataManager.addMetadata(metadataMap);
        this.messagingClient = messagingClient;
        this.broadcaster = new UnicastToAllBroadcaster(messagingClient);
        this.subscriptions = subscriptions;
        this.fdFactory = edgeFailureDetector;

        // Make sure there is an empty list for every enum type
        Arrays.stream(ClusterEvents.values()).forEach(event ->
                this.subscriptions.computeIfAbsent(event, k -> new ArrayList<>(0)));

        // Schedule background jobs
        this.backgroundTasksExecutor = sharedResources.getScheduledTasksExecutor();
        this.latencyExecutor = sharedResources.getScheduledTasksExecutor();
        this.broadcastExecutor = sharedResources.getScheduledTasksExecutor();
        this.dgroExecutor = sharedResources.getScheduledTasksExecutor();
        alertBatcherJob = this.backgroundTasksExecutor.scheduleAtFixedRate(new AlertBatcher(),
                0, settings.getBatchingWindowInMs(), TimeUnit.MILLISECONDS);

        // this.broadcaster.setMembership(membershipView.getRing(0));
        List<Endpoint> subjects = membershipView.getGossipOutOf(myAddr);
        // if(subjects.size() == 0)System.out.println("MembershipService constructor: subjects size is 0");
        // List<Endpoint> subjects = membershipView.getSubjectsOf(myAddr);

        if (subjects.isEmpty()) {
            subjects = new ArrayList<>(); // Create a mutable list
            subjects.add(myAddr); // Add myAddr to the list
        }
        
        this.broadcaster.setMembership(subjects, getMembershipView());
        // this::edgeFailureNotification is invoked by the failure detector whenever an edge
        // to an observer is marked faulty.
        this.failureDetectorJobs = new ArrayList<>();

        // Prepare consensus instance
        this.fastPaxosInstance = new FastPaxos(myAddr, membershipView.getCurrentConfigurationId(),
                                               membershipView.getMembershipSize(), this.messagingClient,
                                               this.broadcaster, this.backgroundTasksExecutor, this::decideViewChange,
                this.settings);
        createFailureDetectorsForCurrentConfiguration();
        createLatencyBroadcasters();
        createLatencyProbesForCurrentConfiguration();
        createDGRO();

        // Execute all VIEW_CHANGE callbacks. This informs applications that a start/join has successfully completed.
        final long configurationId = membershipView.getCurrentConfigurationId();
        final List<Endpoint> currentMembership = membershipView.getRing(0);
        final List<NodeStatusChange> nodeStatusChanges = getInitialViewChange();
        final ClusterStatusChange clusterStatusChange = new ClusterStatusChange(configurationId, currentMembership,
                                                                                nodeStatusChanges);
        subscriptions.get(ClusterEvents.VIEW_CHANGE).forEach(cb -> cb.accept(clusterStatusChange));
    }

    /**
     * Entry point for all messages.
     */
    public ListenableFuture<RapidResponse> handleMessage(final RapidRequest msg) {
        switch (msg.getContentCase()) {
            case PREJOINMESSAGE:
                return handleMessage(msg.getPreJoinMessage());
            case JOINMESSAGE:
                return handleMessage(msg.getJoinMessage());
            case BATCHEDALERTMESSAGE:
                return handleMessage(msg.getBatchedAlertMessage());
            case PROBEMESSAGE:
                return handleMessage(msg.getProbeMessage());
            case FASTROUNDPHASE2BMESSAGE:
            case PHASE1AMESSAGE:
            case PHASE1BMESSAGE:
            case PHASE2AMESSAGE:
            case PHASE2BMESSAGE:
                // System.out.println("Server receives PHASE2BMESSAGE: " + System.currentTimeMillis()  + ", Endpoint: " + myAddr); 
                return handleConsensusMessages(msg);
            case LEAVEMESSAGE:
                return handleLeaveMessage(msg);
            case LATENCYMESSAGE:
                return handleMessage(msg.getLatencyMessage());
            case CONTENT_NOT_SET:
            default:
                throw new IllegalArgumentException("Unidentified RapidRequest type " + msg.getContentCase());
        }
    }

    /**
     * This is invoked by a new node joining the network at a seed node.
     * The seed responds with the current configuration ID and a list of observers
     * for the joiner, who then moves on to phase 2 of the protocol with its observers.
     */
    private ListenableFuture<RapidResponse> handleMessage(final PreJoinMessage msg) {
        final SettableFuture<RapidResponse> future = SettableFuture.create();

        sharedResources.getProtocolExecutor().execute(() -> {
            final Endpoint joiningEndpoint = msg.getSender();
            // System.out.println("Get PreJoinMessage from " + joiningEndpoint); 
            final JoinStatusCode statusCode = membershipView.isSafeToJoin(joiningEndpoint, msg.getNodeId());
            final JoinResponse.Builder builder = JoinResponse.newBuilder()
                    .setSender(myAddr)
                    .setConfigurationId(membershipView.getCurrentConfigurationId())
                    .setStatusCode(statusCode);
            LOG.info("Join at seed for {seed:{}, sender:{}, config:{}, size:{}}",
                    Utils.loggable(myAddr), Utils.loggable(msg.getSender()),
                    membershipView.getCurrentConfigurationId(), membershipView.getMembershipSize());
            if (statusCode.equals(JoinStatusCode.SAFE_TO_JOIN)
                    || statusCode.equals(JoinStatusCode.HOSTNAME_ALREADY_IN_RING)) {
                // Return a list of observers for the joiner to contact for phase 2 of the protocol
                builder.addAllEndpoints(membershipView.getExpectedObserversOf(joiningEndpoint));
            }
            future.set(Utils.toRapidResponse(builder.build()));
        });
        return future;
    }

    /**
     * Invoked by gatekeepers of a joining node. They perform any failure checking
     * required before propagating a AlertMessage with the status UP. After the cut detection
     * and full agreement succeeds, the observer informs the joiner about the new configuration it
     * is now a part of.
     */
    private ListenableFuture<RapidResponse> handleMessage(final JoinMessage joinMessage) {
        final SettableFuture<RapidResponse> future = SettableFuture.create();
        LOG.trace("getJoinMessage");
        
        sharedResources.getProtocolExecutor().execute(() -> {
            final long currentConfiguration = membershipView.getCurrentConfigurationId();
            if (currentConfiguration == joinMessage.getConfigurationId()) {
                LOG.trace("Enqueuing SAFE_TO_JOIN for {sender:{}, config:{}, size:{}}",
                        Utils.loggable(joinMessage.getSender()), currentConfiguration,
                        membershipView.getMembershipSize());

                joinersToRespondTo.computeIfAbsent(joinMessage.getSender(),
                        k -> new LinkedBlockingDeque<>()).add(future);

                final AlertMessage msg = AlertMessage.newBuilder()
                        .setEdgeSrc(myAddr)
                        .setEdgeDst(joinMessage.getSender())
                        .setEdgeStatus(EdgeStatus.UP)
                        .setConfigurationId(currentConfiguration)
                        .setNodeId(joinMessage.getNodeId())
                        .addAllRingNumber(joinMessage.getRingNumberList())
                        .setMetadata(joinMessage.getMetadata())
                        .build();
                LOG.trace("Call enqueueAlertMessage");
                enqueueAlertMessage(msg);
            } else {
                // This handles the corner case where the configuration changed between phase 1 and phase 2
                // of the joining node's bootstrap. It should attempt to rejoin the network.
                final MembershipView.Configuration configuration = membershipView.getConfiguration();
                LOG.info("Wrong configuration for {sender:{}, config:{}, myConfig:{}, size:{}}",
                        Utils.loggable(joinMessage.getSender()), joinMessage.getConfigurationId(),
                        currentConfiguration, membershipView.getMembershipSize());
                JoinResponse.Builder responseBuilder = JoinResponse.newBuilder()
                        .setSender(myAddr)
                        .setConfigurationId(configuration.getConfigurationId());
                if (membershipView.isHostPresent(joinMessage.getSender())
                        && membershipView.isIdentifierPresent(joinMessage.getNodeId())) {
                    LOG.info("Joining host already present : {sender:{}, config:{}, myConfig:{}, size:{}}",
                            Utils.loggable(joinMessage.getSender()), joinMessage.getConfigurationId(),
                            currentConfiguration, membershipView.getMembershipSize());
                    // Race condition where a observer already crossed H messages for the joiner and changed
                    // the configuration, but the JoinPhase2 messages show up at the observer
                    // after it has already added the joiner. In this case, we simply
                    // tell the sender that they're safe to join.
                    responseBuilder = responseBuilder.setStatusCode(JoinStatusCode.SAFE_TO_JOIN)
                            .addAllEndpoints(configuration.endpoints)
                            .addAllIdentifiers(configuration.nodeIds)
                            .addAllMetadataKeys(metadataManager.getAllMetadata().keySet())
                            .addAllMetadataValues(metadataManager.getAllMetadata().values());
                } else {
                    responseBuilder = responseBuilder.setStatusCode(JoinStatusCode.CONFIG_CHANGED);
                    LOG.info("Returning CONFIG_CHANGED for {sender:{}, config:{}, size:{}}",
                            Utils.loggable(joinMessage.getSender()), configuration.getConfigurationId(),
                            configuration.endpoints.size());
                }
                future.set(Utils.toRapidResponse(responseBuilder.build())); // new configuration
            }
        });
        LOG.trace("sharedResources.getProtocolExecutor!");
        return future;
    }


    /**
     * This method receives edge update events and delivers them to
     * the cut detector to check if it will return a valid
     * proposal.
     *
     * Edge update messages that do not affect an ongoing proposal
     * needs to be dropped.
     */
    private ListenableFuture<RapidResponse> handleMessage(final BatchedAlertMessage messageBatch) {
        Objects.requireNonNull(messageBatch);
        final SettableFuture<RapidResponse> future = SettableFuture.create();

        sharedResources.getProtocolExecutor().execute(() -> {
            // first, retain all valid alerts
            // this includes adding the UUIDs and metadata of joining nodes
            final long currentConfigurationId = membershipView.getCurrentConfigurationId();
            final int membershipSize = membershipView.getMembershipSize();
            final Stream<AlertMessage> validAlerts = messageBatch.getMessagesList().stream()
                // First, we filter out invalid messages that violate membership invariants.
                .filter(msg -> filterAlertMessages(messageBatch, msg, membershipSize, currentConfigurationId))
                // For valid UP alerts, extract the joiner details (UUID and metadata) which is going to be needed
                // when the node is added to the rings
                .map(this::extractJoinerUuidAndMetadata);

            // We already have a proposal for this round
            // => we have initiated consensus and cannot go back on our proposal.
            if (announcedProposal) {
                LOG.info("{} announcedProposal", myAddr.getPort());
                future.set(RapidResponse.getDefaultInstance());
            } else {
                // We now apply all the valid messages into our condition detector
                // to obtain a view change proposal
                final Set<Endpoint> proposal =
                        validAlerts
                                .map(cutDetection::aggregateForProposal)
                                .flatMap(List::stream)
                                .collect(Collectors.toSet());
                LOG.info("proposal size {}", proposal.size());
                // Lastly, we apply implicit detections
                proposal.addAll(cutDetection.invalidateFailingEdges(membershipView));

                // If we have a proposal for this stage, start an instance of consensus on it.
                if (!proposal.isEmpty()) {
                    LOG.info("Proposing membership change of size {}", proposal.size());
                    announcedProposal = true;

                    if (subscriptions.containsKey(ClusterEvents.VIEW_CHANGE_PROPOSAL)) {
                        final List<NodeStatusChange> result = createNodeStatusChangeList(proposal);
                        final List<Endpoint> currentMembership = membershipView.getRing(0);
                        final ClusterStatusChange clusterStatusChange = new ClusterStatusChange(currentConfigurationId,
                                                                                             currentMembership, result);
                        // Inform subscribers that a proposal has been announced.
                        subscriptions.get(ClusterEvents.VIEW_CHANGE_PROPOSAL)
                                .forEach(cb -> cb.accept(clusterStatusChange));
                    }
                    fastPaxosInstance.propose(new ArrayList<>(proposal.stream()
                            .sorted(membershipView.getRingZeroComparator())
                            .collect(Collectors.toList())));
                }
                else{
                    LOG.info("{} proposal empty!", myAddr.getPort());
                }
                future.set(RapidResponse.getDefaultInstance());
            }
        });
        return future;
    }


    /**
     * Receives proposal for the one-step consensus (essentially phase 2 of Fast Paxos).
     *
     * XXX: Implement recovery for the extremely rare possibility of conflicting proposals.
     *
     */
    private ListenableFuture<RapidResponse> handleConsensusMessages(final RapidRequest request) {
        final SettableFuture<RapidResponse> future = SettableFuture.create();
        sharedResources.getProtocolExecutor().execute(() -> future.set(fastPaxosInstance.handleMessages(request)));
        return future;
    }

    /**
     * Propagates the intent of a node to leave the group
     */
    private ListenableFuture<RapidResponse> handleLeaveMessage(final RapidRequest request) {
        final SettableFuture<RapidResponse> future = SettableFuture.create();
        edgeFailureNotification(request.getLeaveMessage().getSender(), membershipView.getCurrentConfigurationId());
        future.set(RapidResponse.getDefaultInstance());
        return future;
    }

    /**
     * This is invoked by FastPaxos modules when they arrive at a decision.
     *
     * Any node that is not in the membership list will be added to the cluster,
     * and any node that is currently in the membership list will be removed from it.
     */
    private void decideViewChange(final List<Endpoint> proposal) {
        LOG.info("Decide view change called in current configuration {} ({} nodes), for proposal {}",
                membershipView.getCurrentConfigurationId(), membershipView.getMembershipSize(),
                Utils.loggable(proposal));
        // The first step is to disable our failure detectors in anticipation of new ones to be created.
        cancelFailureDetectorJobs();

        final List<NodeStatusChange> statusChanges = new ArrayList<>(proposal.size());
        synchronized (membershipUpdateLock) {
            for (final Endpoint node : proposal) {
                final boolean isPresent = membershipView.isHostPresent(node);
                // If the node is already in the ring, remove it. Else, add it.
                // XXX: Maybe there's a cleaner way to do this in the future because
                // this ties us to just two states a node can be in.
                if (isPresent) {
                    membershipView.ringDelete(node);
                    statusChanges.add(new NodeStatusChange(node, EdgeStatus.DOWN, metadataManager.get(node)));
                    metadataManager.removeNode(node);
                }
                else {
                    assert joinerUuid.containsKey(node);
                    final NodeId nodeId = joinerUuid.remove(node);
                    membershipView.ringAdd(node, nodeId);
                    final Metadata metadata = joinerMetadata.remove(node);
                    if (metadata.getMetadataCount() > 0) {
                        metadataManager.addMetadata(Collections.singletonMap(node, metadata));
                    }
                    statusChanges.add(new NodeStatusChange(node, EdgeStatus.UP, metadata));
                }
            }
        }

        // if (membershipView.isHostPresent(myAddr)) {
            long viewchangeTime = System.nanoTime();
            // System.out.printf("Port {} + view changes to size {} at {}", myAddr.getPort(), getMembershipView().size(), viewchangeTime);
            System.out.printf(
            "Port %d + view changes to size %d at %d ms%n",
            myAddr.getPort(),
            getMembershipView().size(),
            viewchangeTime / 1_000_000 // Convert nanoseconds to milliseconds
            );
        // }
        if(settings.getBatchingWindowInMs() != 100){
            settings.setConsensusFallbackTimeoutBaseDelayInMs(1000);
            settings.setGrpcTimeoutMs(1000);
            settings.setBatchingWindowInMs(100);
            alertBatcherJob.cancel(true);
            alertBatcherJob = this.backgroundTasksExecutor.scheduleAtFixedRate(new AlertBatcher(),
                0, settings.getBatchingWindowInMs(), TimeUnit.MILLISECONDS);
        }
        if(proposal.size() >= 5){
        stopLatencyBroadcasts();
        stopLatencyProbes();
        stopDGRO();
        }
        final long currentConfigurationId = membershipView.getCurrentConfigurationId();
        // Publish an event to the listeners.
        final List<Endpoint> currentMembership = membershipView.getRing(0);
        // membershipView.reconstructDGRO(myAddr);
        final ClusterStatusChange clusterStatusChange = new ClusterStatusChange(currentConfigurationId,
                                                                                currentMembership, statusChanges);
        subscriptions.get(ClusterEvents.VIEW_CHANGE).forEach(cb -> cb.accept(clusterStatusChange));

        // Clear data structures for the next round.
        cutDetection.clear();
        announcedProposal = false;
        fastPaxosInstance = new FastPaxos(myAddr, currentConfigurationId, membershipView.getMembershipSize(),
                                          messagingClient, broadcaster, backgroundTasksExecutor,
                                          this::decideViewChange, settings);
        // broadcaster.setMembership(membershipView.getRing(0));
        // broadcaster.setMembership(membershipView.getSubjectsOf(myAddr));
        List<Endpoint> subjects = membershipView.getGossipOutOf(myAddr);
        if(subjects.size() == 0)System.out.println("Decide view change: subjects size is 0");
        // List<Endpoint> subjects = membershipView.getSubjectsOf(myAddr);

        if (subjects.isEmpty()) {
            subjects = new ArrayList<>(); // Create a mutable list
            subjects.add(myAddr); // Add myAddr to the list
        }
        
        this.broadcaster.setMembership(subjects, getMembershipView());
        // Inform EdgeFailureDetector about membership change
        if (membershipView.isHostPresent(myAddr)) {
            createFailureDetectorsForCurrentConfiguration();
            // createLatencyBroadcasters();
            // createLatencyProbesForCurrentConfiguration();
            // createDGRO();
        } else {
            // We need to gracefully exit by calling a user handler and invalidating
            // the current session.
            LOG.trace("Got kicked out and is shutting down.");
            subscriptions.get(ClusterEvents.KICKED).forEach(cb -> cb.accept(clusterStatusChange));
        }
        if(proposal.size() >= 5){
            createLatencyBroadcasters();
            createLatencyProbesForCurrentConfiguration();
            createDGRO();
        }
        // Send new configuration to all nodes joining through us
        respondToJoiners(proposal);
    }

    /**
     * Invoked by observers of a node for failure detection.
     */
    private ListenableFuture<RapidResponse> handleMessage(final ProbeMessage probeMessage) {
        LOG.trace("handleProbeMessage from {}", Utils.loggable(probeMessage.getSender()));
        return Futures.immediateFuture(Utils.toRapidResponse(ProbeResponse.getDefaultInstance()));
    }

    private ListenableFuture<RapidResponse> handleMessage(final LatencyMessage request) {
        membershipView.updateLatencyMap(request.getSender(), request.getLatencyMapMap());
        return Futures.immediateFuture(Utils.toRapidResponse(ProbeResponse.getDefaultInstance()));
    }

    /**
     * Invoked by subscribers waiting for event notifications.
     * @param event Cluster event to subscribe to
     * @param callback Callback to be executed when {@code event} occurs.
     */
    void registerSubscription(final ClusterEvents event,
                              final Consumer<ClusterStatusChange> callback) {
        subscriptions.get(event).add(callback);
    }


    /**
     * This is a notification from a local edge failure detector at an observer. This changes
     * the status of the edge between the observer and the subject to DOWN.
     *
     * @param subject The subject that has failed.
     */
    private void edgeFailureNotification(final Endpoint subject, final long configurationId) {
        sharedResources.getProtocolExecutor().execute(() -> {
            if (configurationId != membershipView.getCurrentConfigurationId()) {
                LOG.info("Ignoring failure notification from old configuration" +
                                " {subject:{}, config:{}, oldConfiguration:{}}",
                        Utils.loggable(subject), membershipView.getCurrentConfigurationId(), configurationId);
                return;
            }
            if (LOG.isDebugEnabled()) {
                final int size = membershipView.getMembershipSize();
                LOG.debug("Announcing EdgeFail event {subject:{}, observer:{}, config:{}, size:{}}",
                        Utils.loggable(subject), Utils.loggable(myAddr), configurationId, size);
            }
            // Note: setUuid is deliberately missing here because it does not affect leaves.
            final AlertMessage msg = AlertMessage.newBuilder()
                    .setEdgeSrc(myAddr)
                    .setEdgeDst(subject)
                    .setEdgeStatus(EdgeStatus.DOWN)
                    .addAllRingNumber(membershipView.getRingNumbers(myAddr, subject))
                    .setConfigurationId(configurationId)
                    .build();
            enqueueAlertMessage(msg);
        });
    }


    /**
     * Gets the list of endpoints currently in the membership view.
     *
     * @return list of endpoints in the membership view
     */
    public List<Endpoint> getMembershipView() {
        synchronized (membershipUpdateLock) {
            return membershipView.getRing(0);
        }
    }

        /**
     * Gets the list of endpoints currently in the membership view.
     *
     * @return list of endpoints in the membership view
     */
    public List<Endpoint> getSubjectsOf() {
        synchronized (membershipUpdateLock) {
            return membershipView.getSubjectsOf(myAddr);
        }
    }

    public IMessagingClient getMessagingClient() {
            return messagingClient;
    }

    /**
     * Gets the list of endpoints currently in the membership view.
     *
     * @return list of endpoints in the membership view
     */
    int getMembershipSize() {
        synchronized (membershipUpdateLock) {
            return membershipView.getMembershipSize();
        }
    }


    /**
     * Gets the list of endpoints currently in the membership view.
     *
     * @return list of endpoints in the membership view
     */
    Map<Endpoint, Metadata> getMetadata() {
        synchronized (membershipUpdateLock) {
            return metadataManager.getAllMetadata();
        }
    }

    /**
     * Shuts down all the executors.
     */
    void shutdown() {
        alertBatcherJob.cancel(true);
        failureDetectorJobs.forEach(k -> k.cancel(true));
        messagingClient.shutdown();
        stopLatencyProbes();
        stopLatencyBroadcasts();

    }

    /**
     * Leaves the cluster by telling all the observers to proactively trigger failure.
     * This operation is blocking, as we need to wait to send the alert messages before shutting down the rest
     */
    void leave() {
        final LeaveMessage leaveMessage = LeaveMessage.newBuilder().setSender(myAddr).build();
        final RapidRequest leave = RapidRequest.newBuilder().setLeaveMessage(leaveMessage).build();

        try {
            final List<Endpoint> observers = membershipView.getObserversOf(myAddr);
            final ListenableFuture<List<RapidResponse>> leaveResponses = Futures.successfulAsList(observers.stream()
                    .map(endpoint -> messagingClient.sendMessageBestEffort(endpoint, leave))
                    .collect(Collectors.toList()));
            try {
                leaveResponses.get(LEAVE_MESSAGE_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException | ExecutionException e) {
                LOG.trace("Exception while leaving", e);
            } catch (final TimeoutException e) {
                LOG.trace("Timeout while leaving", e);
            }
        } catch (final MembershipView.NodeNotInRingException e) {
            // we already were removed, so that's fine
            LOG.trace("Node was already removed prior to leaving", e);
        }
    }

    /**
     * Queues a AlertMessage to be broadcasted after potentially being batched.
     *
     * @param msg the AlertMessage to be broadcasted
     */
    private void enqueueAlertMessage(final AlertMessage msg) {
        LOG.trace("enqueueAlertMessage" + myAddr);
        batchSchedulerLock.lock();
        try {
            lastEnqueueTimestamp = System.currentTimeMillis();
            sendQueue.add(msg);
        }
        finally {
            batchSchedulerLock.unlock();
        }
    }

    /**
     * Formats a proposal or a view change for application subscriptions.
     */
    private List<NodeStatusChange> createNodeStatusChangeList(final Collection<Endpoint> proposal) {
        final List<NodeStatusChange> list = new ArrayList<>(proposal.size());
        for (final Endpoint node: proposal) {
            final EdgeStatus status = membershipView.isHostPresent(node) ? EdgeStatus.DOWN : EdgeStatus.UP;
            list.add(new NodeStatusChange(node, status, metadataManager.get(node)));
        }
        return list;
    }

    /**
     * Prepares a view change notification for a node that has just become part of a cluster. This is invoked when the
     * membership service is first initialized by a new node, which only happens on a Cluster.join() or Cluster.start().
     * Therefore, all EdgeStatus values will be UP.
     */
    private List<NodeStatusChange> getInitialViewChange() {
        final List<NodeStatusChange> list = new ArrayList<>(membershipView.getMembershipSize());
        for (final Endpoint node: membershipView.getRing(0)) {
            final EdgeStatus status = EdgeStatus.UP;
            list.add(new NodeStatusChange(node, status, metadataManager.get(node)));
        }
        return list;
    }


    /**
     * Batches outgoing AlertMessages into a single BatchAlertMessage.
     */
    private class AlertBatcher implements Runnable {

        @Override
        public void run() {
            LOG.trace("Inside run, " + myAddr);
            batchSchedulerLock.lock();
            try {
                // Wait one BATCHING_WINDOW_IN_MS since last add before sending out
                LOG.trace("Inside run::try, " + myAddr);
                if (!sendQueue.isEmpty() && lastEnqueueTimestamp > 0
                        && (System.currentTimeMillis() - lastEnqueueTimestamp) > settings.getBatchingWindowInMs()) {
                    LOG.trace("Scheduler is sending out {} messages, " + myAddr, sendQueue.size());
                    final ArrayList<AlertMessage> messages = new ArrayList<>(sendQueue.size());
                    final int numDrained = sendQueue.drainTo(messages);
                    assert numDrained > 0;
                    final BatchedAlertMessage batched = BatchedAlertMessage.newBuilder()
                            .setSender(myAddr)
                            .addAllMessages(messages)
                            .build();
                    broadcaster.broadcast(Utils.toRapidRequest(batched));
                }
            }
            finally {
                batchSchedulerLock.unlock();
            }
        }
    }

    /**
     * A filter for removing invalid edge update messages. These include messages that were for a
     * configuration that the current node is not a part of, and messages that violate the semantics
     * of a node being a part of a configuration.
     */
    private boolean filterAlertMessages(final BatchedAlertMessage batchedAlertMessage,
                                        final AlertMessage alertMessage,
                                        final int membershipSize,
                                        final long currentConfigurationId) {
        final Endpoint destination = alertMessage.getEdgeDst();
        LOG.trace("AlertMessage received {sender:{}, config:{}, size:{}, status:{}, destination:{}}",
                Utils.loggable(batchedAlertMessage.getSender()), alertMessage.getConfigurationId(),
                membershipSize, alertMessage.getEdgeStatus(), destination);

        if (currentConfigurationId != alertMessage.getConfigurationId()) {
            LOG.trace("AlertMessage for configuration {} received during configuration {}",
                    alertMessage.getConfigurationId(), currentConfigurationId);
            return false;
        }

        // The invariant we want to maintain is that a node can only go into the
        // membership set once and leave it once.
        if (alertMessage.getEdgeStatus().equals(EdgeStatus.UP)
                && membershipView.isHostPresent(destination)) {
            LOG.trace("AlertMessage with status UP received for node {} already in configuration {} ",
                    Utils.loggable(alertMessage.getEdgeDst()), currentConfigurationId);
            return false;
        }
        if (alertMessage.getEdgeStatus().equals(EdgeStatus.DOWN)
                && !membershipView.isHostPresent(destination)) {
            LOG.trace("AlertMessage with status DOWN received for node {} already in configuration {} ",
                    Utils.loggable(alertMessage.getEdgeDst()), currentConfigurationId);
            return false;
        }

        return true;
    }
    
    private AlertMessage extractJoinerUuidAndMetadata(final AlertMessage alertMessage) {
        if (alertMessage.getEdgeStatus() == EdgeStatus.UP) {
            // Both the UUID and Metadata are saved only after the node is done being added.
            final Endpoint destination = alertMessage.getEdgeDst();
            joinerUuid.put(destination, alertMessage.getNodeId());
            joinerMetadata.put(destination, alertMessage.getMetadata());
        }
        return alertMessage;
    }

    /**
     * Invoked eventually by edge failure detectors to notify MembershipService of failed nodes
     */
    private Runnable createNotifierForSubject(final Endpoint subject) {
        return () -> edgeFailureNotification(subject, membershipView.getCurrentConfigurationId());
    }

    /**
     * Creates and schedules failure detector instances based on the fdFactory instance.
     */
    private void createFailureDetectorsForCurrentConfiguration() {
        final List<ScheduledFuture<?>> jobs = membershipView.getSubjectsOf(myAddr)
                .stream().map(subject -> backgroundTasksExecutor
                         .scheduleAtFixedRate(fdFactory.createInstance(subject,
                                createNotifierForSubject(subject)), // Runnable
                                DEFAULT_FAILURE_DETECTOR_INITIAL_DELAY_IN_MS,
                                settings.getFailureDetectorIntervalInMs(),
                                TimeUnit.MILLISECONDS))
                .collect(Collectors.toList());
        failureDetectorJobs.addAll(jobs);
    }

    /**
     * Cancel all running failure detector tasks
     */
    private void cancelFailureDetectorJobs() {
        failureDetectorJobs.forEach(future -> future.cancel(true));
    }

    public void createLatencyProbesForCurrentConfiguration() {
        // Create and schedule latency probes
        List<ScheduledFuture<?>> jobs = getMembershipView().stream()
                .map(endpoint -> latencyExecutor.scheduleAtFixedRate(
                        createLatencyProbeTask(endpoint),
                        // (long) (-5 * Math.log(1 - ThreadLocalRandom.current().nextDouble())),
                        (long) 20 + (long)myAddr.getPort() % 5,
                        // (long) 200 + 2 * (long)myAddr.getPort() % 10,
                        1000,
                        TimeUnit.SECONDS))
                .collect(Collectors.toList());

        latencyProbeJobs.addAll(jobs);
    }

    /**
     * Stops all ongoing latency probes.
     */
    public void stopLatencyProbes() {
        latencyProbeJobs.forEach(job -> job.cancel(false));
        latencyExecutor.shutdown();
    }

    /**
     * Creates a latency probe task for a specific endpoint.
     */
    private Runnable createLatencyProbeTask(Endpoint endpoint) {
        return () -> {
            try {
                // Send a best-effort message to the endpoint
                RapidRequest probeMessage = RapidRequest.newBuilder().setProbeMessage(
                ProbeMessage.newBuilder().setSender(myAddr).build()).build();
                messagingClient.sendMessageBestEffort(endpoint, probeMessage);
                // System.out.printf("Probed latency for endpoint %s%n", endpoint);
            } catch (Exception e) {
                // System.err.printf("Failed to probe latency for endpoint %s: %s%n", endpoint, e.getMessage());
            }
        };
    }

        /**
     * Creates a periodic latency broadcaster for the current configuration.
     */
    public void createLatencyBroadcasters() {
        // Create and schedule the periodic latency broadcaster task
        ScheduledFuture<?> jobs = 
        broadcastExecutor.scheduleAtFixedRate(
                        createLatencyBroadcastTask(),
                        60 + (long)myAddr.getPort() % 5, // Initial delay
                        // 450 + 2 * (long)myAddr.getPort() % 10, // Initial delay
                        1000,
                        TimeUnit.SECONDS);

        broadcastJobs.add(jobs);
    }

    /**
     * Creates a latency broadcast task for a specific endpoint.
     */
    private Runnable createLatencyBroadcastTask() {
        return () -> {
            try {
                LOG.trace("Broadcasting latency map for {}", myAddr);

                // Construct a LatencyMessage containing the current latency map
                membershipView.updateLatencyMap(myAddr, messagingClient.getLatencyMap());
                LatencyMessage.Builder latencyMessageBuilder = LatencyMessage.newBuilder()
                        .setSender(myAddr).putAllLatencyMap(messagingClient.getLatencyMap()); // Convert Endpoint to string
                LatencyMessage latencyMessage = latencyMessageBuilder.build();

                // Wrap the LatencyMessage into a RapidRequest
                RapidRequest rapidRequest = Utils.toRapidRequest(latencyMessage);

                // Broadcast the message
                broadcaster.broadcast(rapidRequest);

                LOG.trace("Latency map broadcasted successfully for {}", myAddr);
            } catch (Exception e) {
                LOG.error("Error broadcasting latency map for {}: {}", myAddr, e.getMessage(), e);
            }
        };
    }

    /**
     * Stops all ongoing latency broadcast jobs.
     */
    public void stopLatencyBroadcasts() {
        broadcastJobs.forEach(job -> job.cancel(false));
        broadcastExecutor.shutdown();
    }

    public void createDGRO() {
        // Create and schedule the periodic latency broadcaster task
        ScheduledFuture<?> jobs = 
        dgroExecutor.scheduleAtFixedRate(
                        createDGROTask(),
                        70, // Initial delay
                        1000,
                        TimeUnit.SECONDS);

        dgroJobs.add(jobs);
    }

    /**
     * Creates a latency broadcast task for a specific endpoint.
     */
    private Runnable createDGROTask() {
        return () -> {
            try {
                membershipView.reconstructDGRO(myAddr);
                // LOG.trace("Latency map broadcasted successfully for {}", myAddr);
            } catch (Exception e) {
                // LOG.error("Error broadcasting latency map for {}: {}", myAddr, e.getMessage(), e);
            }
        };
    }

    /**
     * Stops all ongoing latency broadcast jobs.
     */
    public void stopDGRO() {
        dgroJobs.forEach(job -> job.cancel(false));
        dgroExecutor.shutdown();
    }

    /**
     * Respond with the current configuration to all nodes that attempted to join through this node.
     */
    private void respondToJoiners(final List<Endpoint> proposal) {
        // This should yield the new configuration.
        final MembershipView.Configuration configuration = membershipView.getConfiguration();
        assert !configuration.endpoints.isEmpty();
        assert !configuration.nodeIds.isEmpty();

        final JoinResponse response = JoinResponse.newBuilder()
                .setSender(myAddr)
                .setStatusCode(JoinStatusCode.SAFE_TO_JOIN)
                .setConfigurationId(configuration.getConfigurationId())
                .addAllEndpoints(configuration.endpoints)
                .addAllIdentifiers(configuration.nodeIds)
                .addAllMetadataKeys(metadataManager.getAllMetadata().keySet())
                .addAllMetadataValues(metadataManager.getAllMetadata().values())
                .build();

        // Send out responses to all the nodes waiting to join.
        for (final Endpoint node: proposal) {
            if (joinersToRespondTo.containsKey(node)) {
                backgroundTasksExecutor.execute(
                    () -> joinersToRespondTo.remove(node)
                            .forEach(settableFuture -> settableFuture.set(Utils.toRapidResponse(response)))
                );
            }
        }
    }

    interface ISettings {
        int getFailureDetectorIntervalInMs();

        int getBatchingWindowInMs();
    }
}