package com.vrg.standalone;

import java.io.IOException;

public class ClusterLauncher {
    public static void main(String[] args) {
        String configFile = "/home/cc/gossip_protocol_research/experiment.yaml"; // 默认配置文件名
        if (args.length > 0) {
            configFile = args[0]; // 允许从命令行指定 YAML 文件
        }

        // 检查配置文件是否存在
        java.io.File file = new java.io.File(configFile);
        if (!file.exists()) {
            throw new RuntimeException("Config file not found: " + configFile);
        }

        // 加载配置
        ExperimentConfig config = ExperimentConfig.loadConfig(configFile);

        // 打印配置
        System.out.println("\nLaunching Cluster with settings:");
        System.out.println("Base IP: " + config.getBaseIP());
        System.out.println("Port: " + config.getPort());
        System.out.println("Number of nodes: " + config.getNumNodes());
        System.out.println("Test ID: " + config.getTestID());
        System.out.println("Target nodes to wait: " + config.getTargetNodes());
        System.out.println("Node ID: " + config.getNodeId());

        // 传递配置到 ClusterTest
        ClusterTest clusterTest = new ClusterTest();
        try {
            clusterTest.setupCluster(
                config.getBaseIP(),
                config.getPort(),
                config.getNumNodes(),
                config.getTestID(),
                config.getTargetNodes(),
                config.getNodeId(),
                config.getTargetServers()
            );
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}