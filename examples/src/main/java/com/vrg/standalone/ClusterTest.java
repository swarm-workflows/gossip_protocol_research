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
//  import com.google.common.net.HostAndPort;
 import com.vrg.rapid.Cluster;
 import com.vrg.rapid.Utils;
 import com.vrg.rapid.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
//  import com.vrg.rapid.ClusterStatusChange;
//  import org.apache.commons.cli.CommandLine;
//  import org.apache.commons.cli.CommandLineParser;
//  import org.apache.commons.cli.DefaultParser;
//  import org.apache.commons.cli.Options;
//  import org.apache.commons.cli.ParseException;
//  import org.slf4j.Logger;
import java.util.logging.Logger;

//  import org.slf4j.LoggerFactory;
 
 import javax.annotation.Nullable;
 
//  import java.time.LocalDateTime;
//  import java.time.format.DateTimeFormatter;
 
 import com.google.protobuf.ByteString;
//  import com.vrg.rapid.messaging.impl.GrpcClient;
 import com.vrg.rapid.pb.Endpoint;
 import com.vrg.rapid.Utils;
 import com.vrg.rapid.pb.FastRoundPhase2bMessage;
//  import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.MessageId;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
 import java.util.Collections;
 import java.io.OutputStream;
//  import java.util.HashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.CountDownLatch;
//  import java.util.concurrent.ExecutionException;
 import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
 import java.util.concurrent.ThreadLocalRandom;
 import java.util.concurrent.atomic.AtomicInteger;
 import java.util.logging.Level;
 import java.util.stream.Collectors;
//  import java.util.stream.IntStream;
 import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

 /**
  * Test public API
  */
 public class ClusterTest {
     // public static final Logger LOG = LoggerFactory.getLogger(ClusterTest.class);
     public static final Logger GRPC_LOGGER;
     public static final Logger NETTY_LOGGER;
     private volatile boolean running = true;
     private Thread serverThread;
     public final Map<Endpoint, Cluster> instances = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, StaticFailureDetector.Factory> staticFds = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, List<ServerDropInterceptors.FirstN>> serverInterceptors = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, List<ClientInterceptors.Delayer>> clientInterceptors = new ConcurrentHashMap<>();
     public boolean useStaticFd = false;
     public boolean addMetadata = true;
     @Nullable public Random random = null;
     public long seed;
     public int basePort;
     public String myIP;
     private int confirmedConnections = 0;
     public int numNodes;
     public int targetNodes;
     public int nodeId;
     public int targetServers;
     public int gossipType;
     public String testID;
     public String baseIP;
     private List<String> connectedHosts = new ArrayList<>();
     @Nullable public AtomicInteger portCounter = null;
     public Settings settings = new Settings();
 
     static {
         // gRPC and netty logs clutter the test output
         GRPC_LOGGER = Logger.getLogger("io.grpc");
         GRPC_LOGGER.setLevel(Level.OFF);
         NETTY_LOGGER = Logger.getLogger("io.grpc.netty.NettyServerHandler");
         NETTY_LOGGER.setLevel(Level.OFF);
     }
 

 
     /**
      * Identical to the previous test, but with more than K nodes joining in parallel.
      *
      * The test starts with a single seed and all N - 1 subsequent nodes initiate their join protocol at the same
      * time. This tests a single seed's ability to bootstrap a large cluster in one step.
      */

        ClusterTest() {
            basePort =  1234;
            portCounter = new AtomicInteger(basePort);
            instances.clear();
            seed = ThreadLocalRandom.current().nextLong();
            random = new Random(seed);
            settings = new Settings();
    
            // Tests need to opt out of the in-process channel
            // settings.setUseInProcessTransport(false);
            // Tests need to set more aggressive frequent failure detection intervals if required
            settings.setFailureDetectorIntervalInMs(1000);
            useStaticFd = false;
            addMetadata = true;
        }

    public void setupCluster(String baseIP, String myIP, int port, int numNodes, String testID, 
    int targetNodes, int nodeId, int targetServers, int gossipType) throws IOException, InterruptedException {
        this.baseIP = baseIP;
        this.myIP = myIP;
        this.basePort = port;
        this.numNodes = numNodes;
        this.testID = testID;
        this.targetNodes = targetNodes;
        this.nodeId = nodeId;
        this.targetServers = targetServers;
        this.gossipType = gossipType;

        System.out.println("Initializing cluster...");
        
        // Check if we can connect to the cluster
        if(nodeId == 0){
            // Set up server to reponse the connectToCluster
            setupBaseServer();
        }
        else {
        try {
            connectToCluster();
        }
        catch (Exception e) {
        System.err.println("Failed to connect to the cluster at " + baseIP + ":" + (port - 1));
        }
        }
        
        waitForConnections();


        System.out.println("Cluster is ready with " + targetServers + " servers. Starting test: " + testID);
        System.out.println("Connected to cluster. Launching " + numNodes + " nodes...");
        try{
            run(numNodes);
        } catch (IOException | InterruptedException e) {
            System.err.println("Error run: " + e.getMessage());
        }
        System.out.println("Finish running");
        stopBaseServer();
        System.out.println("Finish stopBaseServer.");
    }
    
    private void waitForConnections() {
        if (nodeId == 0) {
            synchronized (this) {
                while (confirmedConnections < targetServers - 1) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } else {
            // Logic for nodeId != 0 to wait for information from the base server
            try (ServerSocket serverSocket = new ServerSocket(basePort - 1)) {
                while (true) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        String response = in.readLine();
                        System.out.println("Accepted connection from " + socket.getInetAddress());
                        if ("FINISHED".equals(response)) {
                            break;
                        }
                    } catch (IOException e) {
                        // Handle exception
                    }
                }
            } catch (IOException e) {
                System.err.println("Error setting up server socket: " + e.getMessage());
            }
        }
    }

    private void setupBaseServer() throws IOException {
        serverThread = new Thread(() -> {
            int cnt = 0;
            try (ServerSocket serverSocket = new ServerSocket(basePort - 1)) {
                serverSocket.setSoTimeout(1000);
                System.out.println("Base server is running on port " + (basePort - 1));
                while (running) {
                    // System.out.println("seupBaseServer cnt=" + cnt);
                    cnt++;
                    try {
                        Socket clientSocket = serverSocket.accept();
                        String clientAddress = clientSocket.getInetAddress().getHostAddress();
                        System.out.println("Accepted connection from " + clientSocket.getInetAddress());
                        connectedHosts.add(clientAddress);
                        // Send confirmation message to the client
                        try (OutputStream out = clientSocket.getOutputStream()) {
                            out.write("CONFIRMED".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        synchronized (this) {
                            confirmedConnections++;
                            notifyAll();
                            if (confirmedConnections >= targetServers - 1) {
                                broadcastFinish();
                                running = false;
                            }
                        }
                    } catch (IOException e) {
                        // if (running) {
                        //     System.err.println("Error handling client connection: " + e.getMessage());
                        // }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error starting base server: " + e.getMessage());
                }
            }
            System.out.println("SetupBaseServer finished.");
        });
        serverThread.start();
    }

    private void broadcastFinish() {
        System.out.println("Broadcasting finish to other servers.");
        for (String host : connectedHosts) {
            boolean success = false;
            int attempts = 0;
            int maxAttempts = 10; // Maximum number of retry attempts
            while (!success && attempts < maxAttempts) {
                attempts++;
                try (Socket socket = new Socket(host, basePort - 1);
                    OutputStream out = socket.getOutputStream()) {
                    out.write("FINISHED".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    success = true;
                } catch (IOException e) {
                    System.err.println("Error broadcasting to " + host + " on attempt " + attempts + ": " + e.getMessage());
                    try {
                        Thread.sleep(1000); // Wait before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (!success) {
                System.err.println("Failed to broadcast to " + host + " after " + maxAttempts + " attempts.");
            }
        }
    }

    private void stopBaseServer() {
        running = false;
        if (serverThread != null) {
            serverThread.interrupt();
            try {
                System.out.println("Before servreThread.join .");
                serverThread.join();
                System.out.println("Stopping base server.");
            } catch (InterruptedException e) {
                System.err.println("Error stopping base server: " + e.getMessage());
            }
        }
    }

    private boolean connectToCluster() {
        int retries = 5;
        int attempt = 0;
        while (attempt < retries) {
            try (Socket socket = new Socket(baseIP, basePort - 1)) {
                System.out.println("Successfully connected to the cluster at " + baseIP + ":" + (basePort - 1));
                return true;
            } catch (IOException e) {
                System.err.println("Attempt " + (attempt + 1) + ": Could not connect to " + baseIP + ":" + (basePort - 1));
                attempt++;
                try {
                    Thread.sleep(2000); // Wait for 2 seconds before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("Retry interrupted");
                    return false;
                }
            }
        }
        System.err.println("Error: Could not connect to " + baseIP + ":" + (basePort - 1) + " after " + retries + " attempts");
        return false;
    }

     public void run(final int numNodes) throws IOException, InterruptedException {
            final Endpoint seedEndpoint = Utils.hostFromParts(baseIP, basePort);
            // useFastFailureDetectionTimeouts();
            if(nodeId == 0) createCluster(numNodes, seedEndpoint);
            else {
                Thread.sleep(100 * (long)(nodeId - 1));
                extendClusterWithRetry(numNodes, seedEndpoint);
            }
            System.out.println("Wait for targetNodes: " + targetNodes);
            waitAndVerifyAgreement(targetNodes, 200, 1000);
            System.out.println("TargetNodes=" + targetNodes + " have joined the cluster.");
            Thread.sleep(80000);
        //     if("1".equals(testID)){
        //     System.out.println("Broadcast Start at " + System.currentTimeMillis()  +
        //   ", Endpoint: " + seedEndpoint);
        //     }

        if("1".equals(testID)){
          if(nodeId == 0){
            // final UUID TEST_MESSAGE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
            final UUID TEST_MESSAGE_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            
            MessageId testMessageId = Utils.messageIdFromUUID(TEST_MESSAGE_UUID);
            System.out.println("Broadcast Start at " + System.currentTimeMillis()  +
             ", Endpoint: " + seedEndpoint);
            instances.get(seedEndpoint).membershipService.broadcaster.broadcast(
                Utils.toRapidRequest(FastRoundPhase2bMessage.getDefaultInstance(), testMessageId));
          }
        }
        if("2".equals(testID)){
          System.out.println("TestName: testRejoinSingleNode");
            long startTime = System.nanoTime(); // 开始计时
            System.out.println("Start time: " + startTime / 1_000_000 + " ms");
          
                for (int i = 0; i < 5; i++) {
                    if(nodeId == 0){
                    Endpoint leavingEndpoint = Utils.hostFromParts(myIP, basePort + 1);
                    final Cluster cluster = instances.remove(leavingEndpoint);
                    cluster.shutdown();
                    }
                    // System.out.println("Node " + leavingEndpoint + " shutdown.");
                    waitAndVerifyAgreement(targetNodes - 1,40, 1000);
                    // System.out.println("Node " + leavingEndpoint + " has left the cluster.");
                    if(nodeId == 0){
                    Endpoint leavingEndpoint = Utils.hostFromParts(myIP, basePort + 1);
                    extendCluster(leavingEndpoint, seedEndpoint);
                    }
                    waitAndVerifyAgreement(targetNodes, 40, 1000);
                    // System.out.println("Node " + leavingEndpoint + " has rejoined the cluster.");
                    // Thread.sleep(500);
                }
            final long endTime = System.nanoTime(); // End timing
            long durationInMs = (endTime - startTime) / 1_000_000; // Convert to milliseconds
            System.out.println("Total execution time: " + durationInMs + " ms");
        }
         try {
            Thread.sleep(10000);
            waitAndShutdownClusters();
         } catch (final InterruptedException e) {
             // Handle exception if the thread is interrupted
             System.out.println("The sleep was interrupted!");
         }
    }
     
     public void waitAndShutdownClusters() {
        for (final Cluster cluster: instances.values()) {
            cluster.shutdown();
        }
        instances.clear();
            System.out.println("All instances have been cleared.");
    }
     /**
      * Creates a cluster of size {@code numNodes} with a seed {@code seedEndpoint}.
      *
      * @param numNodes cluster size
      * @param seedEndpoint Endpoint that represents the seed node to initialize and be used as the contact point
      *                 for subsequent joiners.
      * @throws IOException Thrown if the Cluster.start() or join() methods throw an IOException when trying
      *                     to register an RpcServer.
      */
     public void createCluster(final int numNodes, final Endpoint seedEndpoint) throws IOException {
         final Cluster seed = buildCluster(seedEndpoint).start();
         instances.put(seedEndpoint, seed);
        //  assertEquals(1, seed.getMemberlist().size());
         if (numNodes >= 2) {
             extendCluster(numNodes - 1, seedEndpoint);
         }
     }
 
     /**
      * Add {@code numNodes} instances to a cluster.
      *
      * @param numNodes cluster size
      * @param seedEndpoint Endpoint that represents the seed node to initialize and be used as the contact point
      *                 for subsequent joiners.
      */
     public void extendCluster(final int numNodes, final Endpoint seedEndpoint) {
         final ExecutorService executor = Executors.newWorkStealingPool(numNodes);
         try {
             final CountDownLatch latch = new CountDownLatch(numNodes);
             for (int i = 0; i <= numNodes; i++) {
                final int currentport = i + 1234;
                if(nodeId == 0 && currentport == basePort) continue; 
                executor.execute(() -> {
                     try {
                         final Endpoint joiningEndpoint = Utils.hostFromParts(myIP, currentport);
                         final Cluster nonSeed = buildCluster(joiningEndpoint).join(seedEndpoint);
                         instances.put(joiningEndpoint, nonSeed);
                     } catch (final InterruptedException | IOException e) {
                         e.printStackTrace();
                         System.out.println(e);
                         fail();
                     } finally {
                         latch.countDown();
                     }
                 });
             }
             latch.await();
         } catch (final InterruptedException e) {
             e.printStackTrace();
             fail();
         } finally {
             executor.shutdown();
         }
     }

     public void extendClusterWithRetry(final int numNodes, final Endpoint seedEndpoint) {
        final ExecutorService executor = Executors.newWorkStealingPool(numNodes);
        final int maxAttempts = 5; // Maximum number of retry attempts
        final int retryDelay = 1000; // Delay between retries in milliseconds
    
        try {
            final CountDownLatch latch = new CountDownLatch(numNodes);
            for (int i = 0; i < numNodes; i++) {
                final int currentPort = i + 1234;
                if (nodeId == 0 && currentPort == basePort) continue;
                executor.execute(() -> {
                    int attempts = 0;
                    boolean success = false;
                    while (attempts < maxAttempts && !success) {
                        attempts++;
                        try {
                            final Endpoint joiningEndpoint = Utils.hostFromParts(myIP, currentPort);
                            final Cluster nonSeed = buildCluster(joiningEndpoint).join(seedEndpoint);
                            instances.put(joiningEndpoint, nonSeed);
                            success = true;
                        } catch (final InterruptedException | IOException e) {
                            System.out.println("Attempt " + attempts + " failed: " + e.getMessage());
                            try {
                                Thread.sleep(retryDelay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    if (!success) {
                        fail();
                    }
                    latch.countDown();
                });
            }
            latch.await();
        } catch (final InterruptedException e) {
            e.printStackTrace();
            fail();
        } finally {
            executor.shutdown();
        }
    }
 
     /**
      * Add {@code numNodes} instances to a cluster.
      *
      * @param joiningNode Endpoint that represents the node joining.
      * @param seedEndpoint Endpoint that represents the seed node to initialize and be used as the contact point
      *                 for subsequent joiners.
      */
     public void extendCluster(final Endpoint joiningNode, final Endpoint seedEndpoint) {
         final ExecutorService executor = Executors.newWorkStealingPool(1);
         try {
             final CountDownLatch latch = new CountDownLatch(1);
             executor.execute(() -> {
                 try {
                     final Cluster nonSeed = buildCluster(joiningNode).join(seedEndpoint);
                     instances.put(joiningNode, nonSeed);
                 } catch (final InterruptedException | IOException e) {
                     fail();
                 } finally {
                     latch.countDown();
                 }
             });
             latch.await();
         } catch (final InterruptedException e) {
             e.printStackTrace();
             fail();
         } finally {
             executor.shutdown();
         }
     }
 
 
     /**
      * Add {@code numNodes} instances to a cluster without waiting for their join methods to return
      *
      * @param numNodes cluster size
      * @param seedEndpoint Endpoint that represents the seed node to initialize and be used as the contact point
      *                 for subsequent joiners.
      */
     public void extendClusterNonBlocking(final int numNodes, final Endpoint seedEndpoint) {
         final ExecutorService executor = Executors.newWorkStealingPool(numNodes);
         try {
             for (int i = 0; i < numNodes; i++) {
                 executor.execute(() -> {
                     try {
                         final Endpoint joiningEndpoint =
                                 Utils.hostFromParts(myIP, portCounter.incrementAndGet());
                         final Cluster nonSeed = buildCluster(joiningEndpoint).join(seedEndpoint);
                         instances.put(joiningEndpoint, nonSeed);
                     } catch (final InterruptedException | IOException e) {
                         e.printStackTrace();
                         fail();
                     }
                 });
             }
         } finally {
             executor.shutdown();
         }
     }
 
     /**
      * Fail a set of nodes in a cluster by calling shutdown().
      *
      * @param nodesToFail list of Endpoint objects representing the nodes to fail
      */
     public void failSomeNodes(final List<Endpoint> nodesToFail) {
         final ExecutorService executor = Executors.newWorkStealingPool(nodesToFail.size());
         try {
             final CountDownLatch latch = new CountDownLatch(nodesToFail.size());
             for (final Endpoint nodeToFail : nodesToFail) {
                 executor.execute(() -> {
                     try {
                         assertTrue(nodeToFail + " not in instances", instances.containsKey(nodeToFail));
                         instances.get(nodeToFail).shutdown();
                         instances.remove(nodeToFail);
                     } finally {
                         latch.countDown();
                     }
                 });
             }
             latch.await();
         } catch (final InterruptedException e) {
             e.printStackTrace();
             fail();
         } finally {
             executor.shutdown();
         }
     }
 
     /**
      * Verify that all nodes in the cluster are of size {@code expectedSize} and have an identical
      * list of members as the seed node.
      *
      * @param expectedSize expected size of each cluster
      */
     public void verifyCluster(final int expectedSize) {
         final List<Endpoint> any = instances.entrySet().iterator().next().getValue().getMemberlist();
         for (final Cluster cluster : instances.values()) {
             assertEquals(cluster.toString(), expectedSize, cluster.getMemberlist().size());
             assertEquals(cluster.getMemberlist(), any);
             if (addMetadata) {
                 assertEquals(cluster.toString(), expectedSize, cluster.getClusterMetadata().size());
             }
         }
     }
 
     /**
      * Verify that all nodes in the cluster are of size {@code expectedSize} and have an identical
      * list of members as the seed node.
      *
      * @param expectedSize expected size of each cluster
      */
     public void verifyClusterMetadata(final int expectedSize) {
         for (final Cluster cluster : instances.values()) {
             assertEquals(cluster.getClusterMetadata().size(), expectedSize);
         }
     }
 
     /**
      * Verify the number of Cluster instances that managed to start.
      *
      * @param expectedSize expected size of each cluster
      */
     public void verifyNumClusterInstances(final int expectedSize) {
         assertEquals(expectedSize, instances.size());
     }
 
     /**
      * Verify whether the cluster has converged {@code maxTries} times with a delay of @{code intervalInMs}
      * between attempts. This is used to give failure detector logic some time to kick in.
      *
      * @param expectedSize expected size of each cluster
      * @param maxTries number of tries to checkSubject if the cluster has stabilized.
      * @param intervalInMs the time duration between checks.
      */
     public void waitAndVerifyAgreement(final int expectedSize, final int maxTries, final int intervalInMs)
             throws InterruptedException {
         int tries = maxTries;
         while (--tries > 0) {
             boolean ready = true;
             final List<Endpoint> any = instances.entrySet().iterator().next().getValue().getMemberlist();
             for (final Cluster cluster : instances.values()) {
                 if (!(cluster.getMemberlist().size() == expectedSize
                         && cluster.getMemberlist().equals(any))) {
                     ready = false;
                    //  System.out.printf("Port %d not ready yet.", cluster.membershipService.myAddr.getPort());
                 }
             }
             if (!ready) {
                 Thread.sleep(intervalInMs);
             } else {
                 break;
             }
         }
 
         verifyCluster(expectedSize);
     }
 
     // Helper that provides a list of N random nodes that have already been added to the instances map
     public Set<Endpoint> getRandomHosts(final int N) {
         assert random != null;
         final List<Map.Entry<Endpoint, Cluster>> entries = new ArrayList<>(instances.entrySet());
         Collections.shuffle(entries);
         return random.ints(instances.size(), 0, N)
                      .mapToObj(i -> entries.get(i).getKey())
                      .collect(Collectors.toSet());
     }
 
     // Helper that provides a list of N random nodes from portStart to portEnd
     public Set<Endpoint> getRandomHosts(final int portStart, final int portEnd, final int N) {
         assert random != null;
         return random.ints(N, portStart, portEnd)
                 .mapToObj(i -> Utils.hostFromParts("127.0.0.7", i))
                 .collect(Collectors.toSet());
     }
 
     // Helper to use static-failure-detectors and inject interceptors
     public Cluster.Builder buildCluster(final Endpoint endpoint) {
         Cluster.Builder builder = new Cluster.Builder(endpoint, gossipType).useSettings(settings);
        //  if (useStaticFd) {
        //      final StaticFailureDetector.Factory fdFactory = new StaticFailureDetector.Factory(new HashSet<>());
        //      builder = builder.setEdgeFailureDetectorFactory(fdFactory);
        //      staticFds.put(endpoint, fdFactory);
        //  }
        //  if (serverInterceptors.containsKey(endpoint)) {
        //      builder = builder.setMessagingClientAndServer(new GrpcClient(endpoint, settings),
        //                                                    new TestingGrpcServer(endpoint,
        //                                                    serverInterceptors.get(endpoint),
        //                                                            settings.getUseInProcessTransport()));
        //  }
        //  if (clientInterceptors.containsKey(endpoint)) {
        //      builder = builder.setMessagingClientAndServer(new TestingGrpcClient(endpoint, settings,
        //                                                                          clientInterceptors.get(endpoint)),
        //              new TestingGrpcServer(endpoint,
        //                      Collections.emptyList(),
        //                      settings.getUseInProcessTransport()));
        //  }
         if (addMetadata) {
             final ByteString byteString = ByteString.copyFrom(endpoint.toString(), Charset.defaultCharset());
             builder = builder.setMetadata(Collections.singletonMap("Key", byteString));
         }
 
         return builder;
     }
 
    //  // Helper that drops the first N requests at a server of a given type
    //  public <T, E> void dropFirstNAtServer(final Endpoint endpoint, final int N,
    //                                         final RapidRequest.ContentCase contentCase) {
    //      serverInterceptors.computeIfAbsent(endpoint, (k) -> new ArrayList<>(1))
    //              .add(new ServerDropInterceptors.FirstN(N, contentCase));
    //  }
 
    //  // Helper that delays requests of a given type at the client
    //  public <T, E> CountDownLatch blockAtClient(final Endpoint endpoint, final RapidRequest.ContentCase messageType) {
    //      final CountDownLatch latch = new CountDownLatch(1);
    //      clientInterceptors.computeIfAbsent(endpoint, (k) -> new ArrayList<>(1))
    //              .add(new ClientInterceptors.Delayer(latch, messageType));
    //      return latch;
    //  }
 
     // This speeds up the retry attempts during the join protocol
     public void useShortJoinTimeouts() {
         settings.setGrpcTimeoutMs(100);
         settings.setGrpcJoinTimeoutMs(500); // use short timeouts
     }
 
     // This speeds up failure detection when using the PingPongFailureDetector
     public void useFastFailureDetectionTimeouts() {
         settings.setGrpcProbeTimeoutMs(110);
         settings.setFailureDetectorIntervalInMs(150);
     }
 }