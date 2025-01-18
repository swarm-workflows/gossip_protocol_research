package com.vrg.rapid.messaging.impl;
import com.vrg.rapid.pb.RapidResponse;

public class ResponseWithLatency {
    private final RapidResponse response;
    private final long latencyMs;

    public ResponseWithLatency(RapidResponse response, long latencyMs) {
        this.response = response;
        this.latencyMs = latencyMs;
    }

    public RapidResponse getResponse() {
        return response;
    }

    public long getLatencyMs() {
        return latencyMs;
    }
}