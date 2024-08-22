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
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.TextFormat;
import com.vrg.rapid.pb.BatchedAlertMessage;
import com.vrg.rapid.pb.ConsensusResponse;
import com.vrg.rapid.pb.Endpoint;
import com.vrg.rapid.pb.FastRoundPhase2bMessage;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.NodeId;
import com.vrg.rapid.pb.Phase1aMessage;
import com.vrg.rapid.pb.Phase1bMessage;
import com.vrg.rapid.pb.Phase2aMessage;
import com.vrg.rapid.pb.Phase2bMessage;
import com.vrg.rapid.pb.PreJoinMessage;
import com.vrg.rapid.pb.ProbeMessage;
import com.vrg.rapid.pb.ProbeResponse;
import com.vrg.rapid.pb.RapidRequest;
import com.vrg.rapid.pb.RapidResponse;

import java.util.Collection;
import java.util.UUID;

/**
 * Utility methods to convert protobuf types
 */
final class Utils {

    private Utils() {
    }

    /**
     * Helpers for type conversions
     */
    static NodeId nodeIdFromUUID(final UUID uuid) {
        return NodeId.newBuilder().setHigh(uuid.getMostSignificantBits())
                                  .setLow(uuid.getLeastSignificantBits()).build();
    }

    /**
     * Validate incoming host:port strings using Guava's HostAndPort
     */
    static Endpoint hostFromString(final String hostString) {
        final HostAndPort hostAndPort = HostAndPort.fromString(hostString);    // Validates input
        return Endpoint.newBuilder().setHostname(ByteString.copyFromUtf8(hostAndPort.getHost()))
                                    .setPort(hostAndPort.getPort())
                                    .build();
    }

    /**
     * Validate incoming host:port strings using Guava's HostAndPort
     */
    static Endpoint hostFromParts(final String hostname, final int port) {
        final HostAndPort hostAndPort = HostAndPort.fromParts(hostname, port); // Validates input
        return Endpoint.newBuilder()
                .setHostname(ByteString.copyFromUtf8(hostAndPort.getHost()))
                .setPort(hostAndPort.getPort())
                .build();
    }

    /**
     * Protobuf messages have a toString() method that uses newlines, which does not bode
     * well with logging. This class allows a deferred toString() call on the protobuf object.
     */
    private static class Loggable<T extends GeneratedMessageV3> {
        private final T protobufObject;

        Loggable(final T protobufObject) {
            this.protobufObject = protobufObject;
        }

        @Override
        public String toString() {
            if (protobufObject instanceof Endpoint) {
                return ((Endpoint) protobufObject).getHostname().toStringUtf8()
                        + ":" + ((Endpoint) protobufObject).getPort();
            }
            else {
                return TextFormat.shortDebugString(protobufObject);
            }
        }
    }

    /**
     * Wraps a protobuf object such that it has a logging friendly toString().
     * @param object protobuf object
     * @param <T> Any protobuf generated type
     * @return a Loggable instance which wraps the protobuf object.
     */
    static <T extends GeneratedMessageV3> Loggable loggable(final T object) {
        return new Loggable<>(object);
    }

    /**
     * Protobuf messages have a toString() method that uses newlines, which does not bode
     * well with logging. This class accepts a collection of such protobuf objects and returns
     * a toString implementation without newlines.
     */
    private static class LoggableCollection {
        private final Collection<? extends GeneratedMessageV3> protobufObjects;

        LoggableCollection(final Collection<? extends GeneratedMessageV3> protobufObjects) {
            this.protobufObjects = protobufObjects;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (final GeneratedMessageV3 obj: protobufObjects) {
                sb.append(loggable(obj));
                sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Wraps a collection of protobuf objects such that it has a logging friendly toString().
     * @param object collection of protobuf objects
     * @return a Loggable instance which wraps the protobuf collection.
     */
    static LoggableCollection loggable(final Collection<? extends GeneratedMessageV3> object) {
        return new LoggableCollection(object);
    }

    /**
     * Helpers to avoid the boilerplate of constructing a new RapidRequest/RapidResponse for
     * every message we want to send out.
     */
    static RapidRequest toRapidRequest(final PreJoinMessage msg) {
        return RapidRequest.newBuilder().setPreJoinMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final JoinMessage msg) {
        return RapidRequest.newBuilder().setJoinMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final BatchedAlertMessage msg) {
        return RapidRequest.newBuilder().setBatchedAlertMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final ProbeMessage msg) {
        return RapidRequest.newBuilder().setProbeMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final FastRoundPhase2bMessage msg) {
        return RapidRequest.newBuilder().setFastRoundPhase2BMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final Phase1aMessage msg) {
        return RapidRequest.newBuilder().setPhase1AMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final Phase1bMessage msg) {
        return RapidRequest.newBuilder().setPhase1BMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final Phase2aMessage msg) {
        return RapidRequest.newBuilder().setPhase2AMessage(msg).build();
    }

    static RapidRequest toRapidRequest(final Phase2bMessage msg) {
        return RapidRequest.newBuilder().setPhase2BMessage(msg).build();
    }

    static RapidResponse toRapidResponse(final JoinResponse msg) {
        return RapidResponse.newBuilder().setJoinResponse(msg).build();
    }

    static RapidResponse toRapidResponse(final ConsensusResponse msg) {
        return RapidResponse.newBuilder().setConsensusResponse(msg).build();
    }

    static RapidResponse toRapidResponse(final ProbeResponse msg) {
        return RapidResponse.newBuilder().setProbeResponse(msg).build();
    }
}