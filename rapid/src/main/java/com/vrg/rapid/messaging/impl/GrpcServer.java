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
import com.vrg.rapid.MembershipService;
import com.vrg.rapid.SharedResources;
import com.vrg.rapid.Utils;
import com.vrg.rapid.messaging.IMessagingServer;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.MembershipServiceGrpc;
import com.vrg.rapid.pb.MessageId;
import com.vrg.rapid.pb.NodeStatus;
import com.vrg.rapid.pb.ProbeResponse;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * gRPC server object. It defers receiving messages until it is ready to
 * host a MembershipService object.
 */
public class GrpcServer extends MembershipServiceGrpc.MembershipServiceImplBase implements IMessagingServer {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcServer.class);

    private final ExecutorService grpcExecutor;
    @Nullable private final EventLoopGroup eventLoopGroup;
    private static final RapidResponse BOOTSTRAPPING_MESSAGE =
            RapidResponse.newBuilder().setProbeResponse(ProbeResponse.newBuilder()
                                                        .setStatus(NodeStatus.BOOTSTRAPPING).build()).build();
    private final Endpoint address;
    @Nullable
    private MembershipService membershipService;
    @Nullable private Server server;
    private final boolean useInProcessServer;
    private final Cache<String, Boolean> messageCache;
    static final UUID TEST_MESSAGE_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    MessageId testMessageId = Utils.messageIdFromUUID(TEST_MESSAGE_UUID);

    // Used to queue messages in the RPC layer until we are ready with
    // a MembershipService object
    public GrpcServer(final Endpoint address, final SharedResources sharedResources,
                      final boolean useInProcessTransport) {
        this.address = address;
        this.grpcExecutor = sharedResources.getServerExecutor();
        this.eventLoopGroup = useInProcessTransport ? null : sharedResources.getEventLoopGroup();
        this.useInProcessServer = useInProcessTransport;
        // Initialize the cache with a maximum size and expiration time
        this.messageCache = Caffeine.newBuilder()
        .expireAfterWrite(1000, TimeUnit.SECONDS)
        .maximumSize(1000)
        .build();
    }


    /**
     * Defined in rapid.proto.
     */
    @Override
    public void sendRequest(final RapidRequest rapidRequest,
                            final StreamObserver<RapidResponse> responseObserver) {
        // if (rapidRequest.getContentCase() == RapidRequest.ContentCase.FASTROUNDPHASE2BMESSAGE) {
        //     System.out.println("当前时间（毫秒精度）: " + System.currentTimeMillis()  + ", Endpoint: " + address); 
        // }
        final String messageId = rapidRequest.getMessageId().getHigh() + "-" 
        + rapidRequest.getMessageId().getLow();

        
        if (
            rapidRequest.getContentCase() == RapidRequest.ContentCase.FASTROUNDPHASE2BMESSAGE ||
            rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE1AMESSAGE ||
            rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE2AMESSAGE ||
            rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE2BMESSAGE ||
            rapidRequest.getContentCase() == RapidRequest.ContentCase.LATENCYMESSAGE ||
            rapidRequest.getContentCase() == RapidRequest.ContentCase.BATCHEDALERTMESSAGE) {
            // Store the message ID in the cache
            // final String messageId = rapidRequest.getMessageId().getHigh() + "-" 
            // + rapidRequest.getMessageId().getLow();
            // else{
            //     System.out.println("messageId: " + rapidRequest.getMessageId() + " messageId_string: " + messageId + " testMessageId: " + testMessageId);
            // }
            if (messageCache.getIfPresent(messageId) != null) {
                return;
            }
        }
        if(rapidRequest.getMessageId().equals(testMessageId)){
            System.out.println("Server receives PHASE2BMESSAGE: " + System.currentTimeMillis()  + ", Endpoint: " + address); 
        }
        else if (membershipService != null) {
            // Forward the message to another node or handle accordingly
            // System.out.println("MembershipService != null");
            final ListenableFuture<RapidResponse> result = membershipService.handleMessage(rapidRequest);
            Futures.addCallback(result, new ResponseCallback(responseObserver), grpcExecutor);
        }
        else if (rapidRequest.getContentCase().equals(RapidRequest.ContentCase.PROBEMESSAGE)) {
            /*
             * This is a special case which indicates that:
             *  1) the system is configured to use a failure detector that relies on Rapid's probe messages
             *  2) the node receiving the probe message has been added to the cluster but has not yet completed
             *     its bootstrap process (has not received its join-confirmation yet).
             *  3) By virtue of 2), the node is "about to be up" and therefore informs the observer that it is
             *     still bootstrapping. This extra information may or may not be respected by the failure detector,
             *     but is useful in large deployments.
             */
            responseObserver.onNext(BOOTSTRAPPING_MESSAGE);
            responseObserver.onCompleted();
        }
    if (
        rapidRequest.getContentCase() == RapidRequest.ContentCase.FASTROUNDPHASE2BMESSAGE ||
        rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE1AMESSAGE ||
        rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE2AMESSAGE ||
        rapidRequest.getContentCase() == RapidRequest.ContentCase.PHASE2BMESSAGE ||
        rapidRequest.getContentCase() == RapidRequest.ContentCase.LATENCYMESSAGE ||
        rapidRequest.getContentCase() == RapidRequest.ContentCase.BATCHEDALERTMESSAGE) {
        // Store the message ID in the cache
        // if (messageCache.getIfPresent(messageId) != null) {
        //     return;
        // }
        // long start = System.nanoTime();
        messageCache.put(messageId, Boolean.TRUE);
        // long end = System.nanoTime();
        // System.out.println("Cache put took: " + (end - start) + " ns");
        // System.out.println("Address " + address + " messageId: " + messageId + "Cache put took: " + (end - start) + " ns");
        List<Endpoint> recipients = membershipService.membershipView.getGossipOutOf(address);
        // if(recipients.size() == 0)System.out.println(rapidRequest.getContentCase() + " GRPCServer: subjects size is 0. Membership size is " + membershipService.getMembershipView().size() + " subjects_dgro size is " + membershipService.membershipView.subjects_dgro.size());
        // List<Endpoint> noResponseEndpoints = membershipService.getMessagingClient().getLatencyMap().keySet().stream()
        //                 .filter(e -> membershipService.getMessagingClient().getLatencyMap().
        //                 getOrDefault(e, -1L) == -1L) // Filter available endpoints
        //                 .collect(Collectors.toList());
        List<Endpoint> availableEndpoints = new ArrayList<>(membershipService.getMembershipView());
        // List<Endpoint> availableEndpoints = membershipService.getMembershipView();
        // for (Endpoint endpoint : recipients) {
        //     if (!noResponseEndpoints.contains(endpoint)) {
        //         availableEndpoints.add(endpoint);
        //     }
        // }
        // recipients = availableEndpoints;
        // availableEndpoints = new ArrayList<>();
        // availableEndpoints = membershipService.getMembershipView();
        // for (Endpoint endpoint : membershipService.getMembershipView()) {
        //     if (!noResponseEndpoints.contains(endpoint)) {
        //         availableEndpoints.add(endpoint);
        //     }
        // }
    
        // for (final Endpoint recipient : availableEndpoints) {
        //     StreamObserver<RapidResponse> redistributeObserver = new StreamObserver<RapidResponse>() {
        //         @Override
        //         public void onNext(RapidResponse response) {
        //             LOG.info("Received response from redistributed message to {}: {}", recipient, response);
        //         }
        
        //         @Override
        //         public void onError(Throwable t) {
        //             LOG.warn("Redistribution to {} failed: {}", recipient, t.getMessage());
        //         }
        
        //         @Override
        //         public void onCompleted() {
        //             LOG.info("Redistribution to {} completed.", recipient);
        //         }
        //     };
        
        //     // Forward the message
        //     ListenableFuture<RapidResponse> result = membershipService.getMessagingClient()
        //             .sendMessageBestEffort(recipient, rapidRequest);
        //     Futures.addCallback(result, new ResponseCallback(redistributeObserver), grpcExecutor);
        // }
        int count = 0;
        // Random random = new Random(membershipService.getMessagingClient().getAddress().getPort());
        Collections.shuffle(availableEndpoints);
        // for (final Endpoint recipient : availableEndpoints) {
        for (final Endpoint recipient : recipients) {
            // if(count == 5)break;
            // if (noResponseEndpoints.contains(recipient)) {
            //     continue;
            // }
            Endpoint target = recipient;
            // if (noResponseEndpoints.contains(recipient)) {
            //     target = availableEndpoints.get(count % availableEndpoints.size());
            // }
            // Create a new instance of the static inner observer
            StreamObserver<RapidResponse> redistributeObserver = new RedistributeObserver(target);
        
            // Forward the message
            ListenableFuture<RapidResponse> result = membershipService.getMessagingClient()
                    .sendMessageBestEffort(target, rapidRequest);
            // Use the static ResponseCallback (if you also replaced it)
            Futures.addCallback(result, new ResponseCallback(redistributeObserver), grpcExecutor);
            // membershipService.getMessagingClient()
            //         .sendMessageBestEffort(recipient, rapidRequest);
            count++;
        }
        // int count = 0;
        for (final Endpoint recipient: availableEndpoints) {
            if(count == 10)break;
            // if (noResponseEndpoints.contains(recipient)) {
            //     continue;
            // }
            // Create a new instance of the static inner observer
            StreamObserver<RapidResponse> redistributeObserver = new RedistributeObserver(recipient);
        
            // Forward the message
            ListenableFuture<RapidResponse> result = membershipService.getMessagingClient()
                    .sendMessageBestEffort(recipient, rapidRequest);
            // Use the static ResponseCallback (if you also replaced it)
            Futures.addCallback(result, new ResponseCallback(redistributeObserver), grpcExecutor);
            // membershipService.getMessagingClient()
            //         .sendMessageBestEffort(recipient, rapidRequest);
            count++;
        }

    }
    }

    /**
     * Invoked by the bootstrap protocol when it has a membership service object
     * ready. Until this method is called, the GrpcServer will not have its gRPC service
     * methods invoked.
     *
     * @param service a fully initialized MembershipService object.
     */
    @Override
    public void setMembershipService(final MembershipService service) {
        if (this.membershipService != null) {
            throw new RuntimeException("setMembershipService called more than once");
        }
        this.membershipService = service;
    }


    // IMessaging server interface
    @Override
    public void shutdown() {
        assert server != null;
        try {
            server.shutdown();
            server.awaitTermination(0, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts the RPC server.
     *
     * @throws IOException if a server cannot be successfully initialized
     */
    @Override
    public void start() throws IOException {
        // System.out.println("Starting server at " + address);
        if (useInProcessServer) {
            final ServerBuilder builder = InProcessServerBuilder.forName(address.toString());
            server = builder.addService(this)
                    .executor(grpcExecutor)
                    .build()
                    .start();
        } else {
            // System.out.println("NettyServerBuilder");
            server = NettyServerBuilder.forAddress(
                        new InetSocketAddress(address.getHostname().toStringUtf8(), address.getPort())
                        // new InetSocketAddress("0.0.0.0", address.getPort())
                    )
                    .workerEventLoopGroup(eventLoopGroup)
                    .addService(this)
                    .executor(grpcExecutor)
                    .build()
                    .start();
            // System.out.println("Server initialized");
        }

        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    // Callbacks
    private static class ResponseCallback implements FutureCallback<RapidResponse> {
        private final StreamObserver<RapidResponse> responseObserver;

        ResponseCallback(final StreamObserver<RapidResponse> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onSuccess(@Nullable final RapidResponse response) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void onFailure(final Throwable throwable) {
            LOG.error("RPC failed {}", throwable);
        }
    }
    private static class RedistributeObserver implements StreamObserver<RapidResponse> {
        private final Endpoint recipient;

        // Pass in anything the observer needs, like the recipient or other state
        public RedistributeObserver(final Endpoint recipient) {
            this.recipient = recipient;
        }

        @Override
        public void onNext(RapidResponse response) {
            LOG.info("Received response from redistributed message to {}: {}", recipient, response);
        }

        @Override
        public void onError(Throwable t) {
            LOG.warn("Redistribution to {} failed: {}", recipient, t.getMessage());
        }

        @Override
        public void onCompleted() {
            LOG.info("Redistribution to {} completed.", recipient);
        }
    }
}
