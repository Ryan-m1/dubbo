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
package org.apache.dubbo.cache;

/**
 * Cache interface to support storing and retrieval of value against a lookup key. It has two operation <b>get</b> and <b>put</b>.
 * <li><b>put</b>-Storing value against a key.</li>
 * <li><b>get</b>-Retrieval of object.</li>
 *
 * @see org.apache.dubbo.cache.support.lru.LruCache
 * @see org.apache.dubbo.cache.support.jcache.JCache
 * @see org.apache.dubbo.cache.support.expiring.ExpiringCache
 * @see org.apache.dubbo.cache.support.threadlocal.ThreadLocalCache
 * <p>
 * 结果缓存 ，用于加速热门数据的访问速度，Dubbo 提供声明式缓存，以减少用户加缓存的工作量。
 */
public interface Cache {

    /**
     * 添加键值
     *
     * @param key 键
     * @param value 值
     */
    void put(Object key, Object value);

    /**
     * 获得值
     *
     * @param key 键
     * @return 值
     */
    Object get(Object key);

}
