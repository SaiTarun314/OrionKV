package com.orionkv.config;

import com.orionkv.model.NodeInfo;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orion")
public class ClusterProperties {
    private String localNodeId = "node-a";
    private int virtualNodesPerPhysical = 8;
    private Quorum quorum = new Quorum();
    private List<NodeEntry> nodes = new ArrayList<>();

    public String getLocalNodeId() {
        return localNodeId;
    }

    public void setLocalNodeId(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    public int getVirtualNodesPerPhysical() {
        return virtualNodesPerPhysical;
    }

    public void setVirtualNodesPerPhysical(int virtualNodesPerPhysical) {
        this.virtualNodesPerPhysical = virtualNodesPerPhysical;
    }

    public Quorum getQuorum() {
        return quorum;
    }

    public void setQuorum(Quorum quorum) {
        this.quorum = quorum;
    }

    public List<NodeEntry> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeEntry> nodes) {
        this.nodes = nodes;
    }

    public List<NodeInfo> toNodeInfos() {
        List<NodeInfo> result = new ArrayList<>();
        for (NodeEntry node : nodes) {
            result.add(new NodeInfo(node.getNodeId(), node.getHost(), node.getPort()));
        }
        return result;
    }

    public static class Quorum {
        private int n = 3;
        private int r = 2;
        private int w = 2;

        public int getN() {
            return n;
        }

        public void setN(int n) {
            this.n = n;
        }

        public int getR() {
            return r;
        }

        public void setR(int r) {
            this.r = r;
        }

        public int getW() {
            return w;
        }

        public void setW(int w) {
            this.w = w;
        }
    }

    public static class NodeEntry {
        private String nodeId;
        private String host;
        private int port;

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
