package ua.lokha.jediswrapper;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BinaryJedisPubSubWrapperTest {
    private static JedisPool pool;

    @BeforeClass
    public static void beforeAll() {
        String host = RedisCredentials.host;
        int port = RedisCredentials.port;
        pool = new JedisPool(new GenericObjectPoolConfig(), host, port, 30000);
    }

    @AfterClass
    public static void afterAll() {
        pool.close();
    }

    @Test
    public void resubscribed() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run)) {
            wrapper.subscribe("channel-name".getBytes(StandardCharsets.UTF_8), (channel, message) -> {
            });

            BinaryJedisPubSub previously = wrapper.getPubSub();
            previously.unsubscribe();
            long unsubStart = System.currentTimeMillis();
            while (previously.isSubscribed()) {
                if (System.currentTimeMillis() - unsubStart > 10_000) {
                    Assert.fail("timeout await unsubscribed");
                }
                Thread.sleep(10);
            }

            long resubStart = System.currentTimeMillis();
            while (!wrapper.getPubSub().isSubscribed()) {
                if (System.currentTimeMillis() - resubStart > 10_000) {
                    Assert.fail("timeout await resubscribed");
                }
                Thread.sleep(10);
            }

            Assert.assertTrue(wrapper.getResubscribeCount() > 1);
        }
    }

    @Test
    public void unsubscribed() throws Exception {
        BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run);
        Assert.assertTrue(wrapper.getPubSub().isSubscribed());
        Assert.assertTrue(wrapper.getThread().isAlive());
        Assert.assertFalse(wrapper.getThread().isInterrupted());
        Assert.assertFalse(wrapper.isClosed());
        wrapper.close();
        Assert.assertFalse(wrapper.getPubSub().isSubscribed());
        Assert.assertTrue(wrapper.getThread().isInterrupted());
        Assert.assertTrue(wrapper.isClosed());
    }

    @Test
    public void subAndUnsub() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run)) {
            BinaryJedisPubSubListener listener = wrapper.subscribe("channel-name".getBytes(StandardCharsets.UTF_8), (channel, message) -> {
            });
            Set<BinaryJedisPubSubListener> listeners = wrapper.getSubscribes().get(new ByteArrayWrapper("channel-name".getBytes(StandardCharsets.UTF_8)));
            Assert.assertNotNull(listeners);
            Assert.assertTrue(listeners.contains(listener));

            Assert.assertTrue(wrapper.unsubscribe(listener));
            Assert.assertFalse(wrapper.unsubscribe(listener)); // double unsub -> false
            listeners = wrapper.getSubscribes().get(new ByteArrayWrapper("channel-name".getBytes(StandardCharsets.UTF_8)));
            if (listeners != null) {
                Assert.assertFalse(listeners.contains(listener));
            }
        }
    }

    @Test
    public void subAndPub() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run)) {
            CountDownLatch latch = new CountDownLatch(1);
            wrapper.subscribe("channel-name".getBytes(StandardCharsets.UTF_8), (channel, message) -> {
                if (Arrays.equals(message, "message".getBytes(StandardCharsets.UTF_8))) {
                    latch.countDown();
                }
            });
            try (Jedis jedis = pool.getResource()) {
                jedis.publish("channel-name", "message");
            }
            Assert.assertTrue("timeout await publish", latch.await(10, TimeUnit.SECONDS));
        }
    }
}