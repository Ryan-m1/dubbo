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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.URLStrParser;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR_ENCODED;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    /**
     * 默认 Zookeeper 根节点
     */
    private static final String DEFAULT_ROOT = "dubbo";

    /**
     * Zookeeper 根节点
     */
    private final String root;

    /**
     * Service 接口全名集合
     */
    private final Set<String> anyServices = new ConcurrentHashSet<>();

    /**
     * 监听器集合
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    /**
     * Zookeeper 客户端
     */
    private final ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获得 Zookeeper 根节点
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;
        // 创建 Zookeeper Client
        zkClient = zookeeperTransporter.connect(url);
        // 添加 StateListener 对象。该监听器，在重连时，调用恢复方法。
        zkClient.addStateListener((state) -> {
            if (state == StateListener.RECONNECTED) {
                logger.warn("Trying to fetch the latest urls, in case there're provider changes during connection loss.\n" + " Since ephemeral ZNode will not get deleted for a connection lose, " + "there's no need to re-register url of this instance.");
                ZookeeperRegistry.this.fetchLatestAddresses();
            } else if (state == StateListener.NEW_SESSION_CREATED) {
                logger.warn("Trying to re-register urls and re-subscribe listeners of this instance to registry...");
                try {
                    ZookeeperRegistry.this.recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } else if (state == StateListener.SESSION_LOST) {
                logger.warn("Url of this instance will be deleted from registry soon. " + "Dubbo client will try to re-register once a new session is created.");
            } else if (state == StateListener.SUSPENDED) {

            } else if (state == StateListener.CONNECTED) {

            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {
        try {
            //调用 #toUrlPath(url) 方法，获得 URL 的路径。
            //url.parameters.dynamic ，是否动态数据。若为 false ，该数据为持久数据，当注册方退出时，数据依然保存在注册中心。
            //调用 ZookeeperClient#create(url, ephemeral) 方法，创建 URL 节点
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 处理所有 Service 层的发起订阅，例如监控中心的订阅
            if (ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();
                // 获得 url 对应的监听器集合
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                // 获得 ChildListener 对象
                ChildListener zkListener = listeners.computeIfAbsent(listener, k -> (parentPath, currentChilds) -> {
                    for (String child : currentChilds) {
                        child = URL.decode(child);
                        if (!anyServices.contains(child)) {
                            anyServices.add(child);
                            // 新增 Service 接口全名时（即新增服务），发起该 Service 层的订阅
                            subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child, Constants.CHECK_KEY, String.valueOf(false)), k);
                        }
                    }
                });
                // 创建 Service 节点。该节点为持久节点。
                zkClient.create(root, false);
                // 向 Zookeeper ，Service 节点，发起订阅
                List<String> services = zkClient.addChildListener(root, zkListener);
                // 首次全量数据获取完成时，循环 Service 接口全名数组，发起该 Service 层的订阅
                if (CollectionUtils.isNotEmpty(services)) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service, Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            }
            // 处理指定 Service 层的发起订阅，例如服务消费者的订阅
            else {
                CountDownLatch latch = new CountDownLatch(1);
                try {
                    // 子节点数据数组
                    List<URL> urls = new ArrayList<>();
                    // 循环分类数组
                    for (String path : toCategoriesPath(url)) {
                        // 获得 url 对应的监听器集合
                        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                        // 获得 ChildListener 对象
                        ChildListener zkListener = listeners.computeIfAbsent(listener, k -> new RegistryChildListenerImpl(url, k, latch));
                        if (zkListener instanceof RegistryChildListenerImpl) {
                            ((RegistryChildListenerImpl) zkListener).setLatch(latch);
                        }
                        // 创建 Type 节点。该节点为持久节点。
                        zkClient.create(path, false);
                        // 向 Zookeeper ，PATH 节点，发起订阅
                        List<String> children = zkClient.addChildListener(path, zkListener);
                        // 添加到 `urls` 中
                        if (children != null) {
                            urls.addAll(toUrlsWithEmpty(url, path, children));
                        }
                    }
                    // 首次全量数据获取完成时，调用 `#notify(...)` 方法，回调 NotifyListener
                    notify(url, listener, urls);
                } finally {
                    // tells the listener to run only after the sync notification of main thread finishes.
                    latch.countDown();
                }
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.remove(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    // 向 Zookeeper ，移除订阅
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        // 向 Zookeeper ，移除订阅
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }

            if (listeners.isEmpty()) {
                zkListeners.remove(url);
            }
        }
    }

    /**
     * 查询符合条件的已注册数据，与订阅的推模式相对应，这里为拉模式，只返回一次结果。
     *
     * @param url 查询条件，不允许为空，如：consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @return 已注册信息列表，可能为空，含义同{@link com.alibaba.dubbo.registry.NotifyListener#notify(List<URL>)}的参数。
     * @see com.alibaba.dubbo.registry.NotifyListener#notify(List)
     */
    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            // 循环分类数组，获得所有的 URL 数组
            List<String> providers = new ArrayList<>();
            for (String path : toCategoriesPath(url)) {
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 匹配
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获得根目录
     * <p>
     * Root
     *
     * @return 路径
     */
    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    /**
     * Root
     *
     * @return 根路径
     */
    private String toRootPath() {
        return root;
    }

    /**
     * 获得服务路径
     * <p>
     * Root + Type
     *
     * @param url URL
     * @return 服务路径
     */
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    /**
     * 获得分类路径数组
     * <p>
     * Root + Service + Type
     *
     * @param url URL
     * @return 分类路径数组
     */
    private String[] toCategoriesPath(URL url) {
        // 获得分类数组
        String[] categories;
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }
        // 获得分类路径数组
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    /**
     * 获得分类路径
     * <p>
     * Root + Service + Type
     *
     * @param url URL
     * @return 分类路径
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    /**
     * 获得 URL 的路径
     * <p>
     * Root + Service + Type + URL
     * <p>
     * 被 {@link #doRegister(URL)} 和 {@link #doUnregister(URL)} 调用
     *
     * @param url URL
     * @return 路径
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    /**
     * 获得 providers 中，和 consumer 匹配的 URL 数组
     *
     * @param consumer  用于匹配 URL
     * @param providers 被匹配的 URL 的字符串
     * @return 匹配的 URL 数组
     */
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) {
                // 是 url
                if (provider.contains(PROTOCOL_SEPARATOR_ENCODED)) {
                    // 将字符串转化成 URL
                    URL url = URLStrParser.parseEncodedStr(provider);
                    // 匹配
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /**
     * 获得 providers 中，和 consumer 匹配的 URL 数组
     * <p>
     * 若不存在匹配，则创建 `empty://` 的 URL返回。通过这样的方式，可以处理类似服务提供者为空的情况。
     *
     * @param consumer  用于匹配 URL
     * @param path      被匹配的 URL 的字符串
     * @param providers 匹配的 URL 数组
     * @return 匹配的 URL 数组
     */
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // 获得 providers 中，和 consumer 匹配的 URL 数组
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        // 若不存在匹配，则创建 `empty://` 的 URL返回
        if (CollectionUtils.isEmpty(urls)) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer).setProtocol(EMPTY_PROTOCOL).addParameter(CATEGORY_KEY, category).build();
            urls.add(empty);
        }
        return urls;
    }

    /**
     * When zookeeper connection recovered from a connection loss, it need to fetch the latest provider list.
     * re-register watcher is only a side effect and is not mandate.
     */
    private void fetchLatestAddresses() {
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Fetching the latest urls of " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    removeFailedSubscribed(url, listener);
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    private class RegistryChildListenerImpl implements ChildListener {

        private URL url;

        private NotifyListener listener;

        private volatile CountDownLatch latch;

        RegistryChildListenerImpl(URL url, NotifyListener listener, CountDownLatch latch) {
            this.url = url;
            this.listener = listener;
            this.latch = latch;
        }

        void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void childChanged(String path, List<String> children) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Zookeeper children listener thread was interrupted unexpectedly, may cause race condition with the main thread.");
            }
            ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, path, children));
        }
    }

}
