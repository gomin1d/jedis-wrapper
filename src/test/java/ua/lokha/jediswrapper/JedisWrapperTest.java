package ua.lokha.jediswrapper;

import lombok.SneakyThrows;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectState;
import org.apache.commons.pool2.impl.DefaultPooledObjectInfo;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Response;
import redis.clients.util.Pool;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class JedisWrapperTest {
    private JedisPool pool;

    @Before
    public void beforeAll() {
        String host = RedisCredentials.host;
        int port = RedisCredentials.port;
        if (RedisCredentials.password == null) {
            pool = new JedisPool(new GenericObjectPoolConfig(), host, port, 30000);
        } else {
            pool = new JedisPool(new GenericObjectPoolConfig(), host, port, 30000, RedisCredentials.password);
        }
    }

    @After
    public void afterAll() {
        pool.close();
    }

    @Test
    public void setAndGet() {
        Random random = new Random();
        String value = String.valueOf(random.nextInt(100000));
        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            wrapper.set("key", value);
        }
        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            String get = wrapper.get("key");
            assertEquals(get, value);
        }
    }

    @Test
    public void subAndPub() throws Exception {
        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            CountDownLatch latch = new CountDownLatch(1);
            wrapper.subscribe((channel, message) -> {
                if (message.equals("message")) {
                    latch.countDown();
                }
            }, "channel-name");

            wrapper.publish("channel-name", "message");
            Assert.assertTrue("timeout await publish", latch.await(10, TimeUnit.SECONDS));
        }

        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            CountDownLatch latch = new CountDownLatch(1);
            wrapper.subscribe((channel, message) -> {
                if (Arrays.equals(message, "message".getBytes(StandardCharsets.UTF_8))) {
                    latch.countDown();
                }
            }, "channel-name".getBytes(StandardCharsets.UTF_8));

            wrapper.publish("channel-name".getBytes(StandardCharsets.UTF_8), "message".getBytes(StandardCharsets.UTF_8));
            Assert.assertTrue("timeout await publish", latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void close() {
        JedisWrapper wrapper = new JedisWrapper(pool);
        wrapper.subscribe((channel, message) -> {
        }, "channel-name");
        wrapper.subscribe((channel, message) -> {
        }, "binary-channel-name".getBytes(StandardCharsets.UTF_8));

        assertFalse(wrapper.getPubSubWrapper().isClosed());
        assertTrue(wrapper.getPubSubWrapper().getPubSub().isSubscribed());

        assertFalse(wrapper.getBinaryPubSubWrapper().isClosed());
        assertTrue(wrapper.getBinaryPubSubWrapper().getPubSub().isSubscribed());

        wrapper.close();

        assertFalse(pool.isClosed()); // пул закрываться не должен

        assertTrue(wrapper.getPubSubWrapper().isClosed());
        assertFalse(wrapper.getPubSubWrapper().getPubSub().isSubscribed());

        assertTrue(wrapper.getBinaryPubSubWrapper().isClosed());
        assertFalse(wrapper.getBinaryPubSubWrapper().getPubSub().isSubscribed());
    }

    @Test
    public void pipelineClose() {
        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            JedisPipeline pipeline = wrapper.pipelined();

            Jedis jedis = pipeline.getJedis();
            assertFalse(containsResourceInPool(jedis, wrapper.getPool()));

            pipeline.sync();
            assertFalse(containsResourceInPool(jedis, wrapper.getPool()));

            pipeline.close();
            assertTrue(containsResourceInPool(jedis, wrapper.getPool()));
        }
    }

    @Test
    public void pipeline() {
        Random random = new Random();
        Map<String, String> keyValue = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            keyValue.put("key" + i, String.valueOf(random.nextInt(100000)));
        }

        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            try (JedisPipeline pipeline = wrapper.pipelined()) {
                keyValue.forEach(pipeline::set);
            }
        }

        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            try (JedisPipeline pipeline = wrapper.pipelined()) {
                Map<String, Response<String>> result = new HashMap<>();
                for (String key : keyValue.keySet()) {
                    Response<String> response = pipeline.get(key);
                    result.put(key, response);
                }
                pipeline.sync();
                keyValue.forEach((key, value) -> {
                    assertEquals(value, result.get(key).get());
                });
            }
        }
    }

    @Test
    public void multiClose() {
        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            JedisTransaction transaction = wrapper.multi();

            Jedis jedis = transaction.getJedis();
            assertFalse(containsResourceInPool(jedis, wrapper.getPool()));

            transaction.exec();
            assertFalse(containsResourceInPool(jedis, wrapper.getPool()));

            transaction.close();
            assertTrue(containsResourceInPool(jedis, wrapper.getPool()));
        }
    }

    @Test
    public void multi() {
        Random random = new Random();
        Map<String, String> keyValue = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            keyValue.put("key" + i, String.valueOf(random.nextInt(100000)));
        }

        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            try (JedisTransaction transaction = wrapper.multi()) {
                keyValue.forEach(transaction::set);
                transaction.exec();
            }
        }

        try (JedisWrapper wrapper = new JedisWrapper(pool)) {
            try (JedisTransaction multi = wrapper.multi()) {
                Map<String, Response<String>> result = new HashMap<>();
                for (String key : keyValue.keySet()) {
                    Response<String> response = multi.get(key);
                    result.put(key, response);
                }
                multi.exec();
                keyValue.forEach((key, value) -> {
                    assertEquals(value, result.get(key).get());
                });
            }
        }
    }

    /**
     * Проверить, свободен ли ресурс {@link Jedis} для взятия из указанного пула.
     */
    @SneakyThrows
    public static boolean containsResourceInPool(Jedis jedis, Pool<Jedis> pool) {
        Field internalPoolField = Pool.class.getDeclaredField("internalPool");
        internalPoolField.setAccessible(true);
        GenericObjectPool objectPool = (GenericObjectPool) internalPoolField.get(pool);

        Field pooledObjectField = DefaultPooledObjectInfo.class.getDeclaredField("pooledObject");
        pooledObjectField.setAccessible(true);

        Set<DefaultPooledObjectInfo> set = objectPool.listAllObjects();
        for (DefaultPooledObjectInfo info : set) {
            PooledObject<?> pooledObject = (PooledObject<?>) pooledObjectField.get(info);
            Jedis object = (Jedis) pooledObject.getObject();

            if (object.equals(jedis)) {
                return pooledObject.getState().equals(PooledObjectState.IDLE);
            }
        }

        return false;
    }
}