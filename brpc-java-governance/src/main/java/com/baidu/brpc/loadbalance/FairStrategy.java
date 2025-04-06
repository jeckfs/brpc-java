/*
 * Copyright (c) 2019 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.brpc.loadbalance;

import com.baidu.brpc.client.CommunicationClient;
import com.baidu.brpc.client.RpcClient;
import com.baidu.brpc.protocol.Request;
import com.baidu.brpc.utils.CollectionUtils;
import com.baidu.brpc.utils.CustomThreadFactory;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Fair load balance strategy aims to more reasonable distribution of traffic.
 * The principle of traffic distribution is not the same as pv, but the distribution of traffic according to performance,
 * the performance of the machine with multi-point traffic, the poor performance of the machine with less point traffic,
 * the same machine traffic with the same performance, and finally the service performance of all nodes tends to balance,
 * there will be no slow nodes to improve the overall performance of the service.
 *
 * <p>Algorithm overview:
 * The fair load balancing policy dynamically adjusts the weight of each server and divides it by weight.
 * Rpc client maintains a latency window record rpc time for each server, after processing a traffic, the server
 * updates its own window. If the mean value of the service in the window > the overall mean of the service, reduce its own weight.
 * On the contrary, increase its own weight. When the window is not full, the polling does not adjust the weight.
 *
 * <p/> 从先进先出的队列中每两个生成一个父节点，然后将父节点加入到队列。重复该过程直到队列为空。每个父节点的权重都是左右子节点的权重之和。
 * 这样构建的二叉树，所有的叶子节点构成了采样集合。
 * 现在要求按照weight大小从采样集合中进行抽取。思路是：随即生成一个 [0, root.weight] 的整数 randInt，若 randInt <= curNode.weight
 * 则元素在 curNode 的左子树，否则在右子树。由于父节点的weight是左右子节点之和，因此在查找右子树的时候，weight 的值要减去 curNode.left.weight
 * @author wangjiayin
 * @since 2018-09-03
 */
@Slf4j
public class FairStrategy implements LoadBalanceStrategy {
    private static final int TIMER_DELAY = 60;

    /**
     * The binary tree used to save weight number for each node.
     * We use a CopyOnWriteArrayList to safely lockless update the weight tree,
     * The first element fo the CopyOnWriteArrayList is useful.
     * The leaf elements of the weight tree are the real rpc servers, and the parent elements of the weight used
     * to save the sum weight of its left and right child's weight.
     * The root element's weight sum is the total sum of every leaf element's weight.
     *
     * <p>The weight tree can achieve time complexity at the O(logN) level, and 1000 servers require only 11 memory accesses.
     */
    protected CopyOnWriteArrayList<Node> treeContainer = new CopyOnWriteArrayList<Node>();
    private volatile Timer timer;
    private RpcClient rpcClient;
    private int latencyWindowSize; // {@see com.baidu.brpc.client.channel.BrpcPooledChannel.latencyWindow} 所表示队列的大小
    // {@see RpcClientOptions#activeInstancesRatioOfFairLoadBalance}
    private float activeInstancesRatio;
    // fair strategy will not work if the instances is less the minInstancesNum
    private int minInstancesNum = 3;
    private CopyOnWriteArrayList<CommunicationClient> invalidInstances
            = new CopyOnWriteArrayList<CommunicationClient>();
    private Random random = new Random(System.currentTimeMillis());

    @Override
    public void init(RpcClient rpcClient) {
        if (timer == null) {
            synchronized (this) {
                if (timer == null) {
                    timer = new HashedWheelTimer(new CustomThreadFactory("fairStrategy-timer-thread"));
                    timer.newTimeout(new TimerTask() {
                        @Override
                        public void run(Timeout timeout) {
                            updateWeightTree();
                            timer.newTimeout(this, TIMER_DELAY, TimeUnit.SECONDS);
                        }
                    }, TIMER_DELAY, TimeUnit.SECONDS);
                    this.rpcClient = rpcClient;
                    treeContainer = new CopyOnWriteArrayList<Node>();
                    invalidInstances = new CopyOnWriteArrayList<CommunicationClient>();
                    latencyWindowSize = rpcClient.getRpcClientOptions().getLatencyWindowSizeOfFairLoadBalance();
                    activeInstancesRatio = rpcClient.getRpcClientOptions().getActiveInstancesRatioOfFairLoadBalance();
                    if (latencyWindowSize <= 1) {
                        throw new IllegalArgumentException("latencyWindowSize must be greater than 1");
                    }
                }
            }
        }
    }

    @Override
    public CommunicationClient selectInstance(
            Request request,
            List<CommunicationClient> instances,
            Set<CommunicationClient> selectedInstances) {

        if (treeContainer.size() == 0) {
            return new RandomStrategy().selectInstance(request, instances, selectedInstances);
        }

        try {
            Node root = treeContainer.get(0);
            CommunicationClient instance = null;
            for (int i = 0; i < 3; i++) {
                instance = fairSelect(root);
                if (CollectionUtils.isNotEmpty(selectedInstances) && selectedInstances.contains(instance)) {
                    continue;
                }
                if (invalidInstances.contains(instance)) {
                    continue;
                }
                break;
            }
            // the invalidInstances list size is not very large.
            if ((CollectionUtils.isNotEmpty(selectedInstances) && selectedInstances.contains(instance))
                    || invalidInstances.contains(instance)) {
                // if the selected node is an invalid one, means the weight tree has not yet updated.
                // random reselect a new one
                log.debug("the selected one is invalid, begin to random reselect a new one...");
                return new RandomStrategy().selectInstance(request, instances, selectedInstances);
            }
            return instance;
        } catch (Exception e) {
            log.warn("FairStrategy select channel failed.", e);
            return new RandomStrategy().selectInstance(request, instances, selectedInstances);
        }

    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.stop();
        }
    }

    /**
     * Since the weight tree will update by a period of time, so if there's any invalid instance,
     * the business should notify the fair strategy.
     */
    public void markInvalidInstance(List<CommunicationClient> instances) {
        this.invalidInstances.addAll(instances);
    }

    protected long getRandomLong() {
        long randomIndex = random.nextLong();
        if (randomIndex < 0) {
            randomIndex = 0 - randomIndex;
        }
        return randomIndex;
    }

    protected CommunicationClient fairSelect(Node root) {
        int max = root.weight;
        int randomWeight = random.nextInt(max);
        Node selectNode = searchNode(root, randomWeight);
        return selectNode.instance;
    }

    protected Node searchNode(Node parent, int weight) {

        if (parent.left == null) {
            return parent;
        }

        if (parent.right == null) {
            return parent.left;
        }

        // parent.weight = left.weight + right.weight.
        // 当 left.weight >= weight 时，权重落在左子树
        // 否则权重落在右子树，此时右子树的权重 = parent.weight - left.weight
        // 所以在搜索右子树的时候搜索权重变为 weight - left.weight
        if (parent.left.weight >= weight) {
            return searchNode(parent.left, weight);
        } else {
            return searchNode(parent.right, weight - parent.left.weight);
        }

    }

    /**
     * Update weight of each node of the tree.
     * By create a new tree and insert into the head of the {@link #treeContainer}
     */
    protected void updateWeightTree() {
        log.debug("begin to updateWeightTree...");
        int timeout = rpcClient.getRpcClientOptions().getReadTimeoutMillis();
        // Create the leaf nodes of the weight tree
        Queue<Node> leafNodes = new LinkedList<Node>();
        List<CommunicationClient> instances = rpcClient.getNamingServiceProcessor().getInstances();
        if (CollectionUtils.isEmpty(instances)) {
            // if there're no healthy servers, skip create weight, use the random select algorithm instead
            return;
        }

        // the instances to build the weight tree
        List<CommunicationClient> fullWindowInstances = new LinkedList<CommunicationClient>();

        for (CommunicationClient instance : instances) {
            Queue<Integer> window = instance.getBrpcChannel().getLatencyWindow();
            // skip instances whose window is not full
            if (window.size() == latencyWindowSize) {
                fullWindowInstances.add(instance);
            }
        }

        // some conditions must be satisfied, if not, the fair strategy will not work and use random strategy instead
        if (fullWindowInstances.size() < minInstancesNum
                || fullWindowInstances.size() * 1.0 / instances.size() < activeInstancesRatio) {
            treeContainer = new CopyOnWriteArrayList<Node>();
            invalidInstances = new CopyOnWriteArrayList<CommunicationClient>();
            return;
        }

        // begin to build the weight tree
        for (CommunicationClient instance : fullWindowInstances) {
            int weight = calculateWeight(instance, timeout);
            leafNodes.add(new Node(instance.hashCode(), weight, true, instance));
        }

        // Now begin to create a new weight tree
        Node root = generateWeightTreeByLeafNodes(leafNodes);

        // Insert the new tree into the head of the container
        treeContainer.add(0, root);
        while (treeContainer.size() > 1) {
            // Remove the old weight tree
            treeContainer.remove(1);
        }

        // Since the weight tree has updated by healthy instances, we need to update invalid instances too.
        // Although there maybe new invalid instances added while updating the weight tree, for simplicity,
        // we just remove all invalid instances, at least brpc-java has the retry feature.
        invalidInstances = new CopyOnWriteArrayList<CommunicationClient>();
    }

    /**
     * Calculate the weight of a rpc server
     *
     * @param instance The instance of a rpc server
     * @param timeout Read timeout in millis
     * @return Weight num
     */
    protected int calculateWeight(CommunicationClient instance, int timeout) {
        Queue<Integer> window = instance.getBrpcChannel().getLatencyWindow();
        int avgLatency = 0;
        for (int latency : window) {
            avgLatency += latency;
        }
        // calculate the average latency
        avgLatency = avgLatency / window.size();
        // normalization to 1-100, to prevent inaccurate calculation of timer, plus a 10ms to the timeout num
        avgLatency = avgLatency * 100 / (timeout + 10);
        // calculate the final weight
        int weight = 100 - avgLatency;
        return weight > 0 ? weight : 1;
    }

    /**
     * generate the tree by leaf nodes
     * the leaf nodes are the real rpc servers
     * the parent nodes used to calculate the sum of it's children's weight
     *
     * @param leafNodes leaf nodes list
     * @return the root node of the tree
     */
    protected Node generateWeightTreeByLeafNodes(Queue<Node> leafNodes) {
        Queue<Node> nodes = new LinkedList<Node>(leafNodes);
        if (leafNodes.size() % 2 == 1) {
            nodes.add(Node.none);
        }

        Node root = new Node();

        while (nodes.size() > 0) {

            Node left = nodes.poll();
            Node right = nodes.poll();

            if (!left.isLeaf && right == null) {
                root = left;
                break;
            }

            Node parent = new Node(0, 0, false);
            parent.left = left;
            left.parent = parent;

            if (right != null && right != Node.none) {
                parent.right = right;
                parent.weight = left.weight + right.weight;
                right.parent = parent;
            } else {
                parent.weight = left.weight;
            }

            nodes.add(parent);
        }

        return root;
    }

    /**
     * The weight tree node
     */
    public static class Node {
        // empty node
        static Node none = new Node();
        int nodeId;
        int weight;
        boolean isLeaf;
        Node parent;
        Node left;
        Node right;
        CommunicationClient instance;

        public Node() {
        }

        public Node(int nodeId, int weight, boolean isLeaf) {
            this.nodeId = nodeId;
            this.weight = weight;
            this.isLeaf = isLeaf;
        }

        public Node(int nodeId, int weight, boolean isLeaf, CommunicationClient instance) {
            this.nodeId = nodeId;
            this.weight = weight;
            this.isLeaf = isLeaf;
            this.instance = instance;
        }
    }

}
