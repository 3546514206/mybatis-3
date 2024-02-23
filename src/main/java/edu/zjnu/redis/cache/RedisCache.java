/*
 *    Copyright 2015-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package edu.zjnu.redis.cache;

import org.apache.ibatis.cache.Cache;
import org.mybatis.caches.redis.RedisCallback;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * @description: 替换 CacheExecutor.query  走的缓存实
 * 现类，将数据库执行的结果缓存到redis
 * @author:
 * @date: 2021-10-01
 **/
public final class RedisCache implements Cache {

  private static JedisPool pool;

  private final ReadWriteLock readWriteLock = new DummyReadWriteLock();

  private final RedisConfig redisConfig;

  private String id;

  private Integer timeout;

  public RedisCache(final String id) {
    if (id == null) {
      throw new IllegalArgumentException("Cache instances require an ID");
    }
    this.id = id;
    redisConfig = RedisConfigurationBuilder.getInstance().parseConfiguration();
    pool = new JedisPool(redisConfig, redisConfig.getHost(), redisConfig.getPort(), redisConfig.getConnectionTimeout(),
            redisConfig.getSoTimeout(), redisConfig.getPassword(), redisConfig.getDatabase(), redisConfig.getClientName(),
            redisConfig.isSsl(), redisConfig.getSslSocketFactory(), redisConfig.getSslParameters(),
            redisConfig.getHostnameVerifier());
  }

  private Object execute(RedisCallback callback) {
    Jedis jedis = pool.getResource();
    try {
      return callback.doWithRedis(jedis);
    } finally {
      jedis.close();
    }
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public int getSize() {
    return (Integer) execute(new RedisCallback() {
      @Override
      public Object doWithRedis(Jedis jedis) {
        Map<byte[], byte[]> result = jedis.hgetAll(id.getBytes());
        return result.size();
      }
    });
  }

  @Override
  public void putObject(final Object key, final Object value) {

    execute(jedis -> {

      final byte[] idBytes = id.getBytes();
      jedis.hset(idBytes, key.toString().getBytes(), redisConfig.getSerializer().serialize(value));
      if (timeout != null && jedis.ttl(idBytes) == -1) {
        jedis.expire(idBytes, timeout);
      }

      return null;
    });
  }

  @Override
  public Object getObject(final Object key) {

    return execute(jedis -> redisConfig.getSerializer().unserialize(jedis.hget(id.getBytes(), key.toString().getBytes())));
  }

  @Override
  public Object removeObject(final Object key) {
    return execute(jedis -> jedis.hdel(id, key.toString()));
  }

  @Override
  public void clear() {
    execute(jedis -> {
      jedis.del(id);
      return null;
    });

  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return readWriteLock;
  }

  @Override
  public String toString() {
    return "Redis {" + id + "}";
  }

  public void setTimeout(Integer timeout) {
    this.timeout = timeout;
  }

}
