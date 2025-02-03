package com.vrg.standalone;
import java.io.InputStream;
import org.yaml.snakeyaml.Yaml;
import java.util.Map;

public class ExperimentConfig {
    private String baseIP;
    private String myIP;
    private int port;
    private int numNodes;
    private String testID;
    private int targetNodes;
    private int nodeId;
    private int targetServers;
    private int gossipType;

    // Getters
    public String getBaseIP() { return baseIP; }
    public String getMyIP() { return myIP; }
    public int getPort() { return port; }
    public int getNumNodes() { return numNodes; }
    public String getTestID() { return testID; }
    public int getTargetNodes() { return targetNodes; }
    public int getNodeId() { return nodeId; }
    public int getTargetServers() { return targetServers; }
    public int getGossipType() { return gossipType; }

    // 从 experiment.yaml 读取配置
    public static ExperimentConfig loadConfig(String filename) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new java.io.FileInputStream(filename)) {
            Map<String, Object> data = yaml.load(inputStream);

            ExperimentConfig config = new ExperimentConfig();
            config.baseIP = (String) data.getOrDefault("baseIP", "127.0.0.7");
            config.myIP = (String) data.getOrDefault("myIP", "127.0.0.7");
            config.port = (int) data.getOrDefault("port", 1234);
            config.numNodes = (int) data.getOrDefault("numNodes", 10);
            config.testID = (String) data.getOrDefault("testID", "defaultTest");
            config.targetNodes = (int) data.getOrDefault("targetNodes", 10);
            config.nodeId = (int) data.getOrDefault("nodeId", -1);
            config.targetServers = (int) data.getOrDefault("targetServers", 1);
            config.gossipType = (int) data.getOrDefault("gossipType", 0);

            return config;
        } catch (Exception e) {
            throw new RuntimeException("Error loading configuration: " + e.getMessage(), e);
        }
    }
}