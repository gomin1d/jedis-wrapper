package ua.lokha.jediswrapper;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JedisPubSubWrapperTest {
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
    public void notLazyInit() throws Exception {
        try (JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run, false)) {
            Assert.assertNotNull(wrapper.getThread());
            Assert.assertNotNull(wrapper.getPubSub());
            Assert.assertTrue(wrapper.getResubscribeCount() > 0);
        }
    }

    @Test
    public void lazyInit() throws Exception {
        try (JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run, true)) {
            Assert.assertNull(wrapper.getThread());
            Assert.assertNull(wrapper.getPubSub());
            Assert.assertEquals(0, wrapper.getResubscribeCount());

            JedisPubSubListener listener = (channel, message) -> {
            };
            wrapper.unsubscribe(listener); // еще не зарегистрирован

            // unsubscribe не должен был вызвать инициализацию
            Assert.assertNull(wrapper.getThread());
            Assert.assertNull(wrapper.getPubSub());
            Assert.assertEquals(0, wrapper.getResubscribeCount());

            wrapper.subscribe("channel-name", listener);

            Assert.assertNotNull(wrapper.getThread());
            Assert.assertNotNull(wrapper.getPubSub());
            Assert.assertTrue(wrapper.getResubscribeCount() > 0);
        }
    }

    @Test
    public void resubscribed() throws Exception {
        try (JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run)) {
            wrapper.subscribe("channel-name", (channel, message) -> {
            });

            JedisPubSub previously = wrapper.getPubSub();
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
        JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run, false);
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
        try (JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run)) {
            JedisPubSubListener listener = wrapper.subscribe("channel-name", (channel, message) -> {
            });
            Set<JedisPubSubListener> listeners = wrapper.getSubscribes().get("channel-name");
            Assert.assertNotNull(listeners);
            Assert.assertTrue(listeners.contains(listener));

            Assert.assertTrue(wrapper.unsubscribe(listener));
            Assert.assertFalse(wrapper.unsubscribe(listener)); // double unsub -> false
            listeners = wrapper.getSubscribes().get("channel-name");
            if (listeners != null) {
                Assert.assertFalse(listeners.contains(listener));
            }
        }
    }

    @Test
    public void subAndPub() throws Exception {
        try (JedisPubSubWrapper wrapper = new JedisPubSubWrapper(pool, Runnable::run)) {
            CountDownLatch latch = new CountDownLatch(1);
            wrapper.subscribe("channel-name", (channel, message) -> {
                if (message.equals("message")) {
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