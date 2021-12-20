/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 * <p>
 * 最少活跃调用数，相同活跃数的随机，活跃数指调用前后计数差。
 * 使慢的提供者收到更少请求，因为越慢的提供者的调用前后计数差会越大。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    /**
     * 最小活跃数算法实现：
     * 假定有3台dubbo provider:
     * <p>
     * 10.0.0.1:20884, weight=2，active=2
     * 10.0.0.1:20886, weight=3，active=4
     * 10.0.0.1:20888, weight=4，active=3
     * active=2最小，且只有一个2，所以选择10.0.0.1:20884
     * <p>
     * 假定有3台dubbo provider:
     * <p>
     * 10.0.0.1:20884, weight=2，active=2
     * 10.0.0.1:20886, weight=3，active=2
     * 10.0.0.1:20888, weight=4，active=3
     * active=2最小，且有2个，所以从[10.0.0.1:20884,10.0.0.1:20886 ]中选择；
     * 接下来的算法与随机算法类似：
     * <p>
     * 假设offset=1（即random.nextInt(5)=1）
     * 1-2=-1<0？是，所以选中 10.0.0.1:20884, weight=2
     * 假设offset=4（即random.nextInt(5)=4）
     * 4-2=2<0？否，这时候offset=2， 2-3<0？是，所以选中 10.0.0.1:20886, weight=3
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 总个数
        int length = invokers.size();
        // 最小的活跃数
        int leastActive = -1;
        // 相同最小活跃数的个数
        int leastCount = 0;
        // 相同最小活跃数的下标
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        int[] weights = new int[length];
        // 总权重
        int totalWeight = 0;
        // 第一个权重，用于于计算是否相同
        int firstWeight = 0;
        // 是否所有权重相同
        boolean sameWeight = true;


        // 计算获得相同最小活跃数的数组和个数
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 活跃数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoker's configuration. The default value is 100.
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            // 发现更小的活跃数，重新开始
            if (leastActive == -1 || active < leastActive) {
                // 记录最小活跃数
                leastActive = active;
                // 重新统计相同最小活跃数的个数
                leastCount = 1;
                // 重新记录最小活跃数下标
                leastIndexes[0] = i;
                // 重新累计总权重
                totalWeight = afterWarmup;
                // 记录第一个权重
                firstWeight = afterWarmup;
                // 还原权重相同标识
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.
            }
            // 累计相同最小的活跃数
            else if (active == leastActive) {
                // 累计相同最小活跃数下标
                leastIndexes[leastCount++] = i;
                // 累计总权重
                totalWeight += afterWarmup;
                // 判断所有权重是否一样
                if (sameWeight && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers
        if (leastCount == 1) {
            // 如果只有一个最小则直接返回
            return invokers.get(leastIndexes[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            // 如果权重不相同且权重大于0则按总权重数随机
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 并确定随机值落在哪个片断上
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果权重相同或权重为0则均等随机
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}
