package com.vrg.standalone;
import java.io.InputStream;
import org.yaml.snakeyaml.Yaml;
import java.util.Map;

public class ExperimentConfig {
    private String baseIP;
    private int port;
    private int numNodes;
    private String testID;
    private int targetNodes;
    private int nodeId;

    // Getters
    public String getBaseIP() { return baseIP; }
    public int getPort() { return port; }
    public int getNumNodes() { return numNodes; }
    public String getTestID() { return testID; }
    public int getTargetNodes() { return targetNodes; }
    public int getNodeId() { return nodeId; }

    // 从 experiment.yaml 读取配置
    public static ExperimentConfig loadConfig(String filename) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new java.io.FileInputStream(filename)) {
            Map<String, Object> data = yaml.load(inputStream);

            ExperimentConfig config = new ExperimentConfig();
            config.baseIP = (String) data.getOrDefault("baseIP", "127.0.0.7");
            config.port = (int) data.getOrDefault("port", 1234);
            config.numNodes = (int) data.getOrDefault("numNodes", 10);
            config.testID = (String) data.getOrDefault("testID", "defaultTest");
            config.targetNodes = (int) data.getOrDefault("targetNodes", 10);
            config.nodeId = (int) data.getOrDefault("nodeId", -1);
            return config;
        } catch (Exception e) {
            throw new RuntimeException("Error loading configuration: " + e.getMessage(), e);
        }
    }
}