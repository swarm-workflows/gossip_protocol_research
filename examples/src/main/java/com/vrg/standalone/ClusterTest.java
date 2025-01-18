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
 import java.io.IOException;
 
//  import java.time.LocalDateTime;
//  import java.time.format.DateTimeFormatter;
 
 import com.google.protobuf.ByteString;
//  import com.vrg.rapid.messaging.impl.GrpcClient;
 import com.vrg.rapid.pb.Endpoint;
 import com.vrg.rapid.pb.FastRoundPhase2bMessage;
//  import com.vrg.rapid.pb.RapidRequest;
 
 import java.nio.charset.Charset;
 import java.util.ArrayList;
 import java.util.Collections;
//  import java.util.HashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Random;
 import java.util.Set;
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
     public final Map<Endpoint, Cluster> instances = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, StaticFailureDetector.Factory> staticFds = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, List<ServerDropInterceptors.FirstN>> serverInterceptors = new ConcurrentHashMap<>();
    //  public final Map<Endpoint, List<ClientInterceptors.Delayer>> clientInterceptors = new ConcurrentHashMap<>();
     public boolean useStaticFd = false;
     public boolean addMetadata = true;
     @Nullable public Random random = null;
     public long seed;
     public int basePort;
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
      public static void main(String[] args) {
        ClusterTest clusterTest = new ClusterTest();
        // clusterTest.beforeTest();
        int numNodes = 50;
        if (args.length > 0) {
            try {
                // 从命令行参数读取 numNodes
                numNodes = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid number format for numNodes. Using default value: 50");
            }
        }
    
        System.out.println("Number of nodes: " + numNodes);
    
        try{
            clusterTest.run(numNodes);
        }
        catch (final IOException | InterruptedException e) {
            // Handle exception if the thread is interrupted
            System.out.println("The sleep was interrupted!");
        }
        }

        ClusterTest() {
            basePort =  1234;
            portCounter = new AtomicInteger(basePort);
            instances.clear();
            seed = ThreadLocalRandom.current().nextLong();
            random = new Random(seed);
            settings = new Settings();
    
            // Tests need to opt out of the in-process channel
            settings.setUseInProcessTransport(true);
            // Tests need to set more aggressive frequent failure detection intervals if required
            settings.setFailureDetectorIntervalInMs(1000);
            useStaticFd = false;
            addMetadata = true;
        }

     public void run(final int numNodes) throws IOException, InterruptedException {
         addMetadata = false;
        //  final int numNodes = 50; // Includes the size of the cluster
         for (int i = 0; i < numNodes; i++) {
            basePort = 1234 + i;
            System.out.println("Borderline: baseport is " + basePort);
            final Endpoint seedEndpoint = Utils.hostFromParts("127.0.0.7", basePort);
            //  final Endpoint sourceEndpoint = Utils.hostFromParts("127.0.0.7", sourceEndpoint);
             createCluster(numNodes, seedEndpoint);
             verifyCluster(numNodes);
             verifyClusterMetadata(0);
             instances.get(seedEndpoint).membershipService.membershipView.reconstructDGRO();
             System.out.println("当前时间（毫秒精度）: " + System.currentTimeMillis()  +
          ", Endpoint: " + seedEndpoint);
         instances.get(seedEndpoint).membershipService.broadcaster.broadcast(
             Utils.toRapidRequest(FastRoundPhase2bMessage.getDefaultInstance()));
         
         try {
             // Pause the main process for 30 seconds (30,000 milliseconds)
             Thread.sleep(5000);
            //  for (final Cluster cluster: instances.values()) {
            //     cluster.shutdown();
            // }
            // instances.clear();
            waitAndShutdownClusters();
         } catch (final InterruptedException e) {
             // Handle exception if the thread is interrupted
             System.out.println("The sleep was interrupted!");
         }
        }
         
        //  System.out.println("Process resumed after 30 seconds.");
     }
     
     public void waitAndShutdownClusters() {
        final int numInstances = instances.size();
        if (numInstances == 0) {
            System.out.println("No clusters to shut down.");
            return;
        }
    
        CountDownLatch latch = new CountDownLatch(numInstances);
    
        // 遍历实例并启动异步关闭
        for (final Cluster cluster : instances.values()) {
            new Thread(() -> {
                try {
                    cluster.shutdown(); // 关闭每个 Cluster 实例
                    System.out.println("Cluster shutdown complete: " + cluster);
                } finally {
                    latch.countDown(); // 每完成一个实例，减少计数器
                }
            }).start();
        }
    
        try {
            // 主线程等待所有线程完成
            latch.await();
            System.out.println("All clusters have been shut down.");
        } catch (InterruptedException e) {
            System.err.println("Shutdown process was interrupted.");
            Thread.currentThread().interrupt();
        } finally {
            // 清理实例
            instances.clear();
            System.out.println("All instances have been cleared.");
        }
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
                if(currentport == basePort) continue; 
                executor.execute(() -> {
                     try {
                         final Endpoint joiningEndpoint =
                                //  Utils.hostFromParts("127.0.0.7", portCounter.incrementAndGet());
                                 Utils.hostFromParts("127.0.0.7", currentport);
                         final Cluster nonSeed = buildCluster(joiningEndpoint).join(seedEndpoint);
                         instances.put(joiningEndpoint, nonSeed);
                     } catch (final InterruptedException | IOException e) {
                         e.printStackTrace();
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
                                 Utils.hostFromParts("127.0.0.7", portCounter.incrementAndGet());
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
         Cluster.Builder builder = new Cluster.Builder(endpoint).useSettings(settings);
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
         settings.setGrpcProbeTimeoutMs(10);
         settings.setFailureDetectorIntervalInMs(50);
     }
 }