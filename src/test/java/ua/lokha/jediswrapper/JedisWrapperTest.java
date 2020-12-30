package ua.lokha.jediswrapper;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class JedisWrapperTest {
    private static JedisPool pool;

    @BeforeClass
    public static void beforeAll() {
        String host = RedisCredentials.host;
        int port = RedisCredentials.port;
        if (RedisCredentials.password == null) {
            pool = new JedisPool(new GenericObjectPoolConfig(), host, port, 30000);
        } else {
            pool = new JedisPool(new GenericObjectPoolConfig(), host, port, 30000, RedisCredentials.password);
        }
    }

    @AfterClass
    public static void afterAll() {
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
    public void close() throws Exception {
        JedisWrapper wrapper = new JedisWrapper(pool);
        wrapper.subscribe((channel, message) -> {}, "channel-name");
        wrapper.subscribe((channel, message) -> {}, "binary-channel-name".getBytes(StandardCharsets.UTF_8));

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
}