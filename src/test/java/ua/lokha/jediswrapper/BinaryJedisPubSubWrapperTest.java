package ua.lokha.jediswrapper;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
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
    public void notLazyInit() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run, false)) {
            Assert.assertNotNull(wrapper.getThread());
            Assert.assertNotNull(wrapper.getPubSub());
            Assert.assertTrue(wrapper.getResubscribeCount() > 0);
        }
    }

    @Test
    public void lazyInit() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run, true)) {
            Assert.assertNull(wrapper.getThread());
            Assert.assertNull(wrapper.getPubSub());
            Assert.assertEquals(0, wrapper.getResubscribeCount());

            BinaryJedisPubSubListener listener = (channel, message) -> {
            };
            wrapper.unsubscribe(listener); // еще не зарегистрирован

            // unsubscribe не должен был вызвать инициализацию
            Assert.assertNull(wrapper.getThread());
            Assert.assertNull(wrapper.getPubSub());
            Assert.assertEquals(0, wrapper.getResubscribeCount());

            wrapper.subscribe(listener, "channel-name".getBytes(StandardCharsets.UTF_8));

            Assert.assertNotNull(wrapper.getThread());
            Assert.assertNotNull(wrapper.getPubSub());
            Assert.assertTrue(wrapper.getResubscribeCount() > 0);
        }
    }

    @Test
    public void resubscribed() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run)) {
            wrapper.subscribe((channel, message) -> {
            }, "channel-name".getBytes(StandardCharsets.UTF_8));

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
        BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run, false);
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
            BinaryJedisPubSubListener listener = wrapper.subscribe((channel, message) -> {
            }, "channel-name".getBytes(StandardCharsets.UTF_8));
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
            wrapper.subscribe((channel, message) -> {
                if (Arrays.equals(message, "message".getBytes(StandardCharsets.UTF_8))) {
                    latch.countDown();
                }
            }, "channel-name".getBytes(StandardCharsets.UTF_8));
            try (Jedis jedis = pool.getResource()) {
                jedis.publish("channel-name".getBytes(StandardCharsets.UTF_8), "message".getBytes(StandardCharsets.UTF_8));
            }
            Assert.assertTrue("timeout await publish", latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void pause() throws Exception {
        try (BinaryJedisPubSubWrapper wrapper = new BinaryJedisPubSubWrapper(pool, Runnable::run)) {
            // pause false
            wrapper.setPause(false);
            CountDownLatch notPauseLatch = new CountDownLatch(1);
            CountDownLatch pauseLatch = new CountDownLatch(1);
            wrapper.subscribe((channel, message) -> {
                if (Arrays.equals(message, "notPause".getBytes(StandardCharsets.UTF_8))) {
                    notPauseLatch.countDown();
                }
                if (Arrays.equals(message, "pause".getBytes(StandardCharsets.UTF_8))) {
                    pauseLatch.countDown();
                }
            }, "channel-name".getBytes(StandardCharsets.UTF_8));
            try (Jedis jedis = pool.getResource()) {
                jedis.publish("channel-name", "notPause");
            }
            Assert.assertTrue("timeout await publish", notPauseLatch.await(10, TimeUnit.SECONDS));

            // pause true
            wrapper.setPause(true);
            try (Jedis jedis = pool.getResource()) {
                jedis.publish("channel-name", "pause");
            }
            Assert.assertFalse("pause not work", pauseLatch.await(5, TimeUnit.SECONDS));
        }
    }
}