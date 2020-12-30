package ua.lokha.jediswrapper;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.util.Pool;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Обертка над {@link JedisPubSub}, использование которой даст следующие преимущества:
 * <ul>
 *     <li>Экономия потоков. Каждая подписка {@link JedisPubSub} блокирует для своей работы целый поток.
 *          Добавлять простушиваемые каналы к существующей подписки можно с помощью {@link JedisPubSub#subscribe(String...)},
 *          но это неудобно. Большая часть разработчиков вообще не вникает в эту особенность и каждый раз создает новую подписку
 *          {@link JedisPubSub} для каждого канала. Обертка же внутри себя создает общую подписку {@link JedisPubSub},
 *          которая будет использоваться для всех каналов. Добавлять и удалять простушиваемые каналы можно легко с помощью
 *          методов {@link #subscribe(JedisPubSubListener, String)} и {@link #unsubscribe(JedisPubSubListener)}.
 *          Внутри обертки создается отдельный поток для общей подписки {@link JedisPubSub}, который будет блокироваться
 *          вместо потока, в котором создается эта обертка.
 *     </li>
 *     <li>Потокобезопасность. Обертка сделана максимально потокобезопасно, насколько это позволяла сделать библиотека
 *     Jedis.</li>
 *     <li>Устойчивость. В отличии от {@link JedisPubSub}, подписка в обертке продолжит свою работу в
 *     случае возникновения исключения во время обработки сообщения в {@link JedisPubSubListener}. Исключение
 *     будет записано в лог.</li>
 *     <li>Возобновления работы подписки в случае ее завершения с ошибкой, например, от потери соединения с redis-сервером.
 *     Если внутренняя подписка {@link JedisPubSub} завершит свою работу с ошибкой, тогда будет создана новая подписка
 *     {@link JedisPubSub} с новым соединением {@link Jedis}. Все подписанные каналы будут заново зарегистрированы
 *     в новой подписке.</li>
 * </ul>
 *
 * <p>Эта обретка является ресурсом. После завершения работы с ней, следует вызвать {@link #close()}.
 *
 * <p>К этому классу есть <a href="https://github.com/lokha/jedis-wrapper#jedispubsubwrapper-%D0%B8-binaryjedispubsubwrapper">документация</a>.
 */
@Log
public class JedisPubSubWrapper implements AutoCloseable {
    // Канал-загрушка. В библиотеке Jedis для создания и работы подписки JedisPubSub
    // нужен минимум один канал, иначе будет ошибка.
    private static final String dummyChannel = "jedis-pubsub-keep";

    /**
     * Используется для поддержки многопоточности.
     */
    private final Lock lock = new ReentrantLock();
    private final Condition subscribed = lock.newCondition();
    private final Condition unsubscribed = lock.newCondition();

    /**
     * Используется для пометки этого ресурса как закрытого.
     */
    @Getter
    private boolean closed = false;

    /**
     * Поток, в котором работает подписка.
     */
    @Getter
    private Thread thread;

    /**
     * Объект подписки Jedis, на основе которого работает обертка.
     */
    @Getter
    private JedisPubSub pubSub;

    /**
     * Все подписки, ключем выступает имя канала, в значении список слушателей.
     */
    private Map<String, Set<JedisPubSubListener>> subscribes = new HashMap<>();

    /**
     * Пул для получения соединения {@link Jedis}, служит для инициализации подписки.
     * А так же для возобновления соединения в случае ее обрыва.
     */
    @Getter
    private Pool<Jedis> pool;

    /**
     * Executor, в котором будут обрабатываться сообщения, которые приходят на подписанные каналы.
     */
    @Getter
    private Executor executor;

    /**
     * Счетчик, сколько раз соединение подписка была зарегистрирована.
     * Увеличивается в случае первой подписки и последующих, если подписка будет обрываться.
     */
    @Getter
    private int resubscribeCount = 0;

    /**
     * Работает так же, как и {@link #JedisPubSubWrapper(Pool, Executor, boolean)}.
     * <p>Для параметра {@code executor} задается значение по умолчанию {@code Runnable::run}, что означает
     * обрабатывать сообщения в потоке подписки.
     * <p>Для параметра {@code lazyInit} задается значение по умолчанию {@code true}.
     *
     * @see #JedisPubSubWrapper(Pool, Executor, boolean)
     */
    public JedisPubSubWrapper(Pool<Jedis> pool) {
        this(pool, Runnable::run, true);
    }

    /**
     * Работает так же, как и {@link #JedisPubSubWrapper(Pool, Executor, boolean)}.
     * <p>Для параметра {@code lazyInit} задается значение по умолчанию {@code true}.
     *
     * @see #JedisPubSubWrapper(Pool, Executor, boolean)
     */
    public JedisPubSubWrapper(Pool<Jedis> pool, Executor executor) {
        this(pool, executor, true);
    }

    /**
     * Создание обертки над {@link JedisPubSub}.
     *
     * @param pool     пул соединений с Redis.
     *                 Поскольку эта обертка умеет возобновлять подписку в случае ошибки, нужен именно пул соединений,
     *                 а не конкретное соединиение.
     * @param executor обработчик, в котором будет вызываться обработка сообщений, приходящих на канал подписки.
     *                 Метод слушателя {@link JedisPubSubListener#onMessage(String, String)} будет вызываться
     *                 именно в этом обработчике.
     * @param lazyInit ленивая инициализация. Если указать значение {@code true}, тогда инициализация будет перенесена до
     *                 первого вызова {@link #subscribe(JedisPubSubListener, String)}, если {@code false}, тогда
     *                 инициализация будет вызвана в конструкторе. В инициализацию входит создание подписки PubSub,
     *                 создание потока для этой подписки и ожидание полной готовности подписки.
     */
    public JedisPubSubWrapper(Pool<Jedis> pool, Executor executor, boolean lazyInit) {
        this.pool = pool;
        this.executor = executor;

        if (!lazyInit) {
            this.lazyInit();

            lock.lock();
            try {
                this.awaitSubscribed();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Выполнить инициализацию, если она еще не выполнена
     */
    private void lazyInit() {
        if (thread != null) {
            return; // уже проинициализировано
        }

        thread = new Thread(() -> {
            while (!(closed || Thread.currentThread().isInterrupted() || pool.isClosed())) {
                try (Jedis jedis = pool.getResource()) {
                    lock.lock();
                    String[] channels;
                    try {
                        pubSub = new PubSub();

                        channels = new String[subscribes.size() + 1];
                        channels[0] = dummyChannel;
                        int i = 1;
                        for (String channel : subscribes.keySet()) {
                            channels[i++] = channel;
                        }
                    } finally {
                        lock.unlock();
                    }
                    jedis.subscribe(pubSub, channels);
                } catch (Exception e) {
                    //noinspection ConstantConditions
                    if (e instanceof InterruptedException) {
                        break;
                    }
                    log.severe("Подписка оборвалась с ошибкой.");
                    e.printStackTrace();
                }
            }

            // на всякий случай, если поток завершил работу в результате ошибки
            close();
        }, this.getClass().getSimpleName() + " Thread");

        thread.start();
    }

    /**
     * Подписаться на прослушивание канала.
     *
     * <p>В отличии от {@link Jedis#subscribe(JedisPubSub, String...)}, этот метод не блокирует поток,
     * что делает подписку легковесной.
     *
     * @param listener слушатель, который будет вызываться, когда будет приходить сообщение на указанный канал.
     * @param channel имя канала.
     * @return слушатель, переданный параметром {@code listener}. Он выступает индентификатором подписки,
     * с помощью слушателя можно отменить подписку методом {@link #unsubscribe(JedisPubSubListener)}.
     */
    public JedisPubSubListener subscribe(JedisPubSubListener listener, String channel) {
        lock.lock();
        try {
            this.checkForClosed();
            this.lazyInit();
            this.awaitSubscribed();
            subscribes.computeIfAbsent(channel, key -> {
                try {
                    pubSub.subscribe(channel);
                } catch (Exception ignored) {
                    // если будет ошибка, значит подписка оборвалась,
                    // в таком случае канал зарегистрирует при повторной подписке
                }
                return new HashSet<>(1);
            }).add(listener);
            log.info("Подписали на канал '" + channel + "' listener: " + listener);
            return listener;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Отменить подписку по указанному слушателю.
     *
     * @param listener слушатель, используемый в подписках, которые должны быть отменены.
     *                 Если этот слушатель использовался для прослушивания нескольких каналов, тогда все эти
     *                 подписки будут отменены.
     * @return true, если была отменена хотя бы одна подписка. Если подписок с указанным слушателем не было найдено,
     * тогда вернет false.
     */
    public boolean unsubscribe(JedisPubSubListener listener) {
        lock.lock();
        try {
            this.checkForClosed();
            return subscribes.entrySet().removeIf(setEntry -> {
                if (setEntry.getValue().remove(listener)) {
                    log.info("Отписали от канала " + setEntry.getKey() +
                        " listener: " + listener);
                    if (setEntry.getValue().isEmpty() && pubSub != null) {
                        try {
                            pubSub.unsubscribe(setEntry.getKey());
                        } catch (Exception e) {
                            // если будет ошибка, значит подписка оборвалась,
                            // и уже все равно все каналы отписало
                        }
                        return true;
                    }
                }
                return false;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ждать, пока подписка полностью будет создана.
     */
    @SneakyThrows
    private void awaitSubscribed() {
        while (pubSub == null || !pubSub.isSubscribed()) {
            if (!subscribed.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Таймаут ожидания создания подписки pubSub.");
            }
        }
    }

    /**
     * Вызвать обработку сообщения по каналу.
     * Все слущатели указанного канала будут вызваны.
     *
     * @param channel канал.
     * @param message сообщение.
     */
    private void callListeners(String channel, String message) {
        lock.lock();
        try {
            for (JedisPubSubListener listener : subscribes.getOrDefault(channel, Collections.emptySet())) {
                executor.execute((() -> { // каждое сообщение вызывается в отдельном вызове Executor'a
                    try {
                        listener.onMessage(channel, message);
                    } catch (Exception e) {
                        log.info("Ошибка обработки канала " + channel + " (" + channel + "), " +
                            "listener: " + listener +
                            ", сообщение: " + message + " (" + message + ")");
                        e.printStackTrace();
                    }
                }));
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkForClosed() throws IllegalStateException {
        if (closed) {
            throw new IllegalStateException("this resource is closed");
        }
    }

    /**
     * Завершить работу подписки:
     * <ul>
     *     <li>Внутренняя подписка будет отписана с помощью {@link JedisPubSub#unsubscribe()}.</li>
     *     <li>Поток будет остановлен, который блокировала подписка.</li>
     * </ul>
     *
     * <p>Этот метод блокирует поток, пока внутренняя подписка {@link JedisPubSub} не будет полностью отменена.
     *
     * <p>Этот метод не будет освождать полученный через конструктор пул потоков {@link #getPool()}.
     *
     * <p>Этот метод является идемпотентным, повторный его вызов не приведет к ошибке, а просто будет проигнорирован.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true; // mark closed
            try {
                if (pubSub != null) {
                    pubSub.unsubscribe();
                    while (pubSub.isSubscribed()) {
                        if (!unsubscribed.await(10, TimeUnit.SECONDS)) {
                            throw new TimeoutException("Таймаут ожидания отмены подписки pubSub.");
                        }
                    }
                }
            } catch (Exception e) {
                log.severe("Unsubscribe " + this.getClass().getSimpleName() + " exception, " +
                    "ignore this exception for idempotency of " + this.getClass().getSimpleName() + ".close().");
                e.printStackTrace();
            }

            try {
                if (thread != null && !thread.isInterrupted()) {
                    thread.interrupt();
                }
            } catch (Exception e) {
                log.severe("Interrupt thread " + this.getClass().getSimpleName() + " exception, " +
                    "ignore this exception for idempotency of " + this.getClass().getSimpleName() + ".close().");
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    private class PubSub extends JedisPubSub {
        @Override
        public void onMessage(String channel, String message) {
            callListeners(channel, message);
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            if (channel.equals(dummyChannel)) {
                resubscribeCount++;
                if (resubscribeCount > 1) {
                    // вызывается в случае повторной регистрации подписки,
                    // если предыдущая по какой-то причине оборвалась
                    log.info("Подписка зарегистрирована заново в " + resubscribeCount + " раз.");
                }

                lock.lock();
                try {
                    subscribed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            if (!this.isSubscribed()) {
                lock.lock();
                try {
                    unsubscribed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Все подписки, ключем выступает имя канала, в значении список слушателей.
     */
    public Map<String, Set<JedisPubSubListener>> getSubscribes() {
        lock.lock();
        try {
            Map<String, Set<JedisPubSubListener>> copy = new HashMap<>();
            subscribes.forEach((channel, listeners) -> copy.put(channel, new HashSet<>(listeners)));
            return copy;
        } finally {
            lock.unlock();
        }
    }
}
