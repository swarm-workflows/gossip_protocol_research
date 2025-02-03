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

 import com.google.common.collect.ImmutableList;
 import com.vrg.rapid.pb.Endpoint;
 import com.vrg.rapid.pb.JoinStatusCode;
 import com.vrg.rapid.pb.NodeId;
 import net.openhft.hashing.LongHashFunction;
 
 import javax.annotation.concurrent.GuardedBy;
 import javax.annotation.concurrent.ThreadSafe;
 import java.io.Serializable;
 import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
 import java.util.Collections;
 import java.util.Comparator;
 import java.util.HashMap;
 import java.util.HashSet;
// import java.util.LinkedHashSet;
import java.util.List;
 import java.util.Map;
 import java.util.NavigableSet;
 import java.util.Objects;
import java.util.Random;
import java.util.Set;
 import java.util.TreeSet;
 import java.util.concurrent.locks.ReadWriteLock;
 import java.util.concurrent.locks.ReentrantReadWriteLock;
 import java.util.stream.Collectors;
 
 /**
  * Hosts K permutations of the memberlist that represent the monitoring relationship between nodes;
  * every node (an observer) observers its successor (a subject) on each ring.
  */
 @ThreadSafe
 public final class MembershipView {
     public final int K;
     public final int M = 6;
    //  private final Random random = new Random();
    // private final double meanLatency = 50.0; // Example value
    // private final double stdDevLatency = 15; // Example value
    private final double meanLatency = 25.0; // Example value
    private final double stdDevLatency = 10; // Example value
    // private final List<Endpoint> subjects_record = new ArrayList<>();
    
    public final Map<String, Double> latencyCache = new HashMap<>();
     private static final LongHashFunction HASH_FUNCTION = LongHashFunction.xx(0);
     private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
     private final ReadWriteLock rwLockLatencyMap = new ReentrantReadWriteLock();
     @GuardedBy("rwLock") private final ArrayList<AddressComparator> addressComparators;
     @GuardedBy("rwLock") private final ArrayList<NavigableSet<Endpoint>> rings;
     @GuardedBy("rwLock") public List<Endpoint> subjects_dgro = new ArrayList<>();
     @GuardedBy("rwLock") public final ArrayList<List<Endpoint>> ringlist;
     @GuardedBy("rwLock") private final Set<NodeId> identifiersSeen = new TreeSet<>(NodeIdComparator.INSTANCE);
     @GuardedBy("rwLock") private final Map<Endpoint, List<Endpoint>> cachedObservers = new HashMap<>();
     @GuardedBy("rwLock") private final Set<Endpoint> allNodes = new HashSet<>();
     @GuardedBy("rwLock") private long currentConfigurationId = -1;
     @GuardedBy("rwLock") private Configuration currentConfiguration;
     @GuardedBy("rwLock") private boolean shouldUpdateConfigurationId = true;
     @GuardedBy("rwLockLatencyMap") public Map<Endpoint, Map<String, Long>> latencyMap = new HashMap<>();

     
 
     MembershipView(final int K) {
         assert K > 0;
         this.K = K;
         this.rings = new ArrayList<>(K);
        //  this.rings = new ArrayList<>(K - 1);
         this.ringlist = new ArrayList<>(K);
         this.addressComparators = new ArrayList<>(K);
        //  this.addressComparators = new ArrayList<>(K - 1);
        //  for (int k = 0; k < K - 1; k++) {
         for (int k = 0; k < K; k++) {
             final AddressComparator comparatorWithSeed = new AddressComparator(k);
             this.addressComparators.add(comparatorWithSeed);
             this.rings.add(new TreeSet<>(comparatorWithSeed));
             this.ringlist.add(new ArrayList<Endpoint>());
         }
         this.currentConfiguration = new Configuration(identifiersSeen, rings.get(0));
     }
 
     /**
      * Used to bootstrap a membership view from the fields of a MembershipView.Settings object.
      */
     MembershipView(final int K, final Collection<NodeId> nodeIds, final Collection<Endpoint> endpoints,
    //   final Endpoint node,  final Map<Endpoint, Map<Endpoint, Long>> latencyMap) {
      final Endpoint node) {
         assert K > 0;
         this.K = K;
        //  this.rings = new ArrayList<>(K - 1);
         this.rings = new ArrayList<>(K);
         this.ringlist = new ArrayList<>(K);
        //  this.addressComparators = new ArrayList<>(K - 1);
         this.addressComparators = new ArrayList<>(K);
        //  for (int k = 0; k < K - 1; k++) {
         for (int k = 0; k < K; k++) {
             final AddressComparator comparatorWithSeed = new AddressComparator(k);
             this.addressComparators.add(comparatorWithSeed);
             final TreeSet<Endpoint> set = new TreeSet<>(comparatorWithSeed);
             set.addAll(endpoints);
             allNodes.addAll(endpoints);
             this.rings.add(set);
         }
        //  final List<Endpoint> endpointList = new ArrayList<>(endpoints);
         for (int k = 0; k < K; k++) {
            // this.ringlist.add(DGRO(endpointList, k, latencyMap));
            // List<Endpoint> tmp = getRing(k);
            List<Endpoint> endpointList = new ArrayList<>(endpoints);
            Collections.shuffle(endpointList);
            this.ringlist.add(endpointList);
         }
         this.subjects_dgro.clear();
         for (int k = 0; k < this.M; ++k) {
            final Endpoint ep = ringlist.get(k).get((ringlist.get(k).indexOf(node) - 1 + getMembershipSize())
            % getMembershipSize());
            // subjects_record.add(ep);
            this.subjects_dgro.add(ep);
       }
         this.identifiersSeen.addAll(nodeIds);
         this.currentConfiguration = new Configuration(identifiersSeen, rings.get(0));
     }


     public double getLatency(final Endpoint sender, final Endpoint receiver) {
        // Generate a unique key for the pair (sender, receiver)
        final String key = sender.getPort() < receiver.getPort()
                ? sender.getPort() + "-" + receiver.getPort()
                : receiver.getPort() + "-" + sender.getPort();

        // Check if the latency for this pair is already computed
        if (latencyCache.containsKey(key)) {
            return latencyCache.get(key);
        }
        
        final Random random = new Random();
        // Combine sender and receiver port into a hash for determinism
        final int hash_1 = Integer.hashCode(sender.getPort() * 31 + receiver.getPort());
        final int hash_2 = Integer.hashCode(receiver.getPort() * 31 + sender.getPort());
        final int combinedHash = hash_1 ^ hash_2;
        random.setSeed(combinedHash); // Seed the random generator
        
        // Generate a Gaussian value and scale it to mean and standard deviation
        final double gaussian = random.nextGaussian();
        // Ensure latency is non-negative
        // final double latency = Math.min(100, Math.max(meanLatency + gaussian * stdDevLatency, 10));
        // final double latency = Math.max(meanLatency + gaussian * stdDevLatency, 10);
        final double latency = Math.min(Math.max(meanLatency + gaussian * stdDevLatency, 10), 50);

        // Cache the computed latency for future use
        latencyCache.put(key, latency);

        return latency;
    }

    public void updateLatencyMap(Endpoint sender, Map<String, Long> senderLatencyMap){
        rwLockLatencyMap.writeLock().lock();
        try{
        latencyMap.put(sender, senderLatencyMap);
        } finally {
            rwLockLatencyMap.writeLock().unlock();
        }
        return;
    }
    
    // public void reconstructDGRO(final Endpoint node, final Map<Endpoint, Map<Endpoint, Long>> latencyMap) {
    public void reconstructDGRO(final Endpoint node) {
        rwLock.writeLock().lock();
        try{
        final List<Endpoint> endpointList = getRing(0);
        subjects_dgro.clear();
        for (int k = 0; k < K; k++) {
            // ringlist.set(k, DGRO(endpointList, k, latencyMap));
            ringlist.set(k, DGRO(endpointList, k));
            // ringlist.get(k) = DGRO(endpointList, k);
         }
    //     for (int k = 0; k < M; ++k) {
    //         final Endpoint ep = ringlist.get(k).get((ringlist.get(k).indexOf(node) - 1 + getMembershipSize())
    //         % getMembershipSize());
    //         // subjects_record.add(ep);
    //         subjects_dgro.add(ep);
    //    }

    for (int k = 0; k < 1; ++k) {
                final Endpoint ep = ringlist.get(k).get((ringlist.get(k).indexOf(node) - 1 + getMembershipSize())
                % getMembershipSize());
                // subjects_record.add(ep);
                subjects_dgro.add(ep);
           }
        Map<String, Long> tmp =  latencyMap.getOrDefault(node, Collections.emptyMap());
         List<String> topEndpoints = tmp.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()) // 按值 (latency) 排序
                .limit(M) // 取前 8 个
                .map(Map.Entry::getKey) // 只取 key（endpoint）
                .collect(Collectors.toList());
                for(int i = 0; i < topEndpoints.size(); i++){
                    subjects_dgro.add(Utils.hostFromString(topEndpoints.get(i)));
                }

                // 2️⃣ 如果不足 8 个, 需要填充随机 endpoint
                int remaining = M - subjects_dgro.size();
                for (int i = 0; i < remaining; i++) {
                    Endpoint seedString = node;
                    int seed = seedString.hashCode() + i; // 生成不同的 seed
                    Random random = new Random(seed);
                    int max = ringlist.get(0).size();
                    int randomInt = random.nextInt(max);
                    subjects_dgro.add(ringlist.get(0).get(randomInt % rings.get(0).size()));
                }
        } finally {
        rwLock.writeLock().unlock();
    }
    }

    //  public List<Endpoint> DGRO(final List<Endpoint> endpoints, final int k, final Map<Endpoint, Map<Endpoint, Long>> latencyMap) {
     public List<Endpoint> DGRO(final List<Endpoint> endpoints, final int k) {
        int currentNode = k % endpoints.size();
        final List<Endpoint> newOrder = new ArrayList<>();
        final int[] degree = new int[endpoints.size()];
        Arrays.fill(degree, 0);
        rwLockLatencyMap.readLock().lock();
        try{
            for (int i = 0; i < endpoints.size(); i++) {
                newOrder.add(endpoints.get(currentNode));
                degree[currentNode]++;
                double minLatency = Double.MAX_VALUE;
                int selectedNode = -1;
                Map<String, Long> tmp =  latencyMap.getOrDefault(endpoints.get(currentNode), Collections.emptyMap());
                for (int j = 0; j < endpoints.size(); j++) {
                    if (degree[j] != 0) {
                        continue;
                    }
                    // final double latency = latencyMap.get(endpoints.get(currentNode)).get(Utils.stringFromHost(endpoints.get(j)));
                    final double latency = tmp.getOrDefault(Utils.stringFromHost(endpoints.get(j)), 100L);
                                //  .getOrDefault(Utils.stringFromHost(endpoints.get(j)), (long)getLatency(endpoints.get(currentNode), endpoints.get(j)));
                    // if(latency != 100L)
                    //             System.out.println(endpoints.get(currentNode) + " to " + endpoints.get(j) + ": latencyMap=" + latency + " getlatency=" 
                    //             + getLatency(endpoints.get(currentNode), endpoints.get(j)));
                    if (latency < minLatency) {
                        selectedNode = j;
                        minLatency = latency;
                    }
                }
                currentNode = (i == endpoints.size() - 1) ? k % endpoints.size() : selectedNode;
            }
        } finally {
            rwLockLatencyMap.readLock().unlock();
        }

        return newOrder;
    }

 
     /**
      * Queries if a host with a logical identifier {@code uuid} is safe to add to the network.
      *
      * @param node the joining node
      * @param uuid the joining node's identifier.
      * @return HOSTNAME_ALREADY_IN_RING if the {@code node} is already in the ring.
      *         UUID_ALREADY_IN_RING if the {@code uuid} is already seen before.
      *         SAFE_TO_JOIN otherwise.
      */
     JoinStatusCode isSafeToJoin(final Endpoint node, final NodeId uuid) {
         rwLock.readLock().lock();
         try {
             if (allNodes.contains(node)) {
                 return JoinStatusCode.HOSTNAME_ALREADY_IN_RING;
             }
 
             if (identifiersSeen.contains(uuid)) {
                 return JoinStatusCode.UUID_ALREADY_IN_RING;
             }
 
             return JoinStatusCode.SAFE_TO_JOIN;
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Add a node to all K rings and records its unique identifier
      *
      * @param node the node to be added
      * @param nodeId the logical identifier of the node being added
      */
     void ringAdd(final Endpoint node, final NodeId nodeId) {
         Objects.requireNonNull(node);
         Objects.requireNonNull(nodeId);
 
         if (isIdentifierPresent(nodeId)) {
             throw new UUIDAlreadySeenException(node, nodeId);
         }
 
         rwLock.writeLock().lock();
         try {
             if (rings.get(0).contains(node)) {
                 throw new NodeAlreadyInRingException(node);
             }
 
             final Set<Endpoint> affectedSubjects = new HashSet<>();
 
            //  for (int k = 0; k < K - 1; k++) {
             for (int k = 0; k < K; k++) {
                 final NavigableSet<Endpoint> endpoints = rings.get(k);
                 endpoints.add(node);
 
                 final Endpoint subject = endpoints.lower(node);
                 if (subject != null) {
                     affectedSubjects.add(subject);
                 }
             }
            //  addDGRO(node);

             allNodes.add(node);
 
             for (final Endpoint subject : affectedSubjects) {
                 cachedObservers.remove(subject);
             }
 
             identifiersSeen.add(nodeId);
 
             shouldUpdateConfigurationId = true;
         } finally {
             rwLock.writeLock().unlock();
         }
     }

     void addDGRO(final Endpoint node) {
        int selectedNode = 0;
        double minLatency = Double.MAX_VALUE;
        // double prev_latency = 0;
        double latency = 0;
        for (int k = 0; k < ringlist.size(); k++) {
            for (int i = 0; i < ringlist.get(k).size(); i++) {
                latency = getLatency(node, ringlist.get(k).get(i)) + 
                getLatency(node, ringlist.get(k).get((i + 1) % ringlist.get(k).size()));
                if (latency < minLatency) {
                    selectedNode = (i + 1) % ringlist.get(k).size();
                    minLatency = latency;
                    // break;
                }
            }
            // ringlist.get(k).add((k + 1) % ringlist.get(k).size(), node);
            ringlist.get(k).add((selectedNode + k) % ringlist.get(k).size(), node);
        }
        
        // ringlist.add(0, node);
    }
 
     /**
      * Delete a host from all K rings.
      *
      * @param node the host to be removed
      */
     void ringDelete(final Endpoint node) {
         Objects.requireNonNull(node);
         rwLock.writeLock().lock();
         try {
 
             if (!rings.get(0).contains(node)) {
                 throw new NodeNotInRingException(node);
             }
 
             final Set<Endpoint> affectedSubjects = new HashSet<>();
 
            //  for (int k = 0; k < K - 1; k++) {
             for (int k = 0; k < K; k++) {
                 final NavigableSet<Endpoint> endpoints = rings.get(k);
 
                 final Endpoint oldSubject = endpoints.lower(node);
                 if (oldSubject != null) {
                     affectedSubjects.add(oldSubject);
                 }
 
                 endpoints.remove(node);
 
                 addressComparators.get(k).removeEndpoint(node);
                 cachedObservers.remove(node);
             }
             deleteDGRO(node);
             allNodes.remove(node);
 
             for (final Endpoint subject : affectedSubjects) {
                 cachedObservers.remove(subject);
             }
 
             shouldUpdateConfigurationId = true;
         } finally {
             rwLock.writeLock().unlock();
         }
     }
     

     void deleteDGRO(final Endpoint node) {
        for (int k = 0; k < ringlist.size(); k++) {
            for (int i = 0; i < ringlist.get(k).size(); i++) {
        //    if(ringlist.get(i) == node){
        //     ringlist.remove(i);
        //     break;
        //    }
                if (ringlist.get(k).get(i).equals(node)) {
                    ringlist.get(k).remove(i);
                    break;
                }
            }
        }
    }
     
     /**
      * Returns the set of observers for {@code node}
 
      * @param node input node
      * @return the set of observers for {@code node}
      * @throws NodeNotInRingException thrown if {@code node} is not in the ring
      */
     List<Endpoint> getObserversOf(final Endpoint node) {
         Objects.requireNonNull(node);
         rwLock.readLock().lock();
         try {
             if (!allNodes.contains(node)) {
                 throw new NodeNotInRingException(node);
             }
             // if (!cachedObservers.containsKey(node)) {
             cachedObservers.put(node, computeObserversOf(node));
             // }
             return cachedObservers.get(node);
         } finally {
             rwLock.readLock().unlock();
         }
     }

     List<Endpoint> getGossipInOf(final Endpoint node) {
        Objects.requireNonNull(node);
        rwLock.readLock().lock();
        try {
            if (!allNodes.contains(node)) {
                throw new NodeNotInRingException(node);
            }
            // if (!cachedObservers.containsKey(node)) {
            cachedObservers.put(node, computeGossipInOf(node));
            // }
            return cachedObservers.get(node);
        } finally {
            rwLock.readLock().unlock();
        }
    }
 
     /**
      * Computes the set of observers for {@code node}.
      * Only call this from a (thread-)safe place!
      *
      * @param node input node
      * @return the set of observers for {@code node}
      * @throws NodeNotInRingException thrown if {@code node} is not in the ring
      */
     private List<Endpoint> computeObserversOf(final Endpoint node) {
         Objects.requireNonNull(node);
         if (!rings.get(0).contains(node)) {
             throw new NodeNotInRingException(node);
         }
 
         if (rings.get(0).size() <= 1) {
             return Collections.emptyList();
         }
 
         final List<Endpoint> observers = new ArrayList<>();
 
        //  for (int k = 0; k < K - 1; k++) {
         for (int k = 0; k < K; k++) {
             final NavigableSet<Endpoint> list = rings.get(k);
             // final ArrayList<Endpoint> list = new ArrayList<>(list_);
             final Endpoint successor = list.higher(node);
             // final Endpoint successor = list.higher(node);
             if (successor == null) {
                 observers.add(list.first());
             }
             else {
                 observers.add(successor);
             }
         }
         return observers;
     }
    

     private List<Endpoint> computeGossipInOf(final Endpoint node) {
        Objects.requireNonNull(node);
        if (!rings.get(0).contains(node)) {
            throw new NodeNotInRingException(node);
        }

        if (rings.get(0).size() <= 1) {
            return Collections.emptyList();
        }

        final List<Endpoint> observers = new ArrayList<>();
       for (int k = 0; k < ringlist.size(); ++k) {
        observers.add(ringlist.get(k).get((ringlist.get(k).indexOf(node) + 1)
         % getMembershipSize()));
       }

        return observers;
    }
 
     /**
      * Returns the set of nodes monitored by {@code node}
 
      * @param node input node
      * @return the set of nodes monitored by {@code node}
      * @throws NodeNotInRingException thrown if {@code node} is not in the ring
      */
     List<Endpoint> getSubjectsOf(final Endpoint node) {
         Objects.requireNonNull(node);
         rwLock.readLock().lock();
         try {
             if (!allNodes.contains(node)) {
                 throw new NodeNotInRingException(node);
             }
 
             if (rings.get(0).size() <= 1) {
                 return Collections.emptyList();
             }
            //  computeGossipOutOf(node);
             return getPredecessorsOf(node);
         } finally {
             rwLock.readLock().unlock();
         }
     }

     public List<Endpoint> getGossipOutOf(final Endpoint node) {
        Objects.requireNonNull(node);
        rwLock.readLock().lock();
        try {
            if (!allNodes.contains(node)) {
                throw new NodeNotInRingException(node);
            }

            if (rings.get(0).size() <= 1) {
                return Collections.emptyList();
            }
            // getPredecessorsOf(node);
            return computeGossipOutOf(node);
        } finally {
            rwLock.readLock().unlock();
        }
    }
 
     /**
      * Returns the expected observers of {@code node}, even before it is
      * added to the ring. Used during the bootstrap protocol to identify
      * the nodes responsible for gatekeeping a joining peer.
      *
      * @param node input node
      * @return the list of nodes monitored by {@code node}. Empty list if the membership is empty.
      */
     List<Endpoint> getExpectedObserversOf(final Endpoint node) {
         Objects.requireNonNull(node);
         rwLock.readLock().lock();
         try {
             if (rings.get(0).isEmpty()) {
                 return Collections.emptyList();
             }
             return getPredecessorsOf(node);
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Used by getExpectedObserversOf() and getObserversOf().
      */
     private List<Endpoint> getPredecessorsOf(final Endpoint node) {
         final List<Endpoint> subjects = new ArrayList<>();
 
        //  for (int k = 0; k < K - 1; k++) {
         for (int k = 0; k < K; k++) {
             final NavigableSet<Endpoint> list = rings.get(k);
             final Endpoint predecessor = list.lower(node);
             if (predecessor == null) {
                 subjects.add(list.last());
             }
             else {
                 subjects.add(predecessor);
             }
         }
        
         return subjects;
     }

     private List<Endpoint> computeGossipOutOf(final Endpoint node) {
    //     if(subjects_dgro.size() == 0){
    //         System.out.println(node + " subject_dgro size is 0");
    //     for (int k = 0; k < M; ++k) {
    //         final Endpoint ep = ringlist.get(k).get((ringlist.get(k).indexOf(node) - 1 + getMembershipSize())
    //         % getMembershipSize());
    //         // subjects_record.add(ep);
    //         subjects_dgro.add(ep);
    //    }
    // }
    //    final List<Endpoint> subjects = new ArrayList<>();
       // subjects_record.clear();
    //    for (int k = 0; k < M; k++) {
       // for (int k = 0; k < K; k++) {
        //    final NavigableSet<Endpoint> list = rings.get(k);
        //    final Endpoint predecessor = list.lower(node);
        //    if (predecessor == null) {
        //        subjects.add(list.last());
        //    } 
        //    else {
        //        subjects.add(predecessor);
        //    }
    //    }
    //    for (int k = 0; k < M; k++) {
    //      String seedString = node.toString();
    //      int seed = seedString.hashCode() + k; // 生成确定性 seed
    //      Random random = new Random(seed);
    //      int min = 0, max = ringlist.get(0).size();
    //      int randomInt = random.nextInt(max - min + 1) + min;
    //      subjects.add(ringlist.get(0).get(randomInt % rings.get(k).size()));
    //     }
    // return subjects;
    return subjects_dgro;
    }
 
     /**
      * Query if a host is part of the current membership set.
      *
      * @param address the host
      * @return True if the node is present in the membership view and false otherwise.
      */
     boolean isHostPresent(final Endpoint address) {
         rwLock.readLock().lock();
         try {
             return allNodes.contains(address);
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Query if an identifier has been used by a node already.
      *
      * @param identifier the identifier to query for
      * @return True if the identifier has been seen before and false otherwise.
      */
     boolean isIdentifierPresent(final NodeId identifier) {
         rwLock.readLock().lock();
         try {
             return identifiersSeen.contains(identifier);
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Get the current identifier of the configuration. Computed based on the
      * set of nodes in the view as well as the identifiers seen so far.
      *
      * @return the current configuration identifier.
      */
     long getCurrentConfigurationId() {
         rwLock.readLock().lock();
         try {
             if (shouldUpdateConfigurationId) {
                 updateCurrentConfigurationId();
                 shouldUpdateConfigurationId = false;
             }
             return currentConfigurationId;
         }
         finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Get the list of endpoints in the k'th ring.
      *
      * @param k the index of the ring to query
      * @return the list of endpoints in the k'th ring.
      */
     List<Endpoint> getRing(final int k) {
         rwLock.readLock().lock();
         try {
             assert k >= 0;
             return ImmutableList.copyOf(rings.get(k));
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Get the ring number of an observer for a given subject
      *
      * @param observer The observer node
      * @param subject The subject node
      * @return the indexes k such that {@code subject} is a successor of {@code subject} on ring[k].
      */
     List<Integer> getRingNumbers(final Endpoint observer, final Endpoint subject) {
         rwLock.readLock().lock();
         try {
             // TODO: do this in one scan
             final List<Endpoint> subjects = getSubjectsOf(observer);
             if (subjects.isEmpty()) {
                 return Collections.emptyList();
             }
 
             final List<Integer> ringIndexes = new ArrayList<>();
             int ringNumber = 0;
             for (final Endpoint node: subjects) {
                 if (node.equals(subject)) {
                     ringIndexes.add(ringNumber);
                 }
                 ringNumber++;
             }
             return ringIndexes;
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * Get the number of nodes currently in the membership.
      *
      * @return the number of nodes in the membership.
      */
     int getMembershipSize() {
         rwLock.readLock().lock();
         try {
             return rings.get(0).size();
         } finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * XXX: May not be stable across processes. Verify.
      */
     @GuardedBy("rwLock")
     private void updateCurrentConfigurationId() {
         currentConfiguration = new Configuration(identifiersSeen, rings.get(0));
         currentConfigurationId = currentConfiguration.getConfigurationId();
     }
 
     /**
      * Get a Settings object that contains the list of nodes in the membership view
      * as well as the identifiers seen so far. These two lists suffice to bootstrap an
      * identical copy of the MembershipView object.
      *
      * @return a {@code Settings} object.
      */
     Configuration getConfiguration() {
         rwLock.readLock().lock();
         try {
             if (shouldUpdateConfigurationId) {
                 updateCurrentConfigurationId();
                 shouldUpdateConfigurationId = false;
             }
             return currentConfiguration;
         }
         finally {
             rwLock.readLock().unlock();
         }
     }
 
     /**
      * The address comparator for ring 0. Can be used by clients to present a consistent
      * sort order for a list of endpoints.
      *
      * @return address comparator with ring 0 (and seed 0)
      */
     AddressComparator getRingZeroComparator() {
         return addressComparators.get(0);
     }
 
     private static final class NodeIdComparator implements Comparator<NodeId>, Serializable {
         private static final long serialVersionUID = -4891729395L;
         private static final NodeIdComparator INSTANCE = new NodeIdComparator();
 
         private NodeIdComparator() {
         }
 
         @Override
         public int compare(final NodeId o1, final NodeId o2) {
             // First, compare high bits
             if (o1.getHigh() < o2.getHigh()) {
                 return -1;
             }
             if (o1.getHigh() > o2.getHigh()) {
                 return 1;
             }
             // High bits are equal, so compare low bits
             if (o1.getLow() < o2.getLow()) {
                 return -1;
             }
             if (o1.getLow() > o2.getLow()) {
                 return 1;
             }
             // High and low bits are equal
             return 0;
         }
     }
 
     static class NodeAlreadyInRingException extends RuntimeException {
         NodeAlreadyInRingException(final Endpoint node) {
             super(node.toString());
         }
     }
 
     static class NodeNotInRingException extends RuntimeException {
         NodeNotInRingException(final Endpoint node) {
             super(node.toString());
         }
     }
 
     static class UUIDAlreadySeenException extends RuntimeException {
         UUIDAlreadySeenException(final Endpoint node, final NodeId nodeId) {
             super("Endpoint add attempt with identifier already seen:" +
                     " {host: " + node + ", identifier: " + nodeId + "}:");
         }
     }
 
     /**
      * The Settings object contains a list of nodes in the membership view as well as a list of UUIDs.
      * An instance of this object created from one MembershipView object contains the necessary information
      * to bootstrap an identical MembershipView object.
      */
     static class Configuration {
         final List<NodeId> nodeIds;
         final List<Endpoint> endpoints;
 
         public Configuration(final Set<NodeId> nodeIds, final Set<Endpoint> endpoints) {
             this.nodeIds = ImmutableList.copyOf(nodeIds);
             this.endpoints = ImmutableList.copyOf(endpoints);
         }
 
         /**
          * Gets the configuration ID for the list of endpoints and identifiers.
          *
          * @return a configuration identifier.
          */
         public long getConfigurationId() {
             return getConfigurationId(this.nodeIds, this.endpoints);
         }
 
         static long getConfigurationId(final Collection<NodeId> identifiers,
                                        final Collection<Endpoint> endpoints) {
             long hash = 1;
             for (final NodeId id: identifiers) {
                 hash = hash * 37 + HASH_FUNCTION.hashLong(id.getHigh());
                 hash = hash * 37 + HASH_FUNCTION.hashLong(id.getLow());
             }
             for (final Endpoint endpoint : endpoints) {
                 hash = hash * 37 + HASH_FUNCTION.hashBytes(endpoint.getHostname().asReadOnlyByteBuffer());
                 hash = hash * 37 + HASH_FUNCTION.hashInt(endpoint.getPort());
             }
             return hash;
         }
     }
 
     /**
      * Used to order endpoints in the different rings.
      */
     static final class AddressComparator implements Comparator<Endpoint>, Serializable {
         private static final long serialVersionUID = -4891729390L;
         private final LongHashFunction hashFunction;
         private final Map<Endpoint, Long> hashCache;
 
         AddressComparator(final int seed) {
             this.hashFunction = LongHashFunction.xx(seed);
             this.hashCache = new HashMap<>();
         }
 
         @Override
         public final int compare(final Endpoint c1, final Endpoint c2) {
             final long hash1 = hashCache.computeIfAbsent(c1, this::computeHash);
             final long hash2 = hashCache.computeIfAbsent(c2, this::computeHash);
             return Long.compare(hash1, hash2);
         }
 
         private long computeHash(final Endpoint endpoint) {
             return hashFunction.hashBytes(endpoint.getHostname().asReadOnlyByteBuffer()) * 31
                     + hashFunction.hashInt(endpoint.getPort());
         }
 
         void removeEndpoint(final Endpoint endpoint) {
             hashCache.remove(endpoint);
         }
     }
 }