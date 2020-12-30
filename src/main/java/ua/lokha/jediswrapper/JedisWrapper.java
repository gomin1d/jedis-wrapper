package ua.lokha.jediswrapper;

import lombok.Getter;
import lombok.Lombok;
import redis.clients.jedis.*;
import redis.clients.jedis.params.geo.GeoRadiusParam;
import redis.clients.jedis.params.sortedset.ZAddParams;
import redis.clients.jedis.params.sortedset.ZIncrByParams;
import redis.clients.util.Pool;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * {@code JedisWrapper} это оболочка для {@link Jedis} + {@link JedisPool}. {@code JedisWrapper} служит для
 * упрощения работы с Jedis, поскольку берет на себя контроль над ресурсами.
 *
 * <p>Работая с обычным {@link Jedis} разработчику необходимо самостоятельно контролировать получение
 * ресурса из {@link JedisPool}, а после освобождать ресурс с помощью {@link Jedis#close()}.
 *
 * <p>{@code JedisWrapper} берет контроль над ресурсами на себя. При вызове любого метода из {@code JedisWrapper},
 * будет взят ресурс {@link Jedis}, выполнена соотвествующая операция в нем, после чего ресурс будет освобожден.
 * Любой метод из {@code JedisWrapper} будет выполнять операцию в отдельно полученном для этого ресурсе.
 *
 * <p>Плюсы {@code JedisWrapper}:
 * <ul>
 *     <li>Уменьшение количество кода за счет отсутствия необходимости контролировать ресурсы.</li>
 *     <li>Предотвращение утечек ресурсов. Все методы из {@code JedisWrapper} гарантировано освобождают
 *     ресурсы по окончанию своей работы.</li>
 * </ul>
 *
 * <p>Разница между использованием {@link Jedis} и {@code JedisWrapper}:
 * <pre>
 *     // Jedis
 *     try(Jedis jedis = jedisPool.getResource()){
 *         jedis.set("key", "value");
 *     }
 *
 *     // JedisWrapper
 *     JedisWrapper.set("key", "value");
 * </pre>
 *
 * <p>{@code JedisWrapper} эффективно использовать с настоенным {@link JedisPool}, в таком случае реальные соединения
 * не будут создаваться/закрываться, вместо этого они будут браться и возвращаться в пул соединений.
 *
 * <p>{@code JedisWrapper} поддерживает pipeline {@link #pipelined()} и транзации {@link #multi()}.
 *
 * <p>В {@code JedisWrapper} встроены улучшенные подписки {@link #subscribe(JedisPubSubListener, String...)} и
 * {@link #subscribe(BinaryJedisPubSubListener, byte[]...)}.
 *
 * <p>К этому классу есть <a href="https://github.com/lokha/jedis-wrapper#api">документация</a>.
 */
public class JedisWrapper implements JedisCommands, MultiKeyCommands, BinaryJedisCommands, MultiKeyBinaryCommands,
    AutoCloseable {

    /**
     * Получить пул соединений Redis, который был передан в конструктор этого класса.
     */
    @Getter
	private Pool<Jedis> pool;

    /**
     * Получить обертку для PubSub подписок {@link JedisPubSub}.
     */
    @Getter
	private JedisPubSubWrapper pubSubWrapper;

    /**
     * Получить обертку для PubSub подписок {@link BinaryJedisPubSub}.
     */
    @Getter
	private BinaryJedisPubSubWrapper binaryPubSubWrapper;

    /**
     * Работает так же, как и {@link #JedisWrapper(Pool, Executor)}.
     * <p>Для параметра {@code executor} задается значение по умолчанию {@code Runnable::run}, что означает
     * обрабатывать сообщения в потоке подписки.
     */
	public JedisWrapper(Pool<Jedis> pool) {
        this(pool, Runnable::run);
    }

    /**
     * @param pool пул соединений Redis.
     *             <p>{@code JedisWrapper} эффективно использовать с настоенным {@link JedisPool}, в таком случае реальные соединения
     *             не будут создаваться/закрываться, вместо этого они будут браться и возвращаться в пул соединений.
     * @param executor обработчик, в котором будет вызываться обработка сообщений, приходящих на канал подписки.
     *                 Метод слушателя {@link JedisPubSubListener#onMessage(String, String)} будет вызываться
     *                 именно в этом обработчике.
     */
	public JedisWrapper(Pool<Jedis> pool, Executor executor){
		this.pool = pool;

		this.pubSubWrapper = new JedisPubSubWrapper(pool, executor);
		this.binaryPubSubWrapper = new BinaryJedisPubSubWrapper(pool, executor);
	}


    private static Field jedisTransactionField;

    static {
        try {
            jedisTransactionField = BinaryJedis.class.getDeclaredField("transaction");
            jedisTransactionField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Lombok.sneakyThrow(e);
        }
    }

    /**
     * Работает так же, как и {@link Jedis#multi()}, только возвращает
     * объект тразакции {@link JedisTransaction} вместо {@link Transaction}.
     * Отличительной особенностью {@link JedisTransaction} является то, что он при освобождении
     * своего ресурса {@link JedisTransaction#close()} так же освобождает ресурс {@link Jedis}, который хранит внутри себя.
     *
     * <p>{@link JedisTransaction} является ресурсом. После завершения работы с ним, следует вызвать {@link JedisTransaction#close()}.
     *
     * <p>Пример использования:
     * <pre>
     *     JedisWrapper jedisWrapper = ...;
     *     try (JedisTransaction multi = jedisWrapper.multi()) {
     *         multi.set("key1", "value1");
     *         multi.set("key2", "value2");
     *         multi.exec();
     *     }
     * </pre>
     */
    public JedisTransaction multi() {
        Jedis jedis = null;
        JedisTransaction transaction = null;
        try {
            jedis = pool.getResource();
            jedis.getClient().multi();
            transaction = new JedisTransaction(jedis);
            jedisTransactionField.set(jedis, transaction);
        } catch (Exception e) {
            if (jedis != null) {
                try {
                    jedis.close();
                } catch (Exception ignored) {
                }
            }
            Lombok.sneakyThrow(e);
        }
        return transaction;
    }

	private static Field jedisPipelineField;

	static {
	    try {
	        jedisPipelineField = BinaryJedis.class.getDeclaredField("pipeline");
	        jedisPipelineField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Lombok.sneakyThrow(e);
        }
    }

    /**
     * Работает так же, как и {@link Jedis#pipelined()}, только возвращает
     * объект pipeline {@link JedisPipeline} вместо {@link Pipeline}.
     * Отличительной особенностью {@link JedisPipeline} является то, что он при освобождении
     * своего ресурса {@link JedisPipeline#close()} так же освобождает ресурс {@link Jedis}, который хранит внутри себя.
     *
     * <p>{@link JedisPipeline} является ресурсом. После завершения работы с ним, следует вызвать {@link JedisPipeline#close()}.
     *
     * <p>Пример использования:
     * <pre>
     *     JedisWrapper jedisWrapper = ...;
     *     try (JedisPipeline pipeline = jedisWrapper.pipelined()) {
     *         Response<String> response1 = pipeline.get("key1");
     *         Response<String> response2 = pipeline.get("key2");
     *         pipeline.sync();
     *         String value1 = response1.get();
     *         String value2 = response2.get();
     *     }
     * </pre>
     */
    public JedisPipeline pipelined() {
        Jedis jedis = null;
        JedisPipeline pipeline = null;
        try {
            jedis = pool.getResource();
            pipeline = new JedisPipeline(jedis);
            jedisPipelineField.set(jedis, pipeline);
            pipeline.setClient(jedis.getClient());
        } catch (Exception e) {
            if (jedis != null) {
                try {
                    jedis.close();
                } catch (Exception ignored) {
                }
            }
            Lombok.sneakyThrow(e);
        }
        return pipeline;
    }

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String set(final byte[] key, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value);
        }
	}

	@Override
	public String set(byte[] key, byte[] value, byte[] nxxx){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value, nxxx);
        }
	}

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 *
	 * @param key
	 * @param value
	 * @param nxxx  NX|XX, NX -- Only set the key if it does not already exist. XX -- Only set the key
	 *              if it already exist.
	 *              PX = milliseconds
	 * @param time  expire time in the units of <code>expx</code>
	 * @return Status code reply
	 */
	@Override
	public String set(final byte[] key, final byte[] value, final byte[] nxxx, final byte[] expx,
	                  final long time){
		try(Jedis jedis = pool.getResource()){
			return jedis.set(key, value, nxxx, expx, time);
		}
	}

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 *
	 * @param key
	 * @param value PX = milliseconds
	 * @param time  expire time in the units of <code>expx</code>
	 * @return Status code reply
	 */
	public String set(final byte[] key, final byte[] value, final byte[] expx, final long time){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value, expx, time);
        }
	}

	/**
	 * Get the value of the specified key. If the key does not exist the special value 'nil' is
	 * returned. If the value stored at key is not a string an error is returned because GET can only
	 * handle string values.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public byte[] get(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.get(key);
        }
	}


	/**
	 * Test if the specified keys exist. The command returns the number of keys exist.
	 * Time complexity: O(N)
	 *
	 * @param keys
	 * @return Integer reply, specifically: an integer greater than 0 if one or more keys exist,
	 * 0 if none of the specified keys exist.
	 */
	@Override
	public Long exists(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.exists(keys);
        }
	}

	/**
	 * Test if the specified key exists. The command returns true if the key exists, otherwise false is
	 * returned. Note that even keys set with an empty string as value will return true. Time
	 * complexity: O(1)
	 *
	 * @param key
	 * @return Boolean reply, true if the key exists, otherwise false
	 */
	@Override
	public Boolean exists(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.exists(key);
        }
	}

	/**
	 * Remove the specified keys. If a given key does not exist no operation is performed for this
	 * key. The command returns the number of keys removed. Time complexity: O(1)
	 *
	 * @param keys
	 * @return Integer reply, specifically: an integer greater than 0 if one or more keys were removed
	 * 0 if none of the specified key existed
	 */
	@Override
	public Long del(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.del(keys);
        }
	}

	@Override
	public Long del(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.del(key);
        }
	}

	/**
	 * This command is very similar to DEL: it removes the specified keys. Just like DEL a key is
	 * ignored if it does not exist. However the command performs the actual memory reclaiming in a
	 * different thread, so it is not blocking, while DEL is. This is where the command name comes
	 * from: the command just unlinks the keys from the keyspace. The actual removal will happen later
	 * asynchronously.
	 * <p>
	 * Time complexity: O(1) for each key removed regardless of its size. Then the command does O(N)
	 * work in a different thread in order to reclaim memory, where N is the number of allocations the
	 * deleted objects where composed of.
	 *
	 * @param keys
	 * @return Integer reply: The number of keys that were unlinked
	 */
	@Override
	public Long unlink(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.unlink(keys);
        }
	}

	@Override
	public Long unlink(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.unlink(key);
        }
	}

	/**
	 * Return the type of the value stored at key in form of a string. The type can be one of "none",
	 * "string", "list", "set". "none" is returned if the key does not exist. Time complexity: O(1)
	 *
	 * @param key
	 * @return Status code reply, specifically: "none" if the key does not exist "string" if the key
	 * contains a String value "list" if the key contains a List value "set" if the key
	 * contains a Set value "zset" if the key contains a Sorted Set value "hash" if the key
	 * contains a Hash value
	 */
	@Override
	public String type(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.type(key);
        }
	}

	/**
	 * Returns all the keys matching the glob-style pattern as space separated strings. For example if
	 * you have in the database the keys "foo" and "foobar" the command "KEYS foo*" will return
	 * "foo foobar".
	 * <p>
	 * Note that while the time complexity for this operation is O(n) the constant times are pretty
	 * low. For example Redis running on an entry level laptop can scan a 1 million keys database in
	 * 40 milliseconds. <b>Still it's better to consider this one of the slow commands that may ruin
	 * the DB performance if not used with care.</b>
	 * <p>
	 * In other words this command is intended only for debugging and special operations like creating
	 * a script to change the DB schema. Don't use it in your normal code. Use Redis Sets in order to
	 * group together a subset of objects.
	 * <p>
	 * Glob style patterns examples:
	 * <ul>
	 * <li>h?llo will match hello hallo hhllo
	 * <li>h*llo will match hllo heeeello
	 * <li>h[ae]llo will match hello and hallo, but not hillo
	 * </ul>
	 * <p>
	 * Use \ to escape special chars if you want to match them verbatim.
	 * <p>
	 * Time complexity: O(n) (with n being the number of keys in the DB, and assuming keys and pattern
	 * of limited length)
	 *
	 * @param pattern
	 * @return Multi bulk reply
	 */
	@Override
	public Set<byte[]> keys(final byte[] pattern){
        try(Jedis jedis = pool.getResource()){
            return jedis.keys(pattern);
        }
	}

	/**
	 * Return a randomly selected key from the currently selected DB.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @return Singe line reply, specifically the randomly selected key or an empty string is the
	 * database is empty
	 */
	@Override
	public byte[] randomBinaryKey(){
        try(Jedis jedis = pool.getResource()){
            return jedis.randomBinaryKey();
        }
	}

	/**
	 * Atomically renames the key oldkey to newkey. If the source and destination name are the same an
	 * error is returned. If newkey already exists it is overwritten.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param oldkey
	 * @param newkey
	 * @return Status code repy
	 */
	@Override
	public String rename(final byte[] oldkey, final byte[] newkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.rename(oldkey, newkey);
        }
	}

	/**
	 * Rename oldkey into newkey but fails if the destination key newkey already exists.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param oldkey
	 * @param newkey
	 * @return Integer reply, specifically: 1 if the key was renamed 0 if the target key already exist
	 */
	@Override
	public Long renamenx(final byte[] oldkey, final byte[] newkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.renamenx(oldkey, newkey);
        }
	}

	/**
	 * Set a timeout on the specified key. After the timeout the key will be automatically deleted by
	 * the server. A key with an associated timeout is said to be volatile in Redis terminology.
	 * <p>
	 * Volatile keys are stored on disk like the other keys, the timeout is persistent too like all the
	 * other aspects of the dataset. Saving a dataset containing expires and stopping the server does
	 * not stop the flow of time as Redis stores on disk the time when the key will no longer be
	 * available as Unix time, and not the remaining seconds.
	 * <p>
	 * Since Redis 2.1.3 you can update the value of the timeout of a key already having an expire
	 * set. It is also possible to undo the expire at all turning the key into a normal key using the
	 * {@link #persist(byte[]) PERSIST} command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param seconds
	 * @return Integer reply, specifically: 1: the timeout was set. 0: the timeout was not set since
	 * = 2.1.3 will happily update the timeout), or the key does not exist.
	 * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">ExpireCommand</a>
	 */
	@Override
	public Long expire(final byte[] key, final int seconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.expire(key, seconds);
        }
	}

	/**
	 * EXPIREAT works exactly like {@link #expire(byte[], int) EXPIRE} but instead to get the number of
	 * seconds representing the Time To Live of the key as a second argument (that is a relative way
	 * of specifying the TTL), it takes an absolute one in the form of a UNIX timestamp (Number of
	 * seconds elapsed since 1 Gen 1970).
	 * <p>
	 * EXPIREAT was introduced in order to implement the Append Only File persistence mode so that
	 * EXPIRE commands are automatically translated into EXPIREAT commands for the append only file.
	 * Of course EXPIREAT can also used by programmers that need a way to simply specify that a given
	 * key should expire at a given time in the future.
	 * <p>
	 * Since Redis 2.1.3 you can update the value of the timeout of a key already having an expire
	 * set. It is also possible to undo the expire at all turning the key into a normal key using the
	 * {@link #persist(byte[]) PERSIST} command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param unixTime
	 * @return Integer reply, specifically: 1: the timeout was set. 0: the timeout was not set since
	 * = 2.1.3 will happily update the timeout), or the key does not exist.
	 * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">ExpireCommand</a>
	 */
	@Override
	public Long expireAt(final byte[] key, final long unixTime){
        try(Jedis jedis = pool.getResource()){
            return jedis.expireAt(key, unixTime);
        }
	}

	/**
	 * The TTL command returns the remaining time to live in seconds of a key that has an
	 * {@link #expire(byte[], int) EXPIRE} set. This introspection capability allows a Redis client to
	 * check how many seconds a given key will continue to be part of the dataset.
	 *
	 * @param key
	 * @return Integer reply, returns the remaining time to live in seconds of a key that has an
	 * EXPIRE. If the Key does not exists or does not have an associated expire, -1 is
	 * returned.
	 */
	@Override
	public Long ttl(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.ttl(key);
        }
	}

	/**
	 * Alters the last access time of a key(s). A key is ignored if it does not exist.
	 * Time complexity: O(N) where N is the number of keys that will be touched.
	 *
	 * @param keys
	 * @return Integer reply: The number of keys that were touched.
	 */
	@Override
	public Long touch(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.touch(keys);
        }
	}

	@Override
	public Long touch(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.touch(key);
        }
	}

	/**
	 * Move the specified key from the currently selected DB to the specified destination DB. Note
	 * that this command returns 1 only if the key was successfully moved, and 0 if the target key was
	 * already there or if the source key was not found at all, so it is possible to use MOVE as a
	 * locking primitive.
	 *
	 * @param key
	 * @param dbIndex
	 * @return Integer reply, specifically: 1 if the key was moved 0 if the key was not moved because
	 * already present on the target DB or was not found in the current DB.
	 */
	@Override
	public Long move(final byte[] key, final int dbIndex){
        try(Jedis jedis = pool.getResource()){
            return jedis.move(key, dbIndex);
        }
	}

	@Override
	public Long bitcount(byte[] key){
		try(Jedis jedis = pool.getResource()){
			return jedis.bitcount(key);
		}
	}

	/**
	 * GETSET is an atomic set this value and return the old value command. Set key to the string
	 * value and return the old value stored at key. The string can't be longer than 1073741824 bytes
	 * (1 GB).
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Bulk reply
	 */
	@Override
	public byte[] getSet(final byte[] key, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.getSet(key, value);
        }
	}

	/**
	 * Get the values of all the specified keys. If one or more keys don't exist or is not of type
	 * String, a 'nil' value is returned instead of the value of the specified key, but the operation
	 * never fails.
	 * <p>
	 * Time complexity: O(1) for every key
	 *
	 * @param keys
	 * @return Multi bulk reply
	 */
	@Override
	public List<byte[]> mget(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.mget(keys);
        }
	}

	/**
	 * SETNX works exactly like {@link #set(byte[], byte[]) SET} with the only difference that if the
	 * key already exists no operation is performed. SETNX actually means "SET if Not eXists".
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Integer reply, specifically: 1 if the key was set 0 if the key was not set
	 */
	@Override
	public Long setnx(final byte[] key, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setnx(key, value);
        }
	}

	/**
	 * The command is exactly equivalent to the following group of commands:
	 * {@link #set(byte[], byte[]) SET} + {@link #expire(byte[], int) EXPIRE}. The operation is
	 * atomic.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param seconds
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String setex(final byte[] key, final int seconds, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setex(key, seconds, value);
        }
	}

	/**
	 * Set the the respective keys to the respective values. MSET will replace old values with new
	 * values, while {@link #msetnx(byte[]...) MSETNX} will not perform any operation at all even if
	 * just a single key already exists.
	 * <p>
	 * Because of this semantic MSETNX can be used in order to set different keys representing
	 * different fields of an unique logic object in a way that ensures that either all the fields or
	 * none at all are set.
	 * <p>
	 * Both MSET and MSETNX are atomic operations. This means that for instance if the keys A and B
	 * are modified, another client talking to Redis can either see the changes to both A and B at
	 * once, or no modification at all.
	 *
	 * @param keysvalues
	 * @return Status code reply Basically +OK as MSET can't fail
	 * @see #msetnx(byte[]...)
	 */
	@Override
	public String mset(final byte[]... keysvalues){
        try(Jedis jedis = pool.getResource()){
            return jedis.mset(keysvalues);
        }
	}

	/**
	 * Set the the respective keys to the respective values. {@link #mset(byte[]...) MSET} will
	 * replace old values with new values, while MSETNX will not perform any operation at all even if
	 * just a single key already exists.
	 * <p>
	 * Because of this semantic MSETNX can be used in order to set different keys representing
	 * different fields of an unique logic object in a way that ensures that either all the fields or
	 * none at all are set.
	 * <p>
	 * Both MSET and MSETNX are atomic operations. This means that for instance if the keys A and B
	 * are modified, another client talking to Redis can either see the changes to both A and B at
	 * once, or no modification at all.
	 *
	 * @param keysvalues
	 * @return Integer reply, specifically: 1 if the all the keys were set 0 if no key was set (at
	 * least one key already existed)
	 * @see #mset(byte[]...)
	 */
	@Override
	public Long msetnx(final byte[]... keysvalues){
        try(Jedis jedis = pool.getResource()){
            return jedis.msetnx(keysvalues);
        }
	}

	/**
	 * DECRBY work just like {@link #decr(byte[]) INCR} but instead to decrement by 1 the decrement is
	 * integer.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param decrement
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(byte[])
	 * @see #decr(byte[])
	 * @see #incrBy(byte[], long)
	 */
	@Override
	public Long decrBy(final byte[] key, final long decrement){
        try(Jedis jedis = pool.getResource()){
            return jedis.decrBy(key, decrement);
        }
	}

	/**
	 * Decrement the number stored at key by one. If the key does not exist or contains a value of a
	 * wrong type, set the key to the value of "0" before to perform the decrement operation.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(byte[])
	 * @see #incrBy(byte[], long)
	 * @see #decrBy(byte[], long)
	 */
	@Override
	public Long decr(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.decr(key);
        }
	}

	/**
	 * INCRBY work just like {@link #incr(byte[]) INCR} but instead to increment by 1 the increment is
	 * integer.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param increment
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(byte[])
	 * @see #decr(byte[])
	 * @see #decrBy(byte[], long)
	 */
	@Override
	public Long incrBy(final byte[] key, final long increment){
        try(Jedis jedis = pool.getResource()){
            return jedis.incrBy(key, increment);
        }
	}

	/**
	 * INCRBYFLOAT work just like {@link #incrBy(byte[], long)} INCRBY} but increments by floats
	 * instead of integers.
	 * <p>
	 * INCRBYFLOAT commands are limited to double precision floating point values.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "double" types.
	 * Simply the string stored at the key is parsed as a base double precision floating point value,
	 * incremented, and then converted back as a string. There is no DECRYBYFLOAT but providing a
	 * negative value will work as expected.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key       the key to increment
	 * @param increment the value to increment by
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(byte[])
	 * @see #decr(byte[])
	 * @see #decrBy(byte[], long)
	 */
	@Override
	public Double incrByFloat(final byte[] key, final double increment){
        try(Jedis jedis = pool.getResource()){
            return jedis.incrByFloat(key, increment);
        }
	}

	/**
	 * Increment the number stored at key by one. If the key does not exist or contains a value of a
	 * wrong type, set the key to the value of "0" before to perform the increment operation.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incrBy(byte[], long)
	 * @see #decr(byte[])
	 * @see #decrBy(byte[], long)
	 */
	@Override
	public Long incr(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.incr(key);
        }
	}

	/**
	 * If the key already exists and is a string, this command appends the provided value at the end
	 * of the string. If the key does not exist it is created and set as an empty string, so APPEND
	 * will be very similar to SET in this special case.
	 * <p>
	 * Time complexity: O(1). The amortized time complexity is O(1) assuming the appended value is
	 * small and the already present value is of any size, since the dynamic string library used by
	 * Redis will double the free space available on every reallocation.
	 *
	 * @param key
	 * @param value
	 * @return Integer reply, specifically the total length of the string after the append operation.
	 */
	@Override
	public Long append(final byte[] key, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.append(key, value);
        }
	}

	/**
	 * Return a subset of the string from offset start to offset end (both offsets are inclusive).
	 * Negative offsets can be used in order to provide an offset starting from the end of the string.
	 * So -1 means the last char, -2 the penultimate and so forth.
	 * <p>
	 * The function handles out of range requests without raising an error, but just limiting the
	 * resulting range to the actual length of the string.
	 * <p>
	 * Time complexity: O(start+n) (with start being the start index and n the total length of the
	 * requested range). Note that the lookup part of this command is O(1) so for small strings this
	 * is actually an O(1) command.
	 *
	 * @param key
	 * @param start
	 * @param end
	 * @return Bulk reply
	 */
	@Override
	public byte[] substr(final byte[] key, final int start, final int end){
        try(Jedis jedis = pool.getResource()){
            return jedis.substr(key, start, end);
        }
	}

	/**
	 * Set the specified hash field to the specified value.
	 * <p>
	 * If key does not exist, a new key holding a hash is created.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return If the field already exists, and the HSET just produced an update of the value, 0 is
	 * returned, otherwise if a new field is created 1 is returned.
	 */
	@Override
	public Long hset(final byte[] key, final byte[] field, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hset(key, field, value);
        }
	}

	@Override
	public Long hset(final byte[] key, final Map<byte[], byte[]> hash){
        try(Jedis jedis = pool.getResource()){
            return jedis.hset(key, hash);
        }
	}

	/**
	 * If key holds a hash, retrieve the value associated to the specified field.
	 * <p>
	 * If the field is not found or the key does not exist, a special 'nil' value is returned.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @return Bulk reply
	 */
	@Override
	public byte[] hget(final byte[] key, final byte[] field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hget(key, field);
        }
	}

	/**
	 * Set the specified hash field to the specified value if the field not exists. <b>Time
	 * complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return If the field already exists, 0 is returned, otherwise if a new field is created 1 is
	 * returned.
	 */
	@Override
	public Long hsetnx(final byte[] key, final byte[] field, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hsetnx(key, field, value);
        }
	}

	/**
	 * Set the respective fields to the respective values. HMSET replaces old values with new values.
	 * <p>
	 * If key does not exist, a new key holding a hash is created.
	 * <p>
	 * <b>Time complexity:</b> O(N) (with N being the number of fields)
	 *
	 * @param key
	 * @param hash
	 * @return Always OK because HMSET can't fail
	 */
	@Override
	public String hmset(final byte[] key, final Map<byte[], byte[]> hash){
        try(Jedis jedis = pool.getResource()){
            return jedis.hmset(key, hash);
        }
	}

	/**
	 * Retrieve the values associated to the specified fields.
	 * <p>
	 * If some of the specified fields do not exist, nil values are returned. Non existing keys are
	 * considered like empty hashes.
	 * <p>
	 * <b>Time complexity:</b> O(N) (with N being the number of fields)
	 *
	 * @param key
	 * @param fields
	 * @return Multi Bulk Reply specifically a list of all the values associated with the specified
	 * fields, in the same order of the request.
	 */
	@Override
	public List<byte[]> hmget(final byte[] key, final byte[]... fields){
        try(Jedis jedis = pool.getResource()){
            return jedis.hmget(key, fields);
        }
	}

	/**
	 * Increment the number stored at field in the hash at key by value. If key does not exist, a new
	 * key holding a hash is created. If field does not exist or holds a string, the value is set to 0
	 * before applying the operation. Since the value argument is signed you can use this command to
	 * perform both increments and decrements.
	 * <p>
	 * The range of values supported by HINCRBY is limited to 64 bit signed integers.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return Integer reply The new value at field after the increment operation.
	 */
	@Override
	public Long hincrBy(final byte[] key, final byte[] field, final long value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hincrBy(key, field, value);
        }
	}

	/**
	 * Increment the number stored at field in the hash at key by a double precision floating point
	 * value. If key does not exist, a new key holding a hash is created. If field does not exist or
	 * holds a string, the value is set to 0 before applying the operation. Since the value argument
	 * is signed you can use this command to perform both increments and decrements.
	 * <p>
	 * The range of values supported by HINCRBYFLOAT is limited to double precision floating point
	 * values.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return Double precision floating point reply The new value at field after the increment
	 * operation.
	 */
	@Override
	public Double hincrByFloat(final byte[] key, final byte[] field, final double value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hincrByFloat(key, field, value);
        }
	}

	/**
	 * Test for existence of a specified field in a hash. <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @return Return true if the hash stored at key contains the specified field. Return false if the key is
	 * not found or the field is not present.
	 */
	@Override
	public Boolean hexists(final byte[] key, final byte[] field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hexists(key, field);
        }
	}

	/**
	 * Remove the specified field from an hash stored at key.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param fields
	 * @return If the field was present in the hash it is deleted and 1 is returned, otherwise 0 is
	 * returned and no operation is performed.
	 */
	@Override
	public Long hdel(final byte[] key, final byte[]... fields){
        try(Jedis jedis = pool.getResource()){
            return jedis.hdel(key, fields);
        }
	}

	/**
	 * Return the number of items in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @return The number of entries (fields) contained in the hash stored at key. If the specified
	 * key does not exist, 0 is returned assuming an empty hash.
	 */
	@Override
	public Long hlen(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hlen(key);
        }
	}

	/**
	 * Return all the fields in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(N), where N is the total number of entries
	 *
	 * @param key
	 * @return All the fields names contained into a hash.
	 */
	@Override
	public Set<byte[]> hkeys(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hkeys(key);
        }
	}

	/**
	 * Return all the values in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(N), where N is the total number of entries
	 *
	 * @param key
	 * @return All the fields values contained into a hash.
	 */
	@Override
	public List<byte[]> hvals(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hvals(key);
        }
	}

	@Override
	public Map<byte[], byte[]> hgetAll(byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hgetAll(key);
        }
	}

	/**
	 * Add the string value to the head (LPUSH) or tail (RPUSH) of the list stored at key. If the key
	 * does not exist an empty list is created just before the append operation. If the key exists but
	 * is not a List an error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param strings
	 * @return Integer reply, specifically, the number of elements inside the list after the push
	 * operation.
	 * @see BinaryJedis#rpush(byte[], byte[]...)
	 */
	@Override
	public Long rpush(final byte[] key, final byte[]... strings){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpush(key, strings);
        }
	}

	/**
	 * Add the string value to the head (LPUSH) or tail (RPUSH) of the list stored at key. If the key
	 * does not exist an empty list is created just before the append operation. If the key exists but
	 * is not a List an error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param strings
	 * @return Integer reply, specifically, the number of elements inside the list after the push
	 * operation.
	 * @see BinaryJedis#rpush(byte[], byte[]...)
	 */
	@Override
	public Long lpush(final byte[] key, final byte[]... strings){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpush(key, strings);
        }
	}

	/**
	 * Return the length of the list stored at the specified key. If the key does not exist zero is
	 * returned (the same behaviour as for empty lists). If the value stored at key is not a list an
	 * error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return The length of the list.
	 */
	@Override
	public Long llen(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.llen(key);
        }
	}

	/**
	 * Return the specified elements of the list stored at the specified key. Start and end are
	 * zero-based indexes. 0 is the first element of the list (the list head), 1 the next element and
	 * so on.
	 * <p>
	 * For example LRANGE foobar 0 2 will return the first three elements of the list.
	 * <p>
	 * start and end can also be negative numbers indicating offsets from the end of the list. For
	 * example -1 is the last element of the list, -2 the penultimate element and so on.
	 * <p>
	 * <b>Consistency with range functions in various programming languages</b>
	 * <p>
	 * Note that if you have a list of numbers from 0 to 100, LRANGE 0 10 will return 11 elements,
	 * that is, rightmost item is included. This may or may not be consistent with behavior of
	 * range-related functions in your programming language of choice (think Ruby's Range.new,
	 * Array#slice or Python's range() function).
	 * <p>
	 * LRANGE behavior is consistent with one of Tcl.
	 * <p>
	 * <b>Out-of-range indexes</b>
	 * <p>
	 * Indexes out of range will not produce an error: if start is over the end of the list, or start
	 * end, an empty list is returned. If end is over the end of the list Redis will threat it
	 * just like the last element of the list.
	 * <p>
	 * Time complexity: O(start+n) (with n being the length of the range and start being the start
	 * offset)
	 *
	 * @param key
	 * @param start
	 * @param stop
	 * @return Multi bulk reply, specifically a list of elements in the specified range.
	 */
	@Override
	public List<byte[]> lrange(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.lrange(key, start, stop);
        }
	}

	/**
	 * Trim an existing list so that it will contain only the specified range of elements specified.
	 * Start and end are zero-based indexes. 0 is the first element of the list (the list head), 1 the
	 * next element and so on.
	 * <p>
	 * For example LTRIM foobar 0 2 will modify the list stored at foobar key so that only the first
	 * three elements of the list will remain.
	 * <p>
	 * start and end can also be negative numbers indicating offsets from the end of the list. For
	 * example -1 is the last element of the list, -2 the penultimate element and so on.
	 * <p>
	 * Indexes out of range will not produce an error: if start is over the end of the list, or start
	 * end, an empty list is left as value. If end over the end of the list Redis will threat it
	 * just like the last element of the list.
	 * <p>
	 * Hint: the obvious use of LTRIM is together with LPUSH/RPUSH. For example:
	 * <p>
	 * }
	 * <p>
	 * The above two commands will push elements in the list taking care that the list will not grow
	 * without limits. This is very useful when using Redis to store logs for example. It is important
	 * to note that when used in this way LTRIM is an O(1) operation because in the average case just
	 * one element is removed from the tail of the list.
	 * <p>
	 * Time complexity: O(n) (with n being len of list - len of range)
	 *
	 * @param key
	 * @param start
	 * @param stop
	 * @return Status code reply
	 */
	@Override
	public String ltrim(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.ltrim(key, start, stop);
        }
	}

	/**
	 * Return the specified element of the list stored at the specified key. 0 is the first element, 1
	 * the second and so on. Negative indexes are supported, for example -1 is the last element, -2
	 * the penultimate and so on.
	 * <p>
	 * If the value stored at key is not of list type an error is returned. If the index is out of
	 * range a 'nil' reply is returned.
	 * <p>
	 * Note that even if the average time complexity is O(n) asking for the first or the last element
	 * of the list is O(1).
	 * <p>
	 * Time complexity: O(n) (with n being the length of the list)
	 *
	 * @param key
	 * @param index
	 * @return Bulk reply, specifically the requested element
	 */
	@Override
	public byte[] lindex(final byte[] key, final long index){
        try(Jedis jedis = pool.getResource()){
            return jedis.lindex(key, index);
        }
	}

	/**
	 * Set a new value as the element at index position of the List at key.
	 * <p>
	 * Out of range indexes will generate an error.
	 * <p>
	 * Similarly to other list commands accepting indexes, the index can be negative to access
	 * elements starting from the end of the list. So -1 is the last element, -2 is the penultimate,
	 * and so forth.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(N) (with N being the length of the list), setting the first or last elements of the list is
	 * O(1).
	 *
	 * @param key
	 * @param index
	 * @param value
	 * @return Status code reply
	 * @see #lindex(byte[], long)
	 */
	@Override
	public String lset(final byte[] key, final long index, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.lset(key, index, value);
        }
	}

	/**
	 * Remove the first count occurrences of the value element from the list. If count is zero all the
	 * elements are removed. If count is negative elements are removed from tail to head, instead to
	 * go from head to tail that is the normal behaviour. So for example LREM with count -2 and hello
	 * as value to remove against the list (a,b,c,hello,x,hello,hello) will leave the list
	 * (a,b,c,hello,x). The number of removed elements is returned as an integer, see below for more
	 * information about the returned value. Note that non existing keys are considered like empty
	 * lists by LREM, so LREM against non existing keys will always return 0.
	 * <p>
	 * Time complexity: O(N) (with N being the length of the list)
	 *
	 * @param key
	 * @param count
	 * @param value
	 * @return Integer Reply, specifically: The number of removed elements if the operation succeeded
	 */
	@Override
	public Long lrem(final byte[] key, final long count, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.lrem(key, count, value);
        }
	}

	/**
	 * Atomically return and remove the first (LPOP) or last (RPOP) element of the list. For example
	 * if the list contains the elements "a","b","c" LPOP will return "a" and the list will become
	 * "b","c".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned.
	 *
	 * @param key
	 * @return Bulk reply
	 * @see #rpop(byte[])
	 */
	@Override
	public byte[] lpop(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpop(key);
        }
	}

	/**
	 * Atomically return and remove the first (LPOP) or last (RPOP) element of the list. For example
	 * if the list contains the elements "a","b","c" LPOP will return "a" and the list will become
	 * "b","c".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned.
	 *
	 * @param key
	 * @return Bulk reply
	 * @see #lpop(byte[])
	 */
	@Override
	public byte[] rpop(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpop(key);
        }
	}

	/**
	 * Atomically return and remove the last (tail) element of the srckey list, and push the element
	 * as the first (head) element of the dstkey list. For example if the source list contains the
	 * elements "a","b","c" and the destination list contains the elements "foo","bar" after an
	 * RPOPLPUSH command the content of the two lists will be "a","b" and "c","foo","bar".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned. If
	 * the srckey and dstkey are the same the operation is equivalent to removing the last element
	 * from the list and pushing it as first element of the list, so it's a "list rotation" command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param srckey
	 * @param dstkey
	 * @return Bulk reply
	 */
	@Override
	public byte[] rpoplpush(final byte[] srckey, final byte[] dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpoplpush(srckey, dstkey);
        }
	}

	/**
	 * Add the specified member to the set value stored at key. If member is already a member of the
	 * set no operation is performed. If key does not exist a new set with the specified member as
	 * sole member is created. If the key exists but does not hold a set value an error is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @param members
	 * @return Integer reply, specifically: 1 if the new element was added 0 if the element was
	 * already a member of the set
	 */
	@Override
	public Long sadd(final byte[] key, final byte[]... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.sadd(key, members);
        }
	}

	/**
	 * Return all the members (elements) of the set value stored at key. This is just syntax glue for
	 * {@link #sinter(byte[]...)} SINTER}.
	 * <p>
	 * Time complexity O(N)
	 *
	 * @param key the key of the set
	 * @return Multi bulk reply
	 */
	@Override
	public Set<byte[]> smembers(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.smembers(key);
        }
	}

	/**
	 * Remove the specified member from the set value stored at key. If member was not a member of the
	 * set no operation is performed. If key does not hold a set value an error is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key    the key of the set
	 * @param member the set member to remove
	 * @return Integer reply, specifically: 1 if the new element was removed 0 if the new element was
	 * not a member of the set
	 */
	@Override
	public Long srem(final byte[] key, final byte[]... member){
        try(Jedis jedis = pool.getResource()){
            return jedis.srem(key, member);
        }
	}

	/**
	 * Remove a random element from a Set returning it as return value. If the Set is empty or the key
	 * does not exist, a nil object is returned.
	 * <p>
	 * The {@link #srandmember(byte[])} command does a similar work but the returned element is not
	 * removed from the Set.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public byte[] spop(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.spop(key);
        }
	}

	@Override
	public Set<byte[]> spop(final byte[] key, final long count){
        try(Jedis jedis = pool.getResource()){
            return jedis.spop(key, count);
        }
	}

	/**
	 * Move the specified member from the set at srckey to the set at dstkey. This operation is
	 * atomic, in every given moment the element will appear to be in the source or destination set
	 * for accessing clients.
	 * <p>
	 * If the source set does not exist or does not contain the specified element no operation is
	 * performed and zero is returned, otherwise the element is removed from the source set and added
	 * to the destination set. On success one is returned, even if the element was already present in
	 * the destination set.
	 * <p>
	 * An error is raised if the source or destination keys contain a non Set value.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param srckey
	 * @param dstkey
	 * @param member
	 * @return Integer reply, specifically: 1 if the element was moved 0 if the element was not found
	 * on the first set and no operation was performed
	 */
	@Override
	public Long smove(final byte[] srckey, final byte[] dstkey, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.smove(srckey, dstkey, member);
        }
	}

	/**
	 * Return the set cardinality (number of elements). If the key does not exist 0 is returned, like
	 * for empty sets.
	 *
	 * @param key
	 * @return Integer reply, specifically: the cardinality (number of elements) of the set as an
	 * integer.
	 */
	@Override
	public Long scard(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.scard(key);
        }
	}

	/**
	 * Return true if member is a member of the set stored at key, otherwise false is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @param member
	 * @return Boolean reply, specifically: true if the element is a member of the set false if the element
	 * is not a member of the set OR if the key does not exist
	 */
	@Override
	public Boolean sismember(final byte[] key, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.sismember(key, member);
        }
	}

	/**
	 * Return the members of a set resulting from the intersection of all the sets hold at the
	 * specified keys. Like in {@link #lrange(byte[], long, long)} LRANGE} the result is sent to the
	 * client as a multi-bulk reply (see the protocol specification for more information). If just a
	 * single key is specified, then this command produces the same result as
	 * {@link #smembers(byte[]) SMEMBERS}. Actually SMEMBERS is just syntax sugar for SINTER.
	 * <p>
	 * Non existing keys are considered like empty sets, so if one of the keys is missing an empty set
	 * is returned (since the intersection with an empty set always is an empty set).
	 * <p>
	 * Time complexity O(N*M) worst case where N is the cardinality of the smallest set and M the
	 * number of sets
	 *
	 * @param keys
	 * @return Multi bulk reply, specifically the list of common elements.
	 */
	@Override
	public Set<byte[]> sinter(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sinter(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sinter(byte[]...) SINTER} but instead of being returned
	 * the resulting set is sorted as dstkey.
	 * <p>
	 * Time complexity O(N*M) worst case where N is the cardinality of the smallest set and M the
	 * number of sets
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sinterstore(final byte[] dstkey, final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sinterstore(dstkey, keys);
        }
	}

	/**
	 * Return the members of a set resulting from the union of all the sets hold at the specified
	 * keys. Like in {@link #lrange(byte[], long, long)} LRANGE} the result is sent to the client as a
	 * multi-bulk reply (see the protocol specification for more information). If just a single key is
	 * specified, then this command produces the same result as {@link #smembers(byte[]) SMEMBERS}.
	 * <p>
	 * Non existing keys are considered like empty sets.
	 * <p>
	 * Time complexity O(N) where N is the total number of elements in all the provided sets
	 *
	 * @param keys
	 * @return Multi bulk reply, specifically the list of common elements.
	 */
	@Override
	public Set<byte[]> sunion(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sunion(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sunion(byte[]...) SUNION} but instead of being returned
	 * the resulting set is stored as dstkey. Any existing value in dstkey will be over-written.
	 * <p>
	 * Time complexity O(N) where N is the total number of elements in all the provided sets
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sunionstore(final byte[] dstkey, final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sunionstore(dstkey, keys);
        }
	}

	/**
	 * Return the difference between the Set stored at key1 and all the Sets key2, ..., keyN
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * key1 = [x, a, b, c]
	 * key2 = [c]
	 * key3 = [a, d]
	 * [x, b]
	 * </pre>
	 * <p>
	 * Non existing keys are considered like empty sets.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(N) with N being the total number of elements of all the sets
	 *
	 * @param keys
	 * @return Return the members of a set resulting from the difference between the first set
	 * provided and all the successive sets.
	 */
	@Override
	public Set<byte[]> sdiff(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sdiff(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sdiff(byte[]...) SDIFF} but instead of being returned
	 * the resulting set is stored in dstkey.
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sdiffstore(final byte[] dstkey, final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sdiffstore(dstkey, keys);
        }
	}

	/**
	 * Return a random element from a Set, without removing the element. If the Set is empty or the
	 * key does not exist, a nil object is returned.
	 * <p>
	 * The SPOP command does a similar work but the returned element is popped (removed) from the Set.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public byte[] srandmember(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.srandmember(key);
        }
	}

	@Override
	public List<byte[]> srandmember(final byte[] key, final int count){
        try(Jedis jedis = pool.getResource()){
            return jedis.srandmember(key, count);
        }
	}

	/**
	 * Add the specified member having the specified score to the sorted set stored at key. If member
	 * is already a member of the sorted set the score is updated, and the element reinserted in the
	 * right position to ensure sorting. If key does not exist a new sorted set with the specified
	 * member as sole member is created. If the key exists but does not hold a sorted set value an
	 * error is returned.
	 * <p>
	 * The score value can be the string representation of a double precision floating point number.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param score
	 * @param member
	 * @return Integer reply, specifically: 1 if the new element was added 0 if the element was
	 * already a member of the sorted set and the score was updated
	 */
	@Override
	public Long zadd(final byte[] key, final double score, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, score, member);
        }
	}

	@Override
	public Long zadd(final byte[] key, final double score, final byte[] member, final ZAddParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, score, member, params);
        }
	}

	@Override
	public Long zadd(final byte[] key, final Map<byte[], Double> scoreMembers){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, scoreMembers);
        }
	}

	@Override
	public Long zadd(final byte[] key, final Map<byte[], Double> scoreMembers, final ZAddParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, scoreMembers, params);
        }
	}

	@Override
	public Set<byte[]> zrange(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrange(key, start, stop);
        }
	}

	/**
	 * Remove the specified member from the sorted set value stored at key. If member was not a member
	 * of the set no operation is performed. If key does not not hold a set value an error is
	 * returned.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param members
	 * @return Integer reply, specifically: 1 if the new element was removed 0 if the new element was
	 * not a member of the set
	 */
	@Override
	public Long zrem(final byte[] key, final byte[]... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrem(key, members);
        }
	}

	/**
	 * If member already exists in the sorted set adds the increment to its score and updates the
	 * position of the element in the sorted set accordingly. If member does not already exist in the
	 * sorted set it is added with increment as score (that is, like if the previous score was
	 * virtually zero). If key does not exist a new sorted set with the specified member as sole
	 * member is created. If the key exists but does not hold a sorted set value an error is returned.
	 * <p>
	 * The score value can be the string representation of a double precision floating point number.
	 * It's possible to provide a negative value to perform a decrement.
	 * <p>
	 * For an introduction to sorted sets check the Introduction to Redis data types page.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param increment
	 * @param member
	 * @return The new score
	 */
	@Override
	public Double zincrby(final byte[] key, final double increment, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zincrby(key, increment, member);
        }
	}

	@Override
	public Double zincrby(final byte[] key, final double increment, final byte[] member, final ZIncrByParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zincrby(key, increment, member, params);
        }
	}

	/**
	 * Return the rank (or index) or member in the sorted set at key, with scores being ordered from
	 * low to high.
	 * <p>
	 * When the given member does not exist in the sorted set, the special value 'nil' is returned.
	 * The returned rank (or index) of the member is 0-based for both commands.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))
	 *
	 * @param key
	 * @param member
	 * @return Integer reply or a nil bulk reply, specifically: the rank of the element as an integer
	 * reply if the element exists. A nil bulk reply if there is no such element.
	 * @see #zrevrank(byte[], byte[])
	 */
	@Override
	public Long zrank(final byte[] key, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrank(key, member);
        }
	}

	/**
	 * Return the rank (or index) or member in the sorted set at key, with scores being ordered from
	 * high to low.
	 * <p>
	 * When the given member does not exist in the sorted set, the special value 'nil' is returned.
	 * The returned rank (or index) of the member is 0-based for both commands.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))
	 *
	 * @param key
	 * @param member
	 * @return Integer reply or a nil bulk reply, specifically: the rank of the element as an integer
	 * reply if the element exists. A nil bulk reply if there is no such element.
	 * @see #zrank(byte[], byte[])
	 */
	@Override
	public Long zrevrank(final byte[] key, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrank(key, member);
        }
	}

	@Override
	public Set<byte[]> zrevrange(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrange(key, start, stop);
        }
	}

	@Override
	public Set<Tuple> zrangeWithScores(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeWithScores(key, start, stop);
        }
	}

	@Override
	public Set<Tuple> zrevrangeWithScores(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeWithScores(key, start, stop);
        }
	}

	/**
	 * Return the sorted set cardinality (number of elements). If the key does not exist 0 is
	 * returned, like for empty sorted sets.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return the cardinality (number of elements) of the set as an integer.
	 */
	@Override
	public Long zcard(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcard(key);
        }
	}

	/**
	 * Return the score of the specified element of the sorted set at key. If the specified element
	 * does not exist in the sorted set, or the key does not exist at all, a special 'nil' value is
	 * returned.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param member
	 * @return the score
	 */
	@Override
	public Double zscore(final byte[] key, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscore(key, member);
        }
	}

	@Override
	public String watch(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.watch(keys);
        }
	}

	@Override
	public String unwatch(){
        try(Jedis jedis = pool.getResource()){
            return jedis.unwatch();
        }
	}

	/**
	 * Sort a Set or a List.
	 * <p>
	 * Sort the elements contained in the List, Set, or Sorted Set value at key. By default sorting is
	 * numeric with elements being compared as double precision floating point numbers. This is the
	 * simplest form of SORT.
	 *
	 * @param key
	 * @return Assuming the Set/List at key contains a list of numbers, the return value will be the
	 * list of numbers ordered from the smallest to the biggest number.
	 * @see #sort(byte[], byte[])
	 * @see #sort(byte[], SortingParams)
	 * @see #sort(byte[], SortingParams, byte[])
	 */
	@Override
	public List<byte[]> sort(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key);
        }
	}

	/**
	 * Sort a Set or a List accordingly to the specified parameters.
	 * <p>
	 * <b>examples:</b>
	 * <p>
	 * Given are the following sets and key/values:
	 *
	 * <pre>
	 * x = [1, 2, 3]
	 * y = [a, b, c]
	 *
	 * k1 = z
	 * k2 = y
	 * k3 = x
	 *
	 * w1 = 9
	 * w2 = 8
	 * w3 = 7
	 * </pre>
	 * <p>
	 * Sort Order:
	 *
	 * <pre>
	 * sort(x) or sort(x, sp.asc())
	 * [1, 2, 3]
	 *
	 * sort(x, sp.desc())
	 * [3, 2, 1]
	 *
	 * sort(y)
	 * [c, a, b]
	 *
	 * sort(y, sp.alpha())
	 * [a, b, c]
	 *
	 * sort(y, sp.alpha().desc())
	 * [c, a, b]
	 * </pre>
	 * <p>
	 * Limit (e.g. for Pagination):
	 *
	 * <pre>
	 * sort(x, sp.limit(0, 2))
	 * [1, 2]
	 *
	 * sort(y, sp.alpha().desc().limit(1, 2))
	 * [b, a]
	 * </pre>
	 * <p>
	 * Sorting by external keys:
	 *
	 * <pre>
	 * sort(x, sb.by(w*))
	 * [3, 2, 1]
	 *
	 * sort(x, sb.by(w*).desc())
	 * [1, 2, 3]
	 * </pre>
	 * <p>
	 * Getting external keys:
	 *
	 * <pre>
	 * sort(x, sp.by(w*).get(k*))
	 * [x, y, z]
	 *
	 * sort(x, sp.by(w*).get(#).get(k*))
	 * [3, x, 2, y, 1, z]
	 * </pre>
	 *
	 * @param key
	 * @param sortingParameters
	 * @return a list of sorted elements.
	 * @see #sort(byte[])
	 * @see #sort(byte[], SortingParams, byte[])
	 */
	@Override
	public List<byte[]> sort(final byte[] key, final SortingParams sortingParameters){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, sortingParameters);
        }
	}

	/**
	 * BLPOP (and BRPOP) is a blocking list pop primitive. You can see this commands as blocking
	 * versions of LPOP and RPOP able to block if the specified keys don't exist or contain empty
	 * lists.
	 * <p>
	 * The following is a description of the exact semantic. We describe BLPOP but the two commands
	 * are identical, the only difference is that BLPOP pops the element from the left (head) of the
	 * list, and BRPOP pops from the right (tail).
	 * <p>
	 * <b>Non blocking behavior</b>
	 * <p>
	 * When BLPOP is called, if at least one of the specified keys contain a non empty list, an
	 * element is popped from the head of the list and returned to the caller together with the name
	 * of the key (BLPOP returns a two elements array, the first element is the key, the second the
	 * popped value).
	 * <p>
	 * Keys are scanned from left to right, so for instance if you issue BLPOP list1 list2 list3 0
	 * against a dataset where list1 does not exist but list2 and list3 contain non empty lists, BLPOP
	 * guarantees to return an element from the list stored at list2 (since it is the first non empty
	 * list starting from the left).
	 * <p>
	 * <b>Blocking behavior</b>
	 * <p>
	 * If none of the specified keys exist or contain non empty lists, BLPOP blocks until some other
	 * client performs a LPUSH or an RPUSH operation against one of the lists.
	 * <p>
	 * Once new data is present on one of the lists, the client finally returns with the name of the
	 * key unblocking it and the popped value.
	 * <p>
	 * When blocking, if a non-zero timeout is specified, the client will unblock returning a nil
	 * special value if the specified amount of seconds passed without a push operation against at
	 * least one of the specified keys.
	 * <p>
	 * The timeout argument is interpreted as an integer value. A timeout of zero means instead to
	 * block forever.
	 * <p>
	 * <b>Multiple clients blocking for the same keys</b>
	 * <p>
	 * Multiple clients can block for the same key. They are put into a queue, so the first to be
	 * served will be the one that started to wait earlier, in a first-blpopping first-served fashion.
	 * <p>
	 * <b>blocking POP inside a MULTI/EXEC transaction</b>
	 * <p>
	 * BLPOP and BRPOP can be used with pipelining (sending multiple commands and reading the replies
	 * in batch), but it does not make sense to use BLPOP or BRPOP inside a MULTI/EXEC block (a Redis
	 * transaction).
	 * <p>
	 * The behavior of BLPOP inside MULTI/EXEC when the list is empty is to return a multi-bulk nil
	 * reply, exactly what happens when the timeout is reached. If you like science fiction, think at
	 * it like if inside MULTI/EXEC the time will flow at infinite speed :)
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param timeout
	 * @param keys
	 * @return BLPOP returns a two-elements array via a multi bulk reply in order to return both the
	 * unblocking key and the popped value.
	 * <p>
	 * When a non-zero timeout is specified, and the BLPOP operation timed out, the return
	 * value is a nil multi bulk reply. Most client values will return false or nil
	 * accordingly to the programming language used.
	 * @see #brpop(int, byte[]...)
	 */
	@Override
	public List<byte[]> blpop(final int timeout, final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(timeout, keys);
        }
	}


	/**
	 * Sort a Set or a List accordingly to the specified parameters and store the result at dstkey.
	 *
	 * @param key
	 * @param sortingParameters
	 * @param dstkey
	 * @return The number of elements of the list at dstkey.
	 * @see #sort(byte[], SortingParams)
	 * @see #sort(byte[])
	 * @see #sort(byte[], byte[])
	 */
	@Override
	public Long sort(final byte[] key, final SortingParams sortingParameters, final byte[] dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, sortingParameters, dstkey);
        }
	}

	/**
	 * Sort a Set or a List and Store the Result at dstkey.
	 * <p>
	 * Sort the elements contained in the List, Set, or Sorted Set value at key and store the result
	 * at dstkey. By default sorting is numeric with elements being compared as double precision
	 * floating point numbers. This is the simplest form of SORT.
	 *
	 * @param key
	 * @param dstkey
	 * @return The number of elements of the list at dstkey.
	 * @see #sort(byte[])
	 * @see #sort(byte[], SortingParams)
	 * @see #sort(byte[], SortingParams, byte[])
	 */
	@Override
	public Long sort(final byte[] key, final byte[] dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, dstkey);
        }
	}

	/**
	 * BLPOP (and BRPOP) is a blocking list pop primitive. You can see this commands as blocking
	 * versions of LPOP and RPOP able to block if the specified keys don't exist or contain empty
	 * lists.
	 * <p>
	 * The following is a description of the exact semantic. We describe BLPOP but the two commands
	 * are identical, the only difference is that BLPOP pops the element from the left (head) of the
	 * list, and BRPOP pops from the right (tail).
	 * <p>
	 * <b>Non blocking behavior</b>
	 * <p>
	 * When BLPOP is called, if at least one of the specified keys contain a non empty list, an
	 * element is popped from the head of the list and returned to the caller together with the name
	 * of the key (BLPOP returns a two elements array, the first element is the key, the second the
	 * popped value).
	 * <p>
	 * Keys are scanned from left to right, so for instance if you issue BLPOP list1 list2 list3 0
	 * against a dataset where list1 does not exist but list2 and list3 contain non empty lists, BLPOP
	 * guarantees to return an element from the list stored at list2 (since it is the first non empty
	 * list starting from the left).
	 * <p>
	 * <b>Blocking behavior</b>
	 * <p>
	 * If none of the specified keys exist or contain non empty lists, BLPOP blocks until some other
	 * client performs a LPUSH or an RPUSH operation against one of the lists.
	 * <p>
	 * Once new data is present on one of the lists, the client finally returns with the name of the
	 * key unblocking it and the popped value.
	 * <p>
	 * When blocking, if a non-zero timeout is specified, the client will unblock returning a nil
	 * special value if the specified amount of seconds passed without a push operation against at
	 * least one of the specified keys.
	 * <p>
	 * The timeout argument is interpreted as an integer value. A timeout of zero means instead to
	 * block forever.
	 * <p>
	 * <b>Multiple clients blocking for the same keys</b>
	 * <p>
	 * Multiple clients can block for the same key. They are put into a queue, so the first to be
	 * served will be the one that started to wait earlier, in a first-blpopping first-served fashion.
	 * <p>
	 * <b>blocking POP inside a MULTI/EXEC transaction</b>
	 * <p>
	 * BLPOP and BRPOP can be used with pipelining (sending multiple commands and reading the replies
	 * in batch), but it does not make sense to use BLPOP or BRPOP inside a MULTI/EXEC block (a Redis
	 * transaction).
	 * <p>
	 * The behavior of BLPOP inside MULTI/EXEC when the list is empty is to return a multi-bulk nil
	 * reply, exactly what happens when the timeout is reached. If you like science fiction, think at
	 * it like if inside MULTI/EXEC the time will flow at infinite speed :)
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param timeout
	 * @param keys
	 * @return BLPOP returns a two-elements array via a multi bulk reply in order to return both the
	 * unblocking key and the popped value.
	 * <p>
	 * When a non-zero timeout is specified, and the BLPOP operation timed out, the return
	 * value is a nil multi bulk reply. Most client values will return false or nil
	 * accordingly to the programming language used.
	 * @see #blpop(int, byte[]...)
	 */
	@Override
	public List<byte[]> brpop(final int timeout, final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(timeout, keys);
        }
	}

	@Override
	public List<byte[]> blpop(final byte[]... args){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(args);
        }
	}

	@Override
	public List<byte[]> brpop(final byte[]... args){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(args);
        }
	}

	@Override
	public Long zcount(final byte[] key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcount(key, min, max);
        }
	}

	@Override
	public Long zcount(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcount(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(byte[], double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(byte[], double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(byte[], double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(byte[], double, double)
	 * @see #zrangeByScore(byte[], double, double, int, int)
	 * @see #zrangeByScoreWithScores(byte[], double, double)
	 * @see #zrangeByScoreWithScores(byte[], double, double, int, int)
	 * @see #zcount(byte[], double, double)
	 */
	@Override
	public Set<byte[]> zrangeByScore(final byte[] key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScore(key, min, max);
        }
	}

	@Override
	public Set<byte[]> zrangeByScore(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScore(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(byte[], double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(byte[], double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(byte[], double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(byte[], double, double)
	 * @see #zrangeByScore(byte[], double, double, int, int)
	 * @see #zrangeByScoreWithScores(byte[], double, double)
	 * @see #zrangeByScoreWithScores(byte[], double, double, int, int)
	 * @see #zcount(byte[], double, double)
	 */
	@Override
	public Set<byte[]> zrangeByScore(final byte[] key, final double min, final double max,
	                                 final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScore(key, min, max, offset, count);
		}
	}

	@Override
	public Set<byte[]> zrangeByScore(final byte[] key, final byte[] min, final byte[] max,
	                                 final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScore(key, min, max, offset, count);
		}
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(byte[], double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(byte[], double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(byte[], double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(byte[], double, double)
	 * @see #zrangeByScore(byte[], double, double, int, int)
	 * @see #zrangeByScoreWithScores(byte[], double, double)
	 * @see #zrangeByScoreWithScores(byte[], double, double, int, int)
	 * @see #zcount(byte[], double, double)
	 */
	@Override
	public Set<Tuple> zrangeByScoreWithScores(final byte[] key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScoreWithScores(key, min, max);
        }
	}

	@Override
	public Set<Tuple> zrangeByScoreWithScores(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScoreWithScores(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(byte[], double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(byte[], double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(byte[], double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(byte[], double, double)
	 * @see #zrangeByScore(byte[], double, double, int, int)
	 * @see #zrangeByScoreWithScores(byte[], double, double)
	 * @see #zrangeByScoreWithScores(byte[], double, double, int, int)
	 * @see #zcount(byte[], double, double)
	 */
	@Override
	public Set<Tuple> zrangeByScoreWithScores(final byte[] key, final double min, final double max,
	                                          final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScoreWithScores(key, min, max, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrangeByScoreWithScores(final byte[] key, final byte[] min, final byte[] max,
	                                          final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScoreWithScores(key, min, max, offset, count);
		}
	}

	@Override
	public Set<byte[]> zrevrangeByScore(final byte[] key, final double max, final double min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScore(key, max, min);
        }
	}

	@Override
	public Set<byte[]> zrevrangeByScore(final byte[] key, final byte[] max, final byte[] min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScore(key, max, min);
        }
	}

	@Override
	public Set<byte[]> zrevrangeByScore(final byte[] key, final double max, final double min,
	                                    final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScore(key, max, min, offset, count);
		}
	}

	@Override
	public Set<byte[]> zrevrangeByScore(final byte[] key, final byte[] max, final byte[] min,
	                                    final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScore(key, max, min, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final byte[] key, final double max, final double min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScoreWithScores(key, max, min);
        }
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final byte[] key, final double max,
	                                             final double min, final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScoreWithScores(key, max, min, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final byte[] key, final byte[] max, final byte[] min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScoreWithScores(key, max, min);
        }
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final byte[] key, final byte[] max,
	                                             final byte[] min, final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScoreWithScores(key, max, min, offset, count);
		}
	}

	/**
	 * Remove all elements in the sorted set at key with rank between start and end. Start and end are
	 * 0-based with rank 0 being the element with the lowest score. Both start and end can be negative
	 * numbers, where they indicate offsets starting at the element with the highest rank. For
	 * example: -1 is the element with the highest score, -2 the element with the second highest score
	 * and so forth.
	 * <p>
	 * <b>Time complexity:</b> O(log(N))+O(M) with N being the number of elements in the sorted set
	 * and M the number of elements removed by the operation
	 */
	@Override
	public Long zremrangeByRank(final byte[] key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByRank(key, start, stop);
        }
	}

	/**
	 * Remove all the elements in the sorted set at key with a score between min and max (including
	 * elements with score equal to min or max).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements removed by the operation
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Integer reply, specifically the number of elements removed.
	 */
	@Override
	public Long zremrangeByScore(final byte[] key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByScore(key, min, max);
        }
	}

	@Override
	public Long zremrangeByScore(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByScore(key, min, max);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(byte[], byte[]...)} ZINTERSTORE} command requires
	 * an element to be present in each of the given inputs to be inserted in the result. The {@link
	 * #zunionstore(byte[], byte[]...)} command inserts all elements across all inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(byte[], byte[]...)
	 * @see #zunionstore(byte[], ZParams, byte[]...)
	 * @see #zinterstore(byte[], byte[]...)
	 * @see #zinterstore(byte[], ZParams, byte[]...)
	 */
	@Override
	public Long zunionstore(final byte[] dstkey, final byte[]... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zunionstore(dstkey, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(byte[], byte[]...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The {@link
	 * #zunionstore(byte[], byte[]...) ZUNIONSTORE} command inserts all elements across all inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @param params
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(byte[], byte[]...)
	 * @see #zunionstore(byte[], ZParams, byte[]...)
	 * @see #zinterstore(byte[], byte[]...)
	 * @see #zinterstore(byte[], ZParams, byte[]...)
	 */
	@Override
	public Long zunionstore(final byte[] dstkey, final ZParams params, final byte[]... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zunionstore(dstkey, params, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(byte[], byte[]...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The {@link
	 * #zunionstore(byte[], byte[]...) ZUNIONSTORE} command inserts all elements across all inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(byte[], byte[]...)
	 * @see #zunionstore(byte[], ZParams, byte[]...)
	 * @see #zinterstore(byte[], byte[]...)
	 * @see #zinterstore(byte[], ZParams, byte[]...)
	 */
	@Override
	public Long zinterstore(final byte[] dstkey, final byte[]... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zinterstore(dstkey, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(byte[], byte[]...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The {@link
	 * #zunionstore(byte[], byte[]...) ZUNIONSTORE} command inserts all elements across all inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @param params
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(byte[], byte[]...)
	 * @see #zunionstore(byte[], ZParams, byte[]...)
	 * @see #zinterstore(byte[], byte[]...)
	 * @see #zinterstore(byte[], ZParams, byte[]...)
	 */
	@Override
	public Long zinterstore(final byte[] dstkey, final ZParams params, final byte[]... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zinterstore(dstkey, params, sets);
        }
	}

	@Override
	public Long zlexcount(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zlexcount(key, min, max);
        }
	}

	@Override
	public Set<byte[]> zrangeByLex(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByLex(key, min, max);
        }
	}

	@Override
	public Set<byte[]> zrangeByLex(final byte[] key, final byte[] min, final byte[] max,
	                               final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByLex(key, min, max, offset, count);
		}
	}

	@Override
	public Set<byte[]> zrevrangeByLex(final byte[] key, final byte[] max, final byte[] min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByLex(key, max, min);
        }
	}

	@Override
	public Set<byte[]> zrevrangeByLex(final byte[] key, final byte[] max, final byte[] min, final int offset, final int count){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByLex(key, max, min, offset, count);
        }
	}

	@Override
	public Long zremrangeByLex(final byte[] key, final byte[] min, final byte[] max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByLex(key, min, max);
        }
	}

	@Override
	public Long linsert(byte[] key, BinaryClient.LIST_POSITION where, byte[] pivot, byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.linsert(key, where, pivot, value);
        }
	}

	@Override
	public Long strlen(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.strlen(key);
        }
	}

	@Override
	public Long lpushx(final byte[] key, final byte[]... string){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpushx(key, string);
        }
	}

	/**
	 * Undo a {@link #expire(byte[], int) expire} at turning the expire key into a normal key.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, specifically: 1: the key is now persist. 0: the key is not persist (only
	 * happens when key not set).
	 */
	@Override
	public Long persist(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.persist(key);
        }
	}

	@Override
	public Long rpushx(final byte[] key, final byte[]... string){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpushx(key, string);
        }
	}

	@Override
	public List<byte[]> blpop(byte[] arg){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(arg);
        }
	}

	@Override
	public List<byte[]> brpop(byte[] arg){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(arg);
        }
	}

	@Override
	public byte[] echo(final byte[] string){
        try(Jedis jedis = pool.getResource()){
            return jedis.echo(string);
        }
	}

	@Override
	public Long linsert(final byte[] key, final ListPosition where, final byte[] pivot,
	                    final byte[] value){
		try(Jedis jedis = pool.getResource()){
			return jedis.linsert(key, where, pivot, value);
		}
	}

	/**
	 * or block until one is available
	 *
	 * @param source
	 * @param destination
	 * @param timeout
	 * @return the element
	 */
	@Override
	public byte[] brpoplpush(final byte[] source, final byte[] destination, final int timeout){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpoplpush(source, destination, timeout);
        }
	}

	/**
	 * Sets or clears the bit at offset in the string value stored at key
	 *
	 * @param key
	 * @param offset
	 * @param value
	 * @return
	 */
	@Override
	public Boolean setbit(final byte[] key, final long offset, final boolean value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setbit(key, offset, value);
        }
	}

	@Override
	public Boolean setbit(final byte[] key, final long offset, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setbit(key, offset, value);
        }
	}

	/**
	 * Returns the bit value at offset in the string value stored at key
	 *
	 * @param key
	 * @param offset
	 * @return
	 */
	@Override
	public Boolean getbit(final byte[] key, final long offset){
        try(Jedis jedis = pool.getResource()){
            return jedis.getbit(key, offset);
        }
	}

	@Override
	public Long setrange(final byte[] key, final long offset, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setrange(key, offset, value);
        }
	}

	@Override
	public byte[] getrange(final byte[] key, final long startOffset, final long endOffset){
        try(Jedis jedis = pool.getResource()){
            return jedis.getrange(key, startOffset, endOffset);
        }
	}

	@Override
	public Long publish(final byte[] channel, final byte[] message){
        try(Jedis jedis = pool.getResource()){
            return jedis.publish(channel, message);
        }
	}

    /**
     * Подписаться на прослушивание каналов.
     *
     * <p>Этот метод является альтернативой метода {@link #subscribe(BinaryJedisPubSub, byte[]...)},
     * и рекомендуется к использованию. Этот метод создает подписку с помощью обертки {@link BinaryJedisPubSubWrapper},
     * о преимуществах которой можете почитать в ее javadoc. Ключевая особенность заключается в том, что этот метод
     * не блокирует поток, что делает подписку легковесной.
     *
     * @param listener слушатель, который будет вызываться, когда будет приходить сообщение на указанные каналы.
     * @param channels имя каналов.
     * @return слушатель, переданный параметром {@code listener}. Он выступает индентификатором подписки,
     * с помощью слушателя можно отменить подписку методом {@link #unsubscribe(BinaryJedisPubSubListener)}.
     */
    public BinaryJedisPubSubListener subscribe(BinaryJedisPubSubListener listener, byte[]... channels){
        for (byte[] channel : channels) {
            binaryPubSubWrapper.subscribe(listener, channel);
        }
        return listener;
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
    public boolean unsubscribe(BinaryJedisPubSubListener listener) {
        return binaryPubSubWrapper.unsubscribe(listener);
    }

    /**
     * @deprecated не рекомендуется к использованию.
     * Есть альтернативный метод {@link #subscribe(BinaryJedisPubSubListener, byte[]...)},
     * который создает подписку с помощью обертки {@link BinaryJedisPubSubWrapper},  о преимуществах
     * которой можете почитать в ее javadoc.
     */
	@Deprecated
	@Override
	public void subscribe(BinaryJedisPubSub jedisPubSub, byte[]... channels){
        try(Jedis jedis = pool.getResource()){
            jedis.subscribe(jedisPubSub, channels);
        }
	}

	@Override
	public void psubscribe(BinaryJedisPubSub jedisPubSub, byte[]... patterns){
        try(Jedis jedis = pool.getResource()){
            jedis.psubscribe(jedisPubSub, patterns);
        }
	}

	@Override
	public Long bitcount(final byte[] key, final long start, final long end){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitcount(key, start, end);
        }
	}

	@Override
	public Long bitop(final BitOP op, final byte[] destKey, final byte[]... srcKeys){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitop(op, destKey, srcKeys);
        }
	}

	@Override
	public byte[] dump(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.dump(key);
        }
	}

	@Override
	public String restore(final byte[] key, final int ttl, final byte[] serializedValue){
        try(Jedis jedis = pool.getResource()){
            return jedis.restore(key, ttl, serializedValue);
        }
	}

	@Override
	public String restoreReplace(final byte[] key, final int ttl, final byte[] serializedValue){
        try(Jedis jedis = pool.getResource()){
            return jedis.restoreReplace(key, ttl, serializedValue);
        }
	}

	@Deprecated
	public Long pexpire(final byte[] key, final int milliseconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpire(key, milliseconds);
        }
	}

	@Override
	public Long pexpire(final byte[] key, final long milliseconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpire(key, milliseconds);
        }
	}

	@Override
	public Long pexpireAt(final byte[] key, final long millisecondsTimestamp){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpireAt(key, millisecondsTimestamp);
        }
	}

	@Override
	public Long pttl(final byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.pttl(key);
        }
	}

	/**
	 * PSETEX works exactly like {@link #setex(byte[], int, byte[])} with the sole difference that the
	 * expire time is specified in milliseconds instead of seconds. Time complexity: O(1)
	 *
	 * @param key
	 * @param milliseconds
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String psetex(final byte[] key, final long milliseconds, final byte[] value){
        try(Jedis jedis = pool.getResource()){
            return jedis.psetex(key, milliseconds, value);
        }
	}

	@Override
	public Long pfadd(final byte[] key, final byte[]... elements){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfadd(key, elements);
        }
	}

	@Override
	public long pfcount(byte[] key){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfcount(key);
        }
	}

	@Override
	public String pfmerge(final byte[] destkey, final byte[]... sourcekeys){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfmerge(destkey, sourcekeys);
        }
	}

	@Override
	public Long pfcount(final byte[]... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfcount(keys);
        }
	}

	@Override
	public ScanResult<Map.Entry<byte[], byte[]>> hscan(final byte[] key, final byte[] cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.hscan(key, cursor);
        }
	}

	@Override
	public ScanResult<Map.Entry<byte[], byte[]>> hscan(final byte[] key, final byte[] cursor,
	                                                   final ScanParams params){
		try(Jedis jedis = pool.getResource()){
			return jedis.hscan(key, cursor, params);
		}
	}

	@Override
	public ScanResult<byte[]> sscan(final byte[] key, final byte[] cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.sscan(key, cursor);
        }
	}

	@Override
	public ScanResult<byte[]> sscan(final byte[] key, final byte[] cursor, final ScanParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.sscan(key, cursor, params);
        }
	}

	@Override
	public ScanResult<Tuple> zscan(final byte[] key, final byte[] cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscan(key, cursor);
        }
	}

	@Override
	public ScanResult<Tuple> zscan(final byte[] key, final byte[] cursor, final ScanParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscan(key, cursor, params);
        }
	}

	@Override
	public Long geoadd(final byte[] key, final double longitude, final double latitude, final byte[] member){
        try(Jedis jedis = pool.getResource()){
            return jedis.geoadd(key, longitude, latitude, member);
        }
	}

	@Override
	public Long geoadd(final byte[] key, final Map<byte[], GeoCoordinate> memberCoordinateMap){
        try(Jedis jedis = pool.getResource()){
            return jedis.geoadd(key, memberCoordinateMap);
        }
	}

	@Override
	public Double geodist(final byte[] key, final byte[] member1, final byte[] member2){
        try(Jedis jedis = pool.getResource()){
            return jedis.geodist(key, member1, member2);
        }
	}

	@Override
	public Double geodist(final byte[] key, final byte[] member1, final byte[] member2, final GeoUnit unit){
        try(Jedis jedis = pool.getResource()){
            return jedis.geodist(key, member1, member2, unit);
        }
	}

	@Override
	public List<byte[]> geohash(final byte[] key, final byte[]... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.geohash(key, members);
        }
	}

	@Override
	public List<GeoCoordinate> geopos(final byte[] key, final byte[]... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.geopos(key, members);
        }
	}

	@Override
	public List<GeoRadiusResponse> georadius(final byte[] key, final double longitude, final double latitude,
	                                         final double radius, final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadius(key, longitude, latitude, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusReadonly(final byte[] key, final double longitude, final double latitude,
	                                                 final double radius, final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusReadonly(key, longitude, latitude, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadius(final byte[] key, final double longitude, final double latitude,
	                                         final double radius, final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadius(key, longitude, latitude, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusReadonly(final byte[] key, final double longitude, final double latitude,
	                                                 final double radius, final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusReadonly(key, longitude, latitude, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMember(final byte[] key, final byte[] member, final double radius,
	                                                 final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMember(key, member, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMemberReadonly(final byte[] key, final byte[] member, final double radius,
	                                                         final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMemberReadonly(key, member, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMember(final byte[] key, final byte[] member, final double radius,
	                                                 final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMember(key, member, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMemberReadonly(final byte[] key, final byte[] member, final double radius,
	                                                         final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMemberReadonly(key, member, radius, unit, param);
		}
	}

	@Override
	public List<Long> bitfield(final byte[] key, final byte[]... arguments){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitfield(key, arguments);
        }
	}

	@Override
	public Long hstrlen(final byte[] key, final byte[] field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hstrlen(key, field);
        }
	}

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String set(final String key, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value);
        }
	}

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 *
	 * @param key
	 * @param value
	 * @param nxxx  NX|XX, NX -- Only set the key if it does not already exist. XX -- Only set the key
	 *              if it already exist.
	 *              PX = milliseconds
	 * @param time  expire time in the units of <code>expx</code>
	 * @return Status code reply
	 */
	@Override
	public String set(final String key, final String value, final String nxxx, final String expx,
	                  final long time){
		try(Jedis jedis = pool.getResource()){
			return jedis.set(key, value, nxxx, expx, time);
		}
	}

	/**
	 * Set the string value as value of the key. The string can't be longer than 1073741824 bytes (1
	 * GB).
	 *
	 * @param key
	 * @param value PX = milliseconds
	 * @param time  expire time in the units of <code>expx</code>
	 * @return Status code reply
	 */
	@Override
	public String set(final String key, final String value, final String expx, final long time){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value, expx, time);
        }
	}

	/**
	 * Get the value of the specified key. If the key does not exist null is returned. If the value
	 * stored at key is not a string an error is returned because GET can only handle string values.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public String get(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.get(key);
        }
	}

	/**
	 * Test if the specified keys exist. The command returns the number of keys exist.
	 * Time complexity: O(N)
	 *
	 * @param keys
	 * @return Integer reply, specifically: an integer greater than 0 if one or more keys exist,
	 * 0 if none of the specified keys exist.
	 */
	@Override
	public Long exists(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.exists(keys);
        }
	}

	/**
	 * Test if the specified key exists. The command returns true if the key exists, otherwise false is
	 * returned. Note that even keys set with an empty string as value will return true. Time
	 * complexity: O(1)
	 *
	 * @param key
	 * @return Boolean reply, true if the key exists, otherwise false
	 */
	@Override
	public Boolean exists(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.exists(key);
        }
	}

	/**
	 * Remove the specified keys. If a given key does not exist no operation is performed for this
	 * key. The command returns the number of keys removed. Time complexity: O(1)
	 *
	 * @param keys
	 * @return Integer reply, specifically: an integer greater than 0 if one or more keys were removed
	 * 0 if none of the specified key existed
	 */
	@Override
	public Long del(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.del(keys);
        }
	}

	@Override
	public Long del(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.del(key);
        }
	}

	/**
	 * This command is very similar to DEL: it removes the specified keys. Just like DEL a key is
	 * ignored if it does not exist. However the command performs the actual memory reclaiming in a
	 * different thread, so it is not blocking, while DEL is. This is where the command name comes
	 * from: the command just unlinks the keys from the keyspace. The actual removal will happen later
	 * asynchronously.
	 * <p>
	 * Time complexity: O(1) for each key removed regardless of its size. Then the command does O(N)
	 * work in a different thread in order to reclaim memory, where N is the number of allocations the
	 * deleted objects where composed of.
	 *
	 * @param keys
	 * @return Integer reply: The number of keys that were unlinked
	 */
	@Override
	public Long unlink(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.unlink(keys);
        }
	}

	@Override
	public Long unlink(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.unlink(key);
        }
	}

	/**
	 * Return the type of the value stored at key in form of a string. The type can be one of "none",
	 * "string", "list", "set". "none" is returned if the key does not exist. Time complexity: O(1)
	 *
	 * @param key
	 * @return Status code reply, specifically: "none" if the key does not exist "string" if the key
	 * contains a String value "list" if the key contains a List value "set" if the key
	 * contains a Set value "zset" if the key contains a Sorted Set value "hash" if the key
	 * contains a Hash value
	 */
	@Override
	public String type(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.type(key);
        }
	}

	@Override
	public Set<String> keys(final String pattern){
        try(Jedis jedis = pool.getResource()){
            return jedis.keys(pattern);
        }
	}

	/**
	 * Return a randomly selected key from the currently selected DB.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @return Singe line reply, specifically the randomly selected key or an empty string is the
	 * database is empty
	 */
	@Override
	public String randomKey(){
        try(Jedis jedis = pool.getResource()){
            return jedis.randomKey();
        }
	}

	/**
	 * Atomically renames the key oldkey to newkey. If the source and destination name are the same an
	 * error is returned. If newkey already exists it is overwritten.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param oldkey
	 * @param newkey
	 * @return Status code repy
	 */
	@Override
	public String rename(final String oldkey, final String newkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.rename(oldkey, newkey);
        }
	}

	/**
	 * Rename oldkey into newkey but fails if the destination key newkey already exists.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param oldkey
	 * @param newkey
	 * @return Integer reply, specifically: 1 if the key was renamed 0 if the target key already exist
	 */
	@Override
	public Long renamenx(final String oldkey, final String newkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.renamenx(oldkey, newkey);
        }
	}

	/**
	 * Set a timeout on the specified key. After the timeout the key will be automatically deleted by
	 * the server. A key with an associated timeout is said to be volatile in Redis terminology.
	 * <p>
	 * Volatile keys are stored on disk like the other keys, the timeout is persistent too like all the
	 * other aspects of the dataset. Saving a dataset containing expires and stopping the server does
	 * not stop the flow of time as Redis stores on disk the time when the key will no longer be
	 * available as Unix time, and not the remaining seconds.
	 * <p>
	 * Since Redis 2.1.3 you can update the value of the timeout of a key already having an expire
	 * set. It is also possible to undo the expire at all turning the key into a normal key using the
	 * {@link #persist(String) PERSIST} command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param seconds
	 * @return Integer reply, specifically: 1: the timeout was set. 0: the timeout was not set since
	 * = 2.1.3 will happily update the timeout), or the key does not exist.
	 * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">ExpireCommand</a>
	 */
	@Override
	public Long expire(final String key, final int seconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.expire(key, seconds);
        }
	}

	/**
	 * EXPIREAT works exactly like {@link #expire(String, int) EXPIRE} but instead to get the number of
	 * seconds representing the Time To Live of the key as a second argument (that is a relative way
	 * of specifying the TTL), it takes an absolute one in the form of a UNIX timestamp (Number of
	 * seconds elapsed since 1 Gen 1970).
	 * <p>
	 * EXPIREAT was introduced in order to implement the Append Only File persistence mode so that
	 * EXPIRE commands are automatically translated into EXPIREAT commands for the append only file.
	 * Of course EXPIREAT can also used by programmers that need a way to simply specify that a given
	 * key should expire at a given time in the future.
	 * <p>
	 * Since Redis 2.1.3 you can update the value of the timeout of a key already having an expire
	 * set. It is also possible to undo the expire at all turning the key into a normal key using the
	 * {@link #persist(String) PERSIST} command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param unixTime
	 * @return Integer reply, specifically: 1: the timeout was set. 0: the timeout was not set since
	 * = 2.1.3 will happily update the timeout), or the key does not exist.
	 * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">ExpireCommand</a>
	 */
	@Override
	public Long expireAt(final String key, final long unixTime){
        try(Jedis jedis = pool.getResource()){
            return jedis.expireAt(key, unixTime);
        }
	}

	/**
	 * The TTL command returns the remaining time to live in seconds of a key that has an
	 * {@link #expire(String, int) EXPIRE} set. This introspection capability allows a Redis client to
	 * check how many seconds a given key will continue to be part of the dataset.
	 *
	 * @param key
	 * @return Integer reply, returns the remaining time to live in seconds of a key that has an
	 * EXPIRE. In Redis 2.6 or older, if the Key does not exists or does not have an
	 * associated expire, -1 is returned. In Redis 2.8 or newer, if the Key does not have an
	 * associated expire, -1 is returned or if the Key does not exists, -2 is returned.
	 */
	@Override
	public Long ttl(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.ttl(key);
        }
	}

	/**
	 * Alters the last access time of a key(s). A key is ignored if it does not exist.
	 * Time complexity: O(N) where N is the number of keys that will be touched.
	 *
	 * @param keys
	 * @return Integer reply: The number of keys that were touched.
	 */
	@Override
	public Long touch(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.touch(keys);
        }
	}

	@Override
	public Long touch(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.touch(key);
        }
	}

	/**
	 * Move the specified key from the currently selected DB to the specified destination DB. Note
	 * that this command returns 1 only if the key was successfully moved, and 0 if the target key was
	 * already there or if the source key was not found at all, so it is possible to use MOVE as a
	 * locking primitive.
	 *
	 * @param key
	 * @param dbIndex
	 * @return Integer reply, specifically: 1 if the key was moved 0 if the key was not moved because
	 * already present on the target DB or was not found in the current DB.
	 */
	@Override
	public Long move(final String key, final int dbIndex){
        try(Jedis jedis = pool.getResource()){
            return jedis.move(key, dbIndex);
        }
	}

	@Override
	public Long bitcount(String key){
		try(Jedis jedis = pool.getResource()){
			return jedis.bitcount(key);
		}
	}

	/**
	 * GETSET is an atomic set this value and return the old value command. Set key to the string
	 * value and return the old value stored at key. The string can't be longer than 1073741824 bytes
	 * (1 GB).
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Bulk reply
	 */
	@Override
	public String getSet(final String key, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.getSet(key, value);
        }
	}

	/**
	 * Get the values of all the specified keys. If one or more keys don't exist or is not of type
	 * String, a 'nil' value is returned instead of the value of the specified key, but the operation
	 * never fails.
	 * <p>
	 * Time complexity: O(1) for every key
	 *
	 * @param keys
	 * @return Multi bulk reply
	 */
	@Override
	public List<String> mget(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.mget(keys);
        }
	}

	/**
	 * SETNX works exactly like {@link #set(String, String) SET} with the only difference that if the
	 * key already exists no operation is performed. SETNX actually means "SET if Not eXists".
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param value
	 * @return Integer reply, specifically: 1 if the key was set 0 if the key was not set
	 */
	@Override
	public Long setnx(final String key, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setnx(key, value);
        }
	}

	/**
	 * The command is exactly equivalent to the following group of commands:
	 * {@link #set(String, String) SET} + {@link #expire(String, int) EXPIRE}. The operation is
	 * atomic.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param seconds
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String setex(final String key, final int seconds, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setex(key, seconds, value);
        }
	}

	/**
	 * Set the the respective keys to the respective values. MSET will replace old values with new
	 * values, while {@link #msetnx(String...) MSETNX} will not perform any operation at all even if
	 * just a single key already exists.
	 * <p>
	 * Because of this semantic MSETNX can be used in order to set different keys representing
	 * different fields of an unique logic object in a way that ensures that either all the fields or
	 * none at all are set.
	 * <p>
	 * Both MSET and MSETNX are atomic operations. This means that for instance if the keys A and B
	 * are modified, another client talking to Redis can either see the changes to both A and B at
	 * once, or no modification at all.
	 *
	 * @param keysvalues
	 * @return Status code reply Basically +OK as MSET can't fail
	 * @see #msetnx(String...)
	 */
	@Override
	public String mset(final String... keysvalues){
        try(Jedis jedis = pool.getResource()){
            return jedis.mset(keysvalues);
        }
	}

	/**
	 * Set the the respective keys to the respective values. {@link #mset(String...) MSET} will
	 * replace old values with new values, while MSETNX will not perform any operation at all even if
	 * just a single key already exists.
	 * <p>
	 * Because of this semantic MSETNX can be used in order to set different keys representing
	 * different fields of an unique logic object in a way that ensures that either all the fields or
	 * none at all are set.
	 * <p>
	 * Both MSET and MSETNX are atomic operations. This means that for instance if the keys A and B
	 * are modified, another client talking to Redis can either see the changes to both A and B at
	 * once, or no modification at all.
	 *
	 * @param keysvalues
	 * @return Integer reply, specifically: 1 if the all the keys were set 0 if no key was set (at
	 * least one key already existed)
	 * @see #mset(String...)
	 */
	@Override
	public Long msetnx(final String... keysvalues){
        try(Jedis jedis = pool.getResource()){
            return jedis.msetnx(keysvalues);
        }
	}

	/**
	 * IDECRBY work just like {@link #decr(String) INCR} but instead to decrement by 1 the decrement
	 * is integer.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param decrement
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(String)
	 * @see #decr(String)
	 * @see #incrBy(String, long)
	 */
	@Override
	public Long decrBy(final String key, final long decrement){
        try(Jedis jedis = pool.getResource()){
            return jedis.decrBy(key, decrement);
        }
	}

	/**
	 * Decrement the number stored at key by one. If the key does not exist or contains a value of a
	 * wrong type, set the key to the value of "0" before to perform the decrement operation.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(String)
	 * @see #incrBy(String, long)
	 * @see #decrBy(String, long)
	 */
	@Override
	public Long decr(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.decr(key);
        }
	}

	/**
	 * INCRBY work just like {@link #incr(String) INCR} but instead to increment by 1 the increment is
	 * integer.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param increment
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incr(String)
	 * @see #decr(String)
	 * @see #decrBy(String, long)
	 */
	@Override
	public Long incrBy(final String key, final long increment){
        try(Jedis jedis = pool.getResource()){
            return jedis.incrBy(key, increment);
        }
	}

	/**
	 * INCRBYFLOAT
	 * <p>
	 * INCRBYFLOAT commands are limited to double precision floating point values.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "double" types.
	 * Simply the string stored at the key is parsed as a base double precision floating point value,
	 * incremented, and then converted back as a string. There is no DECRYBYFLOAT but providing a
	 * negative value will work as expected.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param increment
	 * @return Double reply, this commands will reply with the new value of key after the increment.
	 */
	@Override
	public Double incrByFloat(final String key, final double increment){
        try(Jedis jedis = pool.getResource()){
            return jedis.incrByFloat(key, increment);
        }
	}

	/**
	 * Increment the number stored at key by one. If the key does not exist or contains a value of a
	 * wrong type, set the key to the value of "0" before to perform the increment operation.
	 * <p>
	 * INCR commands are limited to 64 bit signed integers.
	 * <p>
	 * Note: this is actually a string operation, that is, in Redis there are not "integer" types.
	 * Simply the string stored at the key is parsed as a base 10 64 bit signed integer, incremented,
	 * and then converted back as a string.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, this commands will reply with the new value of key after the increment.
	 * @see #incrBy(String, long)
	 * @see #decr(String)
	 * @see #decrBy(String, long)
	 */
	@Override
	public Long incr(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.incr(key);
        }
	}

	/**
	 * If the key already exists and is a string, this command appends the provided value at the end
	 * of the string. If the key does not exist it is created and set as an empty string, so APPEND
	 * will be very similar to SET in this special case.
	 * <p>
	 * Time complexity: O(1). The amortized time complexity is O(1) assuming the appended value is
	 * small and the already present value is of any size, since the dynamic string library used by
	 * Redis will double the free space available on every reallocation.
	 *
	 * @param key
	 * @param value
	 * @return Integer reply, specifically the total length of the string after the append operation.
	 */
	@Override
	public Long append(final String key, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.append(key, value);
        }
	}

	/**
	 * Return a subset of the string from offset start to offset end (both offsets are inclusive).
	 * Negative offsets can be used in order to provide an offset starting from the end of the string.
	 * So -1 means the last char, -2 the penultimate and so forth.
	 * <p>
	 * The function handles out of range requests without raising an error, but just limiting the
	 * resulting range to the actual length of the string.
	 * <p>
	 * Time complexity: O(start+n) (with start being the start index and n the total length of the
	 * requested range). Note that the lookup part of this command is O(1) so for small strings this
	 * is actually an O(1) command.
	 *
	 * @param key
	 * @param start
	 * @param end
	 * @return Bulk reply
	 */
	@Override
	public String substr(final String key, final int start, final int end){
        try(Jedis jedis = pool.getResource()){
            return jedis.substr(key, start, end);
        }
	}

	/**
	 * Set the specified hash field to the specified value.
	 * <p>
	 * If key does not exist, a new key holding a hash is created.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return If the field already exists, and the HSET just produced an update of the value, 0 is
	 * returned, otherwise if a new field is created 1 is returned.
	 */
	@Override
	public Long hset(final String key, final String field, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hset(key, field, value);
        }
	}

	@Override
	public Long hset(final String key, final Map<String, String> hash){
        try(Jedis jedis = pool.getResource()){
            return jedis.hset(key, hash);
        }
	}

	/**
	 * If key holds a hash, retrieve the value associated to the specified field.
	 * <p>
	 * If the field is not found or the key does not exist, a special 'nil' value is returned.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @return Bulk reply
	 */
	@Override
	public String hget(final String key, final String field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hget(key, field);
        }
	}

	/**
	 * Set the specified hash field to the specified value if the field not exists. <b>Time
	 * complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return If the field already exists, 0 is returned, otherwise if a new field is created 1 is
	 * returned.
	 */
	@Override
	public Long hsetnx(final String key, final String field, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hsetnx(key, field, value);
        }
	}

	/**
	 * Set the respective fields to the respective values. HMSET replaces old values with new values.
	 * <p>
	 * If key does not exist, a new key holding a hash is created.
	 * <p>
	 * <b>Time complexity:</b> O(N) (with N being the number of fields)
	 *
	 * @param key
	 * @param hash
	 * @return Return OK or Exception if hash is empty
	 */
	@Override
	public String hmset(final String key, final Map<String, String> hash){
        try(Jedis jedis = pool.getResource()){
            return jedis.hmset(key, hash);
        }
	}

	/**
	 * Retrieve the values associated to the specified fields.
	 * <p>
	 * If some of the specified fields do not exist, nil values are returned. Non existing keys are
	 * considered like empty hashes.
	 * <p>
	 * <b>Time complexity:</b> O(N) (with N being the number of fields)
	 *
	 * @param key
	 * @param fields
	 * @return Multi Bulk Reply specifically a list of all the values associated with the specified
	 * fields, in the same order of the request.
	 */
	@Override
	public List<String> hmget(final String key, final String... fields){
        try(Jedis jedis = pool.getResource()){
            return jedis.hmget(key, fields);
        }
	}

	/**
	 * Increment the number stored at field in the hash at key by value. If key does not exist, a new
	 * key holding a hash is created. If field does not exist or holds a string, the value is set to 0
	 * before applying the operation. Since the value argument is signed you can use this command to
	 * perform both increments and decrements.
	 * <p>
	 * The range of values supported by HINCRBY is limited to 64 bit signed integers.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return Integer reply The new value at field after the increment operation.
	 */
	@Override
	public Long hincrBy(final String key, final String field, final long value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hincrBy(key, field, value);
        }
	}

	/**
	 * Increment the number stored at field in the hash at key by a double precision floating point
	 * value. If key does not exist, a new key holding a hash is created. If field does not exist or
	 * holds a string, the value is set to 0 before applying the operation. Since the value argument
	 * is signed you can use this command to perform both increments and decrements.
	 * <p>
	 * The range of values supported by HINCRBYFLOAT is limited to double precision floating point
	 * values.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @param value
	 * @return Double precision floating point reply The new value at field after the increment
	 * operation.
	 */
	@Override
	public Double hincrByFloat(final String key, final String field, final double value){
        try(Jedis jedis = pool.getResource()){
            return jedis.hincrByFloat(key, field, value);
        }
	}

	/**
	 * Test for existence of a specified field in a hash. <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param field
	 * @return Return true if the hash stored at key contains the specified field. Return false if the key is
	 * not found or the field is not present.
	 */
	@Override
	public Boolean hexists(final String key, final String field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hexists(key, field);
        }
	}

	/**
	 * Remove the specified field from an hash stored at key.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param fields
	 * @return If the field was present in the hash it is deleted and 1 is returned, otherwise 0 is
	 * returned and no operation is performed.
	 */
	@Override
	public Long hdel(final String key, final String... fields){
        try(Jedis jedis = pool.getResource()){
            return jedis.hdel(key, fields);
        }
	}

	/**
	 * Return the number of items in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @return The number of entries (fields) contained in the hash stored at key. If the specified
	 * key does not exist, 0 is returned assuming an empty hash.
	 */
	@Override
	public Long hlen(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hlen(key);
        }
	}

	/**
	 * Return all the fields in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(N), where N is the total number of entries
	 *
	 * @param key
	 * @return All the fields names contained into a hash.
	 */
	@Override
	public Set<String> hkeys(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hkeys(key);
        }
	}

	/**
	 * Return all the values in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(N), where N is the total number of entries
	 *
	 * @param key
	 * @return All the fields values contained into a hash.
	 */
	@Override
	public List<String> hvals(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hvals(key);
        }
	}

	/**
	 * Return all the fields and associated values in a hash.
	 * <p>
	 * <b>Time complexity:</b> O(N), where N is the total number of entries
	 *
	 * @param key
	 * @return All the fields and values contained into a hash.
	 */
	@Override
	public Map<String, String> hgetAll(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.hgetAll(key);
        }
	}

	/**
	 * Add the string value to the head (LPUSH) or tail (RPUSH) of the list stored at key. If the key
	 * does not exist an empty list is created just before the append operation. If the key exists but
	 * is not a List an error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param strings
	 * @return Integer reply, specifically, the number of elements inside the list after the push
	 * operation.
	 */
	@Override
	public Long rpush(final String key, final String... strings){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpush(key, strings);
        }
	}

	/**
	 * Add the string value to the head (LPUSH) or tail (RPUSH) of the list stored at key. If the key
	 * does not exist an empty list is created just before the append operation. If the key exists but
	 * is not a List an error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @param strings
	 * @return Integer reply, specifically, the number of elements inside the list after the push
	 * operation.
	 */
	@Override
	public Long lpush(final String key, final String... strings){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpush(key, strings);
        }
	}

	/**
	 * Return the length of the list stored at the specified key. If the key does not exist zero is
	 * returned (the same behaviour as for empty lists). If the value stored at key is not a list an
	 * error is returned.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return The length of the list.
	 */
	@Override
	public Long llen(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.llen(key);
        }
	}

	/**
	 * Return the specified elements of the list stored at the specified key. Start and end are
	 * zero-based indexes. 0 is the first element of the list (the list head), 1 the next element and
	 * so on.
	 * <p>
	 * For example LRANGE foobar 0 2 will return the first three elements of the list.
	 * <p>
	 * start and end can also be negative numbers indicating offsets from the end of the list. For
	 * example -1 is the last element of the list, -2 the penultimate element and so on.
	 * <p>
	 * <b>Consistency with range functions in various programming languages</b>
	 * <p>
	 * Note that if you have a list of numbers from 0 to 100, LRANGE 0 10 will return 11 elements,
	 * that is, rightmost item is included. This may or may not be consistent with behavior of
	 * range-related functions in your programming language of choice (think Ruby's Range.new,
	 * Array#slice or Python's range() function).
	 * <p>
	 * LRANGE behavior is consistent with one of Tcl.
	 * <p>
	 * <b>Out-of-range indexes</b>
	 * <p>
	 * Indexes out of range will not produce an error: if start is over the end of the list, or start
	 * end, an empty list is returned. If end is over the end of the list Redis will threat it
	 * just like the last element of the list.
	 * <p>
	 * Time complexity: O(start+n) (with n being the length of the range and start being the start
	 * offset)
	 *
	 * @param key
	 * @param start
	 * @param stop
	 * @return Multi bulk reply, specifically a list of elements in the specified range.
	 */
	@Override
	public List<String> lrange(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.lrange(key, start, stop);
        }
	}

	/**
	 * Trim an existing list so that it will contain only the specified range of elements specified.
	 * Start and end are zero-based indexes. 0 is the first element of the list (the list head), 1 the
	 * next element and so on.
	 * <p>
	 * For example LTRIM foobar 0 2 will modify the list stored at foobar key so that only the first
	 * three elements of the list will remain.
	 * <p>
	 * start and end can also be negative numbers indicating offsets from the end of the list. For
	 * example -1 is the last element of the list, -2 the penultimate element and so on.
	 * <p>
	 * Indexes out of range will not produce an error: if start is over the end of the list, or start
	 * end, an empty list is left as value. If end over the end of the list Redis will threat it
	 * just like the last element of the list.
	 * <p>
	 * Hint: the obvious use of LTRIM is together with LPUSH/RPUSH. For example:
	 * <p>
	 * }
	 * <p>
	 * The above two commands will push elements in the list taking care that the list will not grow
	 * without limits. This is very useful when using Redis to store logs for example. It is important
	 * to note that when used in this way LTRIM is an O(1) operation because in the average case just
	 * one element is removed from the tail of the list.
	 * <p>
	 * Time complexity: O(n) (with n being len of list - len of range)
	 *
	 * @param key
	 * @param start
	 * @param stop
	 * @return Status code reply
	 */
	@Override
	public String ltrim(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.ltrim(key, start, stop);
        }
	}

	/**
	 * Return the specified element of the list stored at the specified key. 0 is the first element, 1
	 * the second and so on. Negative indexes are supported, for example -1 is the last element, -2
	 * the penultimate and so on.
	 * <p>
	 * If the value stored at key is not of list type an error is returned. If the index is out of
	 * range a 'nil' reply is returned.
	 * <p>
	 * Note that even if the average time complexity is O(n) asking for the first or the last element
	 * of the list is O(1).
	 * <p>
	 * Time complexity: O(n) (with n being the length of the list)
	 *
	 * @param key
	 * @param index
	 * @return Bulk reply, specifically the requested element
	 */
	@Override
	public String lindex(final String key, final long index){
        try(Jedis jedis = pool.getResource()){
            return jedis.lindex(key, index);
        }
	}

	/**
	 * Set a new value as the element at index position of the List at key.
	 * <p>
	 * Out of range indexes will generate an error.
	 * <p>
	 * Similarly to other list commands accepting indexes, the index can be negative to access
	 * elements starting from the end of the list. So -1 is the last element, -2 is the penultimate,
	 * and so forth.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(N) (with N being the length of the list), setting the first or last elements of the list is
	 * O(1).
	 *
	 * @param key
	 * @param index
	 * @param value
	 * @return Status code reply
	 * @see #lindex(String, long)
	 */
	@Override
	public String lset(final String key, final long index, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.lset(key, index, value);
        }
	}

	/**
	 * Remove the first count occurrences of the value element from the list. If count is zero all the
	 * elements are removed. If count is negative elements are removed from tail to head, instead to
	 * go from head to tail that is the normal behaviour. So for example LREM with count -2 and hello
	 * as value to remove against the list (a,b,c,hello,x,hello,hello) will leave the list
	 * (a,b,c,hello,x). The number of removed elements is returned as an integer, see below for more
	 * information about the returned value. Note that non existing keys are considered like empty
	 * lists by LREM, so LREM against non existing keys will always return 0.
	 * <p>
	 * Time complexity: O(N) (with N being the length of the list)
	 *
	 * @param key
	 * @param count
	 * @param value
	 * @return Integer Reply, specifically: The number of removed elements if the operation succeeded
	 */
	@Override
	public Long lrem(final String key, final long count, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.lrem(key, count, value);
        }
	}

	/**
	 * Atomically return and remove the first (LPOP) or last (RPOP) element of the list. For example
	 * if the list contains the elements "a","b","c" LPOP will return "a" and the list will become
	 * "b","c".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned.
	 *
	 * @param key
	 * @return Bulk reply
	 * @see #rpop(String)
	 */
	@Override
	public String lpop(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpop(key);
        }
	}

	/**
	 * Atomically return and remove the first (LPOP) or last (RPOP) element of the list. For example
	 * if the list contains the elements "a","b","c" RPOP will return "c" and the list will become
	 * "a","b".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned.
	 *
	 * @param key
	 * @return Bulk reply
	 * @see #lpop(String)
	 */
	@Override
	public String rpop(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpop(key);
        }
	}

	/**
	 * Atomically return and remove the last (tail) element of the srckey list, and push the element
	 * as the first (head) element of the dstkey list. For example if the source list contains the
	 * elements "a","b","c" and the destination list contains the elements "foo","bar" after an
	 * RPOPLPUSH command the content of the two lists will be "a","b" and "c","foo","bar".
	 * <p>
	 * If the key does not exist or the list is already empty the special value 'nil' is returned. If
	 * the srckey and dstkey are the same the operation is equivalent to removing the last element
	 * from the list and pushing it as first element of the list, so it's a "list rotation" command.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param srckey
	 * @param dstkey
	 * @return Bulk reply
	 */
	@Override
	public String rpoplpush(final String srckey, final String dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpoplpush(srckey, dstkey);
        }
	}

	/**
	 * Add the specified member to the set value stored at key. If member is already a member of the
	 * set no operation is performed. If key does not exist a new set with the specified member as
	 * sole member is created. If the key exists but does not hold a set value an error is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @param members
	 * @return Integer reply, specifically: 1 if the new element was added 0 if the element was
	 * already a member of the set
	 */
	@Override
	public Long sadd(final String key, final String... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.sadd(key, members);
        }
	}

	/**
	 * Return all the members (elements) of the set value stored at key. This is just syntax glue for
	 * {@link #sinter(String...) SINTER}.
	 * <p>
	 * Time complexity O(N)
	 *
	 * @param key
	 * @return Multi bulk reply
	 */
	@Override
	public Set<String> smembers(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.smembers(key);
        }
	}

	/**
	 * Remove the specified member from the set value stored at key. If member was not a member of the
	 * set no operation is performed. If key does not hold a set value an error is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @param members
	 * @return Integer reply, specifically: 1 if the new element was removed 0 if the new element was
	 * not a member of the set
	 */
	@Override
	public Long srem(final String key, final String... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.srem(key, members);
        }
	}

	/**
	 * Remove a random element from a Set returning it as return value. If the Set is empty or the key
	 * does not exist, a nil object is returned.
	 * <p>
	 * The {@link #srandmember(String)} command does a similar work but the returned element is not
	 * removed from the Set.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public String spop(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.spop(key);
        }
	}

	@Override
	public Set<String> spop(final String key, final long count){
        try(Jedis jedis = pool.getResource()){
            return jedis.spop(key, count);
        }
	}

	/**
	 * Move the specified member from the set at srckey to the set at dstkey. This operation is
	 * atomic, in every given moment the element will appear to be in the source or destination set
	 * for accessing clients.
	 * <p>
	 * If the source set does not exist or does not contain the specified element no operation is
	 * performed and zero is returned, otherwise the element is removed from the source set and added
	 * to the destination set. On success one is returned, even if the element was already present in
	 * the destination set.
	 * <p>
	 * An error is raised if the source or destination keys contain a non Set value.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param srckey
	 * @param dstkey
	 * @param member
	 * @return Integer reply, specifically: 1 if the element was moved 0 if the element was not found
	 * on the first set and no operation was performed
	 */
	@Override
	public Long smove(final String srckey, final String dstkey, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.smove(srckey, dstkey, member);
        }
	}

	/**
	 * Return the set cardinality (number of elements). If the key does not exist 0 is returned, like
	 * for empty sets.
	 *
	 * @param key
	 * @return Integer reply, specifically: the cardinality (number of elements) of the set as an
	 * integer.
	 */
	@Override
	public Long scard(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.scard(key);
        }
	}

	/**
	 * Return true if member is a member of the set stored at key, otherwise false is returned.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @param member
	 * @return Boolean reply, specifically: true if the element is a member of the set false if the element
	 * is not a member of the set OR if the key does not exist
	 */
	@Override
	public Boolean sismember(final String key, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.sismember(key, member);
        }
	}

	/**
	 * Return the members of a set resulting from the intersection of all the sets hold at the
	 * specified keys. Like in {@link #lrange(String, long, long) LRANGE} the result is sent to the
	 * client as a multi-bulk reply (see the protocol specification for more information). If just a
	 * single key is specified, then this command produces the same result as
	 * {@link #smembers(String) SMEMBERS}. Actually SMEMBERS is just syntax sugar for SINTER.
	 * <p>
	 * Non existing keys are considered like empty sets, so if one of the keys is missing an empty set
	 * is returned (since the intersection with an empty set always is an empty set).
	 * <p>
	 * Time complexity O(N*M) worst case where N is the cardinality of the smallest set and M the
	 * number of sets
	 *
	 * @param keys
	 * @return Multi bulk reply, specifically the list of common elements.
	 */
	@Override
	public Set<String> sinter(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sinter(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sinter(String...) SINTER} but instead of being returned
	 * the resulting set is stored as dstkey.
	 * <p>
	 * Time complexity O(N*M) worst case where N is the cardinality of the smallest set and M the
	 * number of sets
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sinterstore(final String dstkey, final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sinterstore(dstkey, keys);
        }
	}

	/**
	 * Return the members of a set resulting from the union of all the sets hold at the specified
	 * keys. Like in {@link #lrange(String, long, long) LRANGE} the result is sent to the client as a
	 * multi-bulk reply (see the protocol specification for more information). If just a single key is
	 * specified, then this command produces the same result as {@link #smembers(String) SMEMBERS}.
	 * <p>
	 * Non existing keys are considered like empty sets.
	 * <p>
	 * Time complexity O(N) where N is the total number of elements in all the provided sets
	 *
	 * @param keys
	 * @return Multi bulk reply, specifically the list of common elements.
	 */
	@Override
	public Set<String> sunion(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sunion(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sunion(String...) SUNION} but instead of being returned
	 * the resulting set is stored as dstkey. Any existing value in dstkey will be over-written.
	 * <p>
	 * Time complexity O(N) where N is the total number of elements in all the provided sets
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sunionstore(final String dstkey, final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sunionstore(dstkey, keys);
        }
	}

	/**
	 * Return the difference between the Set stored at key1 and all the Sets key2, ..., keyN
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * key1 = [x, a, b, c]
	 * key2 = [c]
	 * key3 = [a, d]
	 * [x, b]
	 * </pre>
	 * <p>
	 * Non existing keys are considered like empty sets.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(N) with N being the total number of elements of all the sets
	 *
	 * @param keys
	 * @return Return the members of a set resulting from the difference between the first set
	 * provided and all the successive sets.
	 */
	@Override
	public Set<String> sdiff(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sdiff(keys);
        }
	}

	/**
	 * This command works exactly like {@link #sdiff(String...) SDIFF} but instead of being returned
	 * the resulting set is stored in dstkey.
	 *
	 * @param dstkey
	 * @param keys
	 * @return Status code reply
	 */
	@Override
	public Long sdiffstore(final String dstkey, final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.sdiffstore(dstkey, keys);
        }
	}

	/**
	 * Return a random element from a Set, without removing the element. If the Set is empty or the
	 * key does not exist, a nil object is returned.
	 * <p>
	 * The SPOP command does a similar work but the returned element is popped (removed) from the Set.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return Bulk reply
	 */
	@Override
	public String srandmember(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.srandmember(key);
        }
	}

	@Override
	public List<String> srandmember(final String key, final int count){
        try(Jedis jedis = pool.getResource()){
            return jedis.srandmember(key, count);
        }
	}

	/**
	 * Add the specified member having the specified score to the sorted set stored at key. If member
	 * is already a member of the sorted set the score is updated, and the element reinserted in the
	 * right position to ensure sorting. If key does not exist a new sorted set with the specified
	 * member as sole member is created. If the key exists but does not hold a sorted set value an
	 * error is returned.
	 * <p>
	 * The score value can be the string representation of a double precision floating point number.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param score
	 * @param member
	 * @return Integer reply, specifically: 1 if the new element was added 0 if the element was
	 * already a member of the sorted set and the score was updated
	 */
	@Override
	public Long zadd(final String key, final double score, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, score, member);
        }
	}

	@Override
	public Long zadd(final String key, final double score, final String member,
	                 final ZAddParams params){
		try(Jedis jedis = pool.getResource()){
			return jedis.zadd(key, score, member, params);
		}
	}

	@Override
	public Long zadd(String key, Map<String, Double> scoreMembers){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, scoreMembers);
        }
	}

	@Override
	public Long zadd(final String key, final Map<String, Double> scoreMembers, final ZAddParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zadd(key, scoreMembers, params);
        }
	}

	@Override
	public Set<String> zrange(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrange(key, start, stop);
        }
	}

	/**
	 * Remove the specified member from the sorted set value stored at key. If member was not a member
	 * of the set no operation is performed. If key does not not hold a set value an error is
	 * returned.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param members
	 * @return Integer reply, specifically: 1 if the new element was removed 0 if the new element was
	 * not a member of the set
	 */
	@Override
	public Long zrem(final String key, final String... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrem(key, members);
        }
	}

	/**
	 * If member already exists in the sorted set adds the increment to its score and updates the
	 * position of the element in the sorted set accordingly. If member does not already exist in the
	 * sorted set it is added with increment as score (that is, like if the previous score was
	 * virtually zero). If key does not exist a new sorted set with the specified member as sole
	 * member is created. If the key exists but does not hold a sorted set value an error is returned.
	 * <p>
	 * The score value can be the string representation of a double precision floating point number.
	 * It's possible to provide a negative value to perform a decrement.
	 * <p>
	 * For an introduction to sorted sets check the Introduction to Redis data types page.
	 * <p>
	 * Time complexity O(log(N)) with N being the number of elements in the sorted set
	 *
	 * @param key
	 * @param increment
	 * @param member
	 * @return The new score
	 */
	@Override
	public Double zincrby(final String key, final double increment, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zincrby(key, increment, member);
        }
	}

	@Override
	public Double zincrby(final String key, final double increment, final String member, final ZIncrByParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zincrby(key, increment, member, params);
        }
	}

	/**
	 * Return the rank (or index) of member in the sorted set at key, with scores being ordered from
	 * low to high.
	 * <p>
	 * When the given member does not exist in the sorted set, the special value 'nil' is returned.
	 * The returned rank (or index) of the member is 0-based for both commands.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))
	 *
	 * @param key
	 * @param member
	 * @return Integer reply or a nil bulk reply, specifically: the rank of the element as an integer
	 * reply if the element exists. A nil bulk reply if there is no such element.
	 * @see #zrevrank(String, String)
	 */
	@Override
	public Long zrank(final String key, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrank(key, member);
        }
	}

	/**
	 * Return the rank (or index) of member in the sorted set at key, with scores being ordered from
	 * high to low.
	 * <p>
	 * When the given member does not exist in the sorted set, the special value 'nil' is returned.
	 * The returned rank (or index) of the member is 0-based for both commands.
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))
	 *
	 * @param key
	 * @param member
	 * @return Integer reply or a nil bulk reply, specifically: the rank of the element as an integer
	 * reply if the element exists. A nil bulk reply if there is no such element.
	 * @see #zrank(String, String)
	 */
	@Override
	public Long zrevrank(final String key, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrank(key, member);
        }
	}

	@Override
	public Set<String> zrevrange(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrange(key, start, stop);
        }
	}

	@Override
	public Set<Tuple> zrangeWithScores(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeWithScores(key, start, stop);
        }
	}

	@Override
	public Set<Tuple> zrevrangeWithScores(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeWithScores(key, start, stop);
        }
	}

	/**
	 * Return the sorted set cardinality (number of elements). If the key does not exist 0 is
	 * returned, like for empty sorted sets.
	 * <p>
	 * Time complexity O(1)
	 *
	 * @param key
	 * @return the cardinality (number of elements) of the set as an integer.
	 */
	@Override
	public Long zcard(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcard(key);
        }
	}

	/**
	 * Return the score of the specified element of the sorted set at key. If the specified element
	 * does not exist in the sorted set, or the key does not exist at all, a special 'nil' value is
	 * returned.
	 * <p>
	 * <b>Time complexity:</b> O(1)
	 *
	 * @param key
	 * @param member
	 * @return the score
	 */
	@Override
	public Double zscore(final String key, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscore(key, member);
        }
	}

	@Override
	public String watch(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.watch(keys);
        }
	}

	/**
	 * Sort a Set or a List.
	 * <p>
	 * Sort the elements contained in the List, Set, or Sorted Set value at key. By default sorting is
	 * numeric with elements being compared as double precision floating point numbers. This is the
	 * simplest form of SORT.
	 *
	 * @param key
	 * @return Assuming the Set/List at key contains a list of numbers, the return value will be the
	 * list of numbers ordered from the smallest to the biggest number.
	 * @see #sort(String, String)
	 * @see #sort(String, SortingParams)
	 * @see #sort(String, SortingParams, String)
	 */
	@Override
	public List<String> sort(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key);
        }
	}

	/**
	 * Sort a Set or a List accordingly to the specified parameters.
	 * <p>
	 * <b>examples:</b>
	 * <p>
	 * Given are the following sets and key/values:
	 *
	 * <pre>
	 * x = [1, 2, 3]
	 * y = [a, b, c]
	 *
	 * k1 = z
	 * k2 = y
	 * k3 = x
	 *
	 * w1 = 9
	 * w2 = 8
	 * w3 = 7
	 * </pre>
	 * <p>
	 * Sort Order:
	 *
	 * <pre>
	 * sort(x) or sort(x, sp.asc())
	 * [1, 2, 3]
	 *
	 * sort(x, sp.desc())
	 * [3, 2, 1]
	 *
	 * sort(y)
	 * [c, a, b]
	 *
	 * sort(y, sp.alpha())
	 * [a, b, c]
	 *
	 * sort(y, sp.alpha().desc())
	 * [c, a, b]
	 * </pre>
	 * <p>
	 * Limit (e.g. for Pagination):
	 *
	 * <pre>
	 * sort(x, sp.limit(0, 2))
	 * [1, 2]
	 *
	 * sort(y, sp.alpha().desc().limit(1, 2))
	 * [b, a]
	 * </pre>
	 * <p>
	 * Sorting by external keys:
	 *
	 * <pre>
	 * sort(x, sb.by(w*))
	 * [3, 2, 1]
	 *
	 * sort(x, sb.by(w*).desc())
	 * [1, 2, 3]
	 * </pre>
	 * <p>
	 * Getting external keys:
	 *
	 * <pre>
	 * sort(x, sp.by(w*).get(k*))
	 * [x, y, z]
	 *
	 * sort(x, sp.by(w*).get(#).get(k*))
	 * [3, x, 2, y, 1, z]
	 * </pre>
	 *
	 * @param key
	 * @param sortingParameters
	 * @return a list of sorted elements.
	 * @see #sort(String)
	 * @see #sort(String, SortingParams, String)
	 */
	@Override
	public List<String> sort(final String key, final SortingParams sortingParameters){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, sortingParameters);
        }
	}

	/**
	 * BLPOP (and BRPOP) is a blocking list pop primitive. You can see this commands as blocking
	 * versions of LPOP and RPOP able to block if the specified keys don't exist or contain empty
	 * lists.
	 * <p>
	 * The following is a description of the exact semantic. We describe BLPOP but the two commands
	 * are identical, the only difference is that BLPOP pops the element from the left (head) of the
	 * list, and BRPOP pops from the right (tail).
	 * <p>
	 * <b>Non blocking behavior</b>
	 * <p>
	 * When BLPOP is called, if at least one of the specified keys contain a non empty list, an
	 * element is popped from the head of the list and returned to the caller together with the name
	 * of the key (BLPOP returns a two elements array, the first element is the key, the second the
	 * popped value).
	 * <p>
	 * Keys are scanned from left to right, so for instance if you issue BLPOP list1 list2 list3 0
	 * against a dataset where list1 does not exist but list2 and list3 contain non empty lists, BLPOP
	 * guarantees to return an element from the list stored at list2 (since it is the first non empty
	 * list starting from the left).
	 * <p>
	 * <b>Blocking behavior</b>
	 * <p>
	 * If none of the specified keys exist or contain non empty lists, BLPOP blocks until some other
	 * client performs a LPUSH or an RPUSH operation against one of the lists.
	 * <p>
	 * Once new data is present on one of the lists, the client finally returns with the name of the
	 * key unblocking it and the popped value.
	 * <p>
	 * When blocking, if a non-zero timeout is specified, the client will unblock returning a nil
	 * special value if the specified amount of seconds passed without a push operation against at
	 * least one of the specified keys.
	 * <p>
	 * The timeout argument is interpreted as an integer value. A timeout of zero means instead to
	 * block forever.
	 * <p>
	 * <b>Multiple clients blocking for the same keys</b>
	 * <p>
	 * Multiple clients can block for the same key. They are put into a queue, so the first to be
	 * served will be the one that started to wait earlier, in a first-blpopping first-served fashion.
	 * <p>
	 * <b>blocking POP inside a MULTI/EXEC transaction</b>
	 * <p>
	 * BLPOP and BRPOP can be used with pipelining (sending multiple commands and reading the replies
	 * in batch), but it does not make sense to use BLPOP or BRPOP inside a MULTI/EXEC block (a Redis
	 * transaction).
	 * <p>
	 * The behavior of BLPOP inside MULTI/EXEC when the list is empty is to return a multi-bulk nil
	 * reply, exactly what happens when the timeout is reached. If you like science fiction, think at
	 * it like if inside MULTI/EXEC the time will flow at infinite speed :)
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param timeout
	 * @param keys
	 * @return BLPOP returns a two-elements array via a multi bulk reply in order to return both the
	 * unblocking key and the popped value.
	 * <p>
	 * When a non-zero timeout is specified, and the BLPOP operation timed out, the return
	 * value is a nil multi bulk reply. Most client values will return false or nil
	 * accordingly to the programming language used.
	 * @see #brpop(int, String...)
	 */
	@Override
	public List<String> blpop(final int timeout, final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(timeout, keys);
        }
	}


	@Override
	public List<String> blpop(final String... args){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(args);
        }
	}

	@Override
	public List<String> brpop(final String... args){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(args);
        }
	}

	/**
	 * @deprecated unusable command, this command will be removed in 3.0.0.
	 */
	@Override
	@Deprecated
	public List<String> blpop(String arg){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(arg);
        }
	}

	/**
	 * @deprecated unusable command, this command will be removed in 3.0.0.
	 */
	@Override
	@Deprecated
	public List<String> brpop(String arg){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(arg);
        }
	}

	/**
	 * Sort a Set or a List accordingly to the specified parameters and store the result at dstkey.
	 *
	 * @param key
	 * @param sortingParameters
	 * @param dstkey
	 * @return The number of elements of the list at dstkey.
	 * @see #sort(String, SortingParams)
	 * @see #sort(String)
	 * @see #sort(String, String)
	 */
	@Override
	public Long sort(final String key, final SortingParams sortingParameters, final String dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, sortingParameters, dstkey);
        }
	}

	/**
	 * Sort a Set or a List and Store the Result at dstkey.
	 * <p>
	 * Sort the elements contained in the List, Set, or Sorted Set value at key and store the result
	 * at dstkey. By default sorting is numeric with elements being compared as double precision
	 * floating point numbers. This is the simplest form of SORT.
	 *
	 * @param key
	 * @param dstkey
	 * @return The number of elements of the list at dstkey.
	 * @see #sort(String)
	 * @see #sort(String, SortingParams)
	 * @see #sort(String, SortingParams, String)
	 */
	@Override
	public Long sort(final String key, final String dstkey){
        try(Jedis jedis = pool.getResource()){
            return jedis.sort(key, dstkey);
        }
	}

	/**
	 * BLPOP (and BRPOP) is a blocking list pop primitive. You can see this commands as blocking
	 * versions of LPOP and RPOP able to block if the specified keys don't exist or contain empty
	 * lists.
	 * <p>
	 * The following is a description of the exact semantic. We describe BLPOP but the two commands
	 * are identical, the only difference is that BLPOP pops the element from the left (head) of the
	 * list, and BRPOP pops from the right (tail).
	 * <p>
	 * <b>Non blocking behavior</b>
	 * <p>
	 * When BLPOP is called, if at least one of the specified keys contain a non empty list, an
	 * element is popped from the head of the list and returned to the caller together with the name
	 * of the key (BLPOP returns a two elements array, the first element is the key, the second the
	 * popped value).
	 * <p>
	 * Keys are scanned from left to right, so for instance if you issue BLPOP list1 list2 list3 0
	 * against a dataset where list1 does not exist but list2 and list3 contain non empty lists, BLPOP
	 * guarantees to return an element from the list stored at list2 (since it is the first non empty
	 * list starting from the left).
	 * <p>
	 * <b>Blocking behavior</b>
	 * <p>
	 * If none of the specified keys exist or contain non empty lists, BLPOP blocks until some other
	 * client performs a LPUSH or an RPUSH operation against one of the lists.
	 * <p>
	 * Once new data is present on one of the lists, the client finally returns with the name of the
	 * key unblocking it and the popped value.
	 * <p>
	 * When blocking, if a non-zero timeout is specified, the client will unblock returning a nil
	 * special value if the specified amount of seconds passed without a push operation against at
	 * least one of the specified keys.
	 * <p>
	 * The timeout argument is interpreted as an integer value. A timeout of zero means instead to
	 * block forever.
	 * <p>
	 * <b>Multiple clients blocking for the same keys</b>
	 * <p>
	 * Multiple clients can block for the same key. They are put into a queue, so the first to be
	 * served will be the one that started to wait earlier, in a first-blpopping first-served fashion.
	 * <p>
	 * <b>blocking POP inside a MULTI/EXEC transaction</b>
	 * <p>
	 * BLPOP and BRPOP can be used with pipelining (sending multiple commands and reading the replies
	 * in batch), but it does not make sense to use BLPOP or BRPOP inside a MULTI/EXEC block (a Redis
	 * transaction).
	 * <p>
	 * The behavior of BLPOP inside MULTI/EXEC when the list is empty is to return a multi-bulk nil
	 * reply, exactly what happens when the timeout is reached. If you like science fiction, think at
	 * it like if inside MULTI/EXEC the time will flow at infinite speed :)
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param timeout
	 * @param keys
	 * @return BLPOP returns a two-elements array via a multi bulk reply in order to return both the
	 * unblocking key and the popped value.
	 * <p>
	 * When a non-zero timeout is specified, and the BLPOP operation timed out, the return
	 * value is a nil multi bulk reply. Most client values will return false or nil
	 * accordingly to the programming language used.
	 * @see #blpop(int, String...)
	 */
	@Override
	public List<String> brpop(final int timeout, final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(timeout, keys);
        }
	}

	@Override
	public Long zcount(final String key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcount(key, min, max);
        }
	}

	@Override
	public Long zcount(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zcount(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(String, double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(String, double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(String, double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min a double or Double.NEGATIVE_INFINITY for "-inf"
	 * @param max a double or Double.POSITIVE_INFINITY for "+inf"
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(String, double, double)
	 * @see #zrangeByScore(String, double, double, int, int)
	 * @see #zrangeByScoreWithScores(String, double, double)
	 * @see #zrangeByScoreWithScores(String, String, String)
	 * @see #zrangeByScoreWithScores(String, double, double, int, int)
	 * @see #zcount(String, double, double)
	 */
	@Override
	public Set<String> zrangeByScore(final String key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScore(key, min, max);
        }
	}

	@Override
	public Set<String> zrangeByScore(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScore(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(String, double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(String, double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(String, double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(String, double, double)
	 * @see #zrangeByScore(String, double, double, int, int)
	 * @see #zrangeByScoreWithScores(String, double, double)
	 * @see #zrangeByScoreWithScores(String, double, double, int, int)
	 * @see #zcount(String, double, double)
	 */
	@Override
	public Set<String> zrangeByScore(final String key, final double min, final double max,
	                                 final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScore(key, min, max, offset, count);
		}
	}

	@Override
	public Set<String> zrangeByScore(final String key, final String min, final String max,
	                                 final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScore(key, min, max, offset, count);
		}
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(String, double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(String, double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(String, double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(String, double, double)
	 * @see #zrangeByScore(String, double, double, int, int)
	 * @see #zrangeByScoreWithScores(String, double, double)
	 * @see #zrangeByScoreWithScores(String, double, double, int, int)
	 * @see #zcount(String, double, double)
	 */
	@Override
	public Set<Tuple> zrangeByScoreWithScores(final String key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScoreWithScores(key, min, max);
        }
	}

	@Override
	public Set<Tuple> zrangeByScoreWithScores(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByScoreWithScores(key, min, max);
        }
	}

	/**
	 * Return the all the elements in the sorted set at key with a score between min and max
	 * (including elements with score equal to min or max).
	 * <p>
	 * The elements having the same score are returned sorted lexicographically as ASCII strings (this
	 * follows from a property of Redis sorted sets and does not involve further computation).
	 * <p>
	 * Using the optional {@link #zrangeByScore(String, double, double, int, int) LIMIT} it's possible
	 * to get only a range of the matching elements in an SQL-alike way. Note that if offset is large
	 * the commands needs to traverse the list for offset elements and this adds up to the O(M)
	 * figure.
	 * <p>
	 * The {@link #zcount(String, double, double) ZCOUNT} command is similar to
	 * {@link #zrangeByScore(String, double, double) ZRANGEBYSCORE} but instead of returning the
	 * actual elements in the specified interval, it just returns the number of matching elements.
	 * <p>
	 * <b>Exclusive intervals and infinity</b>
	 * <p>
	 * min and max can be -inf and +inf, so that you are not required to know what's the greatest or
	 * smallest element in order to take, for instance, elements "up to a given value".
	 * <p>
	 * Also while the interval is for default closed (inclusive) it's possible to specify open
	 * intervals prefixing the score with a "(" character, so for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (1.3 5}
	 * <p>
	 * = 5, while for instance:
	 * <p>
	 * {@code ZRANGEBYSCORE zset (5 (10}
	 * <p>
	 * 10 (5 and 10 excluded).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements returned by the command, so if M is constant (for instance you always ask for the
	 * first ten elements with LIMIT) you can consider it O(log(N))
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Multi bulk reply specifically a list of elements in the specified score range.
	 * @see #zrangeByScore(String, double, double)
	 * @see #zrangeByScore(String, double, double, int, int)
	 * @see #zrangeByScoreWithScores(String, double, double)
	 * @see #zrangeByScoreWithScores(String, double, double, int, int)
	 * @see #zcount(String, double, double)
	 */
	@Override
	public Set<Tuple> zrangeByScoreWithScores(final String key, final double min, final double max,
	                                          final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScoreWithScores(key, min, max, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrangeByScoreWithScores(final String key, final String min, final String max,
	                                          final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByScoreWithScores(key, min, max, offset, count);
		}
	}

	@Override
	public Set<String> zrevrangeByScore(final String key, final double max, final double min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScore(key, max, min);
        }
	}

	@Override
	public Set<String> zrevrangeByScore(final String key, final String max, final String min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScore(key, max, min);
        }
	}

	@Override
	public Set<String> zrevrangeByScore(final String key, final double max, final double min,
	                                    final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScore(key, max, min, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final String key, final double max, final double min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScoreWithScores(key, max, min);
        }
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final String key, final double max,
	                                             final double min, final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScoreWithScores(key, max, min, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final String key, final String max,
	                                             final String min, final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScoreWithScores(key, max, min, offset, count);
		}
	}

	@Override
	public Set<String> zrevrangeByScore(final String key, final String max, final String min,
	                                    final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrevrangeByScore(key, max, min, offset, count);
		}
	}

	@Override
	public Set<Tuple> zrevrangeByScoreWithScores(final String key, final String max, final String min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByScoreWithScores(key, max, min);
        }
	}

	/**
	 * Remove all elements in the sorted set at key with rank between start and end. Start and end are
	 * 0-based with rank 0 being the element with the lowest score. Both start and end can be negative
	 * numbers, where they indicate offsets starting at the element with the highest rank. For
	 * example: -1 is the element with the highest score, -2 the element with the second highest score
	 * and so forth.
	 * <p>
	 * <b>Time complexity:</b> O(log(N))+O(M) with N being the number of elements in the sorted set
	 * and M the number of elements removed by the operation
	 */
	@Override
	public Long zremrangeByRank(final String key, final long start, final long stop){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByRank(key, start, stop);
        }
	}

	/**
	 * Remove all the elements in the sorted set at key with a score between min and max (including
	 * elements with score equal to min or max).
	 * <p>
	 * <b>Time complexity:</b>
	 * <p>
	 * O(log(N))+O(M) with N being the number of elements in the sorted set and M the number of
	 * elements removed by the operation
	 *
	 * @param key
	 * @param min
	 * @param max
	 * @return Integer reply, specifically the number of elements removed.
	 */
	@Override
	public Long zremrangeByScore(final String key, final double min, final double max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByScore(key, min, max);
        }
	}

	@Override
	public Long zremrangeByScore(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByScore(key, min, max);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(String, String...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The
	 * {@link #zunionstore(String, String...) ZUNIONSTORE} command inserts all elements across all
	 * inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(String, String...)
	 * @see #zunionstore(String, ZParams, String...)
	 * @see #zinterstore(String, String...)
	 * @see #zinterstore(String, ZParams, String...)
	 */
	@Override
	public Long zunionstore(final String dstkey, final String... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zunionstore(dstkey, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(String, String...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The
	 * {@link #zunionstore(String, String...) ZUNIONSTORE} command inserts all elements across all
	 * inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @param params
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(String, String...)
	 * @see #zunionstore(String, ZParams, String...)
	 * @see #zinterstore(String, String...)
	 * @see #zinterstore(String, ZParams, String...)
	 */
	@Override
	public Long zunionstore(final String dstkey, final ZParams params, final String... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zunionstore(dstkey, params, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(String, String...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The
	 * {@link #zunionstore(String, String...) ZUNIONSTORE} command inserts all elements across all
	 * inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(String, String...)
	 * @see #zunionstore(String, ZParams, String...)
	 * @see #zinterstore(String, String...)
	 * @see #zinterstore(String, ZParams, String...)
	 */
	@Override
	public Long zinterstore(final String dstkey, final String... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zinterstore(dstkey, sets);
        }
	}

	/**
	 * Creates a union or intersection of N sorted sets given by keys k1 through kN, and stores it at
	 * dstkey. It is mandatory to provide the number of input keys N, before passing the input keys
	 * and the other (optional) arguments.
	 * <p>
	 * As the terms imply, the {@link #zinterstore(String, String...) ZINTERSTORE} command requires an
	 * element to be present in each of the given inputs to be inserted in the result. The
	 * {@link #zunionstore(String, String...) ZUNIONSTORE} command inserts all elements across all
	 * inputs.
	 * <p>
	 * Using the WEIGHTS option, it is possible to add weight to each input sorted set. This means
	 * that the score of each element in the sorted set is first multiplied by this weight before
	 * being passed to the aggregation. When this option is not given, all weights default to 1.
	 * <p>
	 * With the AGGREGATE option, it's possible to specify how the results of the union or
	 * intersection are aggregated. This option defaults to SUM, where the score of an element is
	 * summed across the inputs where it exists. When this option is set to be either MIN or MAX, the
	 * resulting set will contain the minimum or maximum score of an element across the inputs where
	 * it exists.
	 * <p>
	 * <b>Time complexity:</b> O(N) + O(M log(M)) with N being the sum of the sizes of the input
	 * sorted sets, and M being the number of elements in the resulting sorted set
	 *
	 * @param dstkey
	 * @param sets
	 * @param params
	 * @return Integer reply, specifically the number of elements in the sorted set at dstkey
	 * @see #zunionstore(String, String...)
	 * @see #zunionstore(String, ZParams, String...)
	 * @see #zinterstore(String, String...)
	 * @see #zinterstore(String, ZParams, String...)
	 */
	@Override
	public Long zinterstore(final String dstkey, final ZParams params, final String... sets){
        try(Jedis jedis = pool.getResource()){
            return jedis.zinterstore(dstkey, params, sets);
        }
	}

	@Override
	public Long zlexcount(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zlexcount(key, min, max);
        }
	}

	@Override
	public Set<String> zrangeByLex(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrangeByLex(key, min, max);
        }
	}

	@Override
	public Set<String> zrangeByLex(final String key, final String min, final String max,
	                               final int offset, final int count){
		try(Jedis jedis = pool.getResource()){
			return jedis.zrangeByLex(key, min, max, offset, count);
		}
	}

	@Override
	public Set<String> zrevrangeByLex(final String key, final String max, final String min){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByLex(key, max, min);
        }
	}

	@Override
	public Set<String> zrevrangeByLex(final String key, final String max, final String min, final int offset, final int count){
        try(Jedis jedis = pool.getResource()){
            return jedis.zrevrangeByLex(key, max, min, offset, count);
        }
	}

	@Override
	public Long zremrangeByLex(final String key, final String min, final String max){
        try(Jedis jedis = pool.getResource()){
            return jedis.zremrangeByLex(key, min, max);
        }
	}

	@Override
	public Long linsert(String key, BinaryClient.LIST_POSITION where, String pivot, String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.linsert(key, where, pivot, value);
        }
	}

	@Override
	public Long strlen(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.strlen(key);
        }
	}

	@Override
	public Long lpushx(final String key, final String... string){
        try(Jedis jedis = pool.getResource()){
            return jedis.lpushx(key, string);
        }
	}

	/**
	 * Undo a {@link #expire(String, int) expire} at turning the expire key into a normal key.
	 * <p>
	 * Time complexity: O(1)
	 *
	 * @param key
	 * @return Integer reply, specifically: 1: the key is now persist. 0: the key is not persist (only
	 * happens when key not set).
	 */
	@Override
	public Long persist(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.persist(key);
        }
	}

	@Override
	public Long rpushx(final String key, final String... string){
        try(Jedis jedis = pool.getResource()){
            return jedis.rpushx(key, string);
        }
	}

	@Override
	public String echo(final String string){
        try(Jedis jedis = pool.getResource()){
            return jedis.echo(string);
        }
	}

	@Override
	public Long linsert(final String key, final ListPosition where, final String pivot,
	                    final String value){
		try(Jedis jedis = pool.getResource()){
			return jedis.linsert(key, where, pivot, value);
		}
	}

	/**
	 * or block until one is available
	 *
	 * @param source
	 * @param destination
	 * @param timeout
	 * @return the element
	 */
	@Override
	public String brpoplpush(final String source, final String destination, final int timeout){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpoplpush(source, destination, timeout);
        }
	}

	/**
	 * Sets or clears the bit at offset in the string value stored at key
	 *
	 * @param key
	 * @param offset
	 * @param value
	 * @return
	 */
	@Override
	public Boolean setbit(final String key, final long offset, final boolean value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setbit(key, offset, value);
        }
	}

	@Override
	public Boolean setbit(final String key, final long offset, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setbit(key, offset, value);
        }
	}

	/**
	 * Returns the bit value at offset in the string value stored at key
	 *
	 * @param key
	 * @param offset
	 * @return
	 */
	@Override
	public Boolean getbit(final String key, final long offset){
        try(Jedis jedis = pool.getResource()){
            return jedis.getbit(key, offset);
        }
	}

	@Override
	public Long setrange(final String key, final long offset, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.setrange(key, offset, value);
        }
	}

	@Override
	public String getrange(final String key, final long startOffset, final long endOffset){
        try(Jedis jedis = pool.getResource()){
            return jedis.getrange(key, startOffset, endOffset);
        }
	}

	@Override
	public Long bitpos(final String key, final boolean value){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitpos(key, value);
        }
	}

	@Override
	public Long bitpos(final String key, final boolean value, final BitPosParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitpos(key, value, params);
        }
	}

	@Override
	public Long publish(final String channel, final String message){
        try(Jedis jedis = pool.getResource()){
            return jedis.publish(channel, message);
        }
	}

    /**
     * Подписаться на прослушивание каналов.
     *
     * <p>Этот метод является альтернативой метода {@link #subscribe(JedisPubSub, String...)},
     * и рекомендуется к использованию. Этот метод создает подписку с помощью обертки {@link JedisPubSubWrapper},
     * о преимуществах которой можете почитать в ее javadoc. Ключевая особенность заключается в том, что этот метод
     * не блокирует поток, что делает подписку легковесной.
     *
     * @param listener слушатель, который будет вызываться, когда будет приходить сообщение на указанные каналы.
     * @param channels имя каналов.
     * @return слушатель, переданный параметром {@code listener}. Он выступает индентификатором подписки,
     * с помощью слушателя можно отменить подписку методом {@link #unsubscribe(JedisPubSubListener)}.
     */
    public JedisPubSubListener subscribe(JedisPubSubListener listener, String... channels){
        for (String channel : channels) {
            pubSubWrapper.subscribe(listener, channel);
        }
        return listener;
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
        return pubSubWrapper.unsubscribe(listener);
    }

    /**
     * @deprecated не рекомендуется к использованию.
     * Есть альтернативный метод {@link #subscribe(JedisPubSubListener, String...)},
     * который создает подписку с помощью обертки {@link JedisPubSubWrapper},  о преимуществах
     * которой можете почитать в ее javadoc.
     */
    @Deprecated
	@Override
	public void subscribe(JedisPubSub jedisPubSub, String... channels){
        try(Jedis jedis = pool.getResource()){
            jedis.subscribe(jedisPubSub, channels);
        }
	}

	@Override
	public void psubscribe(JedisPubSub jedisPubSub, String... patterns){
        try(Jedis jedis = pool.getResource()){
            jedis.psubscribe(jedisPubSub, patterns);
        }
	}

	@Override
	public Long bitcount(final String key, final long start, final long end){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitcount(key, start, end);
        }
	}

	@Override
	public Long bitop(final BitOP op, final String destKey, final String... srcKeys){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitop(op, destKey, srcKeys);
        }
	}

	@Override
	public byte[] dump(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.dump(key);
        }
	}

	@Override
	public String restore(final String key, final int ttl, final byte[] serializedValue){
        try(Jedis jedis = pool.getResource()){
            return jedis.restore(key, ttl, serializedValue);
        }
	}

	@Deprecated
	public Long pexpire(final String key, final int milliseconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpire(key, milliseconds);
        }
	}

	@Override
	public Long pexpire(final String key, final long milliseconds){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpire(key, milliseconds);
        }
	}

	@Override
	public Long pexpireAt(final String key, final long millisecondsTimestamp){
        try(Jedis jedis = pool.getResource()){
            return jedis.pexpireAt(key, millisecondsTimestamp);
        }
	}

	@Override
	public Long pttl(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.pttl(key);
        }
	}

	@Deprecated
	public String psetex(final String key, final int milliseconds, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.psetex(key, milliseconds, value);
        }
	}

	/**
	 * PSETEX works exactly like {@link #setex(String, int, String)} with the sole difference that the
	 * expire time is specified in milliseconds instead of seconds. Time complexity: O(1)
	 *
	 * @param key
	 * @param milliseconds
	 * @param value
	 * @return Status code reply
	 */
	@Override
	public String psetex(final String key, final long milliseconds, final String value){
        try(Jedis jedis = pool.getResource()){
            return jedis.psetex(key, milliseconds, value);
        }
	}

	@Override
	public String set(final String key, final String value, final String nxxx){
        try(Jedis jedis = pool.getResource()){
            return jedis.set(key, value, nxxx);
        }
	}

	@Override
	@Deprecated
	/**
	 * This method is deprecated due to bug (scan cursor should be unsigned long)
	 * And will be removed on next major release
	 * @see https://github.com/xetorthio/jedis/issues/531
	 */
	public ScanResult<String> scan(int cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.scan(cursor);
        }
	}

	@Override
	@Deprecated
	/**
	 * This method is deprecated due to bug (scan cursor should be unsigned long)
	 * And will be removed on next major release
	 * @see https://github.com/xetorthio/jedis/issues/531
	 */
	public ScanResult<Map.Entry<String, String>> hscan(final String key, int cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.hscan(key, cursor);
        }
	}

	@Override
	@Deprecated
	/**
	 * This method is deprecated due to bug (scan cursor should be unsigned long)
	 * And will be removed on next major release
	 * @see https://github.com/xetorthio/jedis/issues/531
	 */
	public ScanResult<String> sscan(final String key, int cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.sscan(key, cursor);
        }
	}

	@Override
	@Deprecated
	/**
	 * This method is deprecated due to bug (scan cursor should be unsigned long)
	 * And will be removed on next major release
	 * @see https://github.com/xetorthio/jedis/issues/531
	 */
	public ScanResult<Tuple> zscan(final String key, int cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscan(key, cursor);
        }
	}

	@Override
	public ScanResult<String> scan(final String cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.scan(cursor);
        }
	}

	@Override
	public ScanResult<String> scan(final String cursor, final ScanParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.scan(cursor, params);
        }
	}

	@Override
	public ScanResult<Map.Entry<String, String>> hscan(final String key, final String cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.hscan(key, cursor);
        }
	}

	@Override
	public ScanResult<Map.Entry<String, String>> hscan(final String key, final String cursor,
	                                                   final ScanParams params){
		try(Jedis jedis = pool.getResource()){
			return jedis.hscan(key, cursor, params);
		}
	}

	@Override
	public ScanResult<String> sscan(final String key, final String cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.sscan(key, cursor);
        }
	}

	@Override
	public ScanResult<String> sscan(final String key, final String cursor, final ScanParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.sscan(key, cursor, params);
        }
	}

	@Override
	public ScanResult<Tuple> zscan(final String key, final String cursor){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscan(key, cursor);
        }
	}

	@Override
	public ScanResult<Tuple> zscan(final String key, final String cursor, final ScanParams params){
        try(Jedis jedis = pool.getResource()){
            return jedis.zscan(key, cursor, params);
        }
	}

	@Override
	public Long pfadd(final String key, final String... elements){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfadd(key, elements);
        }
	}

	@Override
	public long pfcount(final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfcount(key);
        }
	}

	@Override
	public long pfcount(final String... keys){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfcount(keys);
        }
	}

	@Override
	public String pfmerge(final String destkey, final String... sourcekeys){
        try(Jedis jedis = pool.getResource()){
            return jedis.pfmerge(destkey, sourcekeys);
        }
	}

	@Override
	public List<String> blpop(final int timeout, final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.blpop(timeout, key);
        }
	}

	@Override
	public List<String> brpop(final int timeout, final String key){
        try(Jedis jedis = pool.getResource()){
            return jedis.brpop(timeout, key);
        }
	}

	@Override
	public Long geoadd(final String key, final double longitude, final double latitude, final String member){
        try(Jedis jedis = pool.getResource()){
            return jedis.geoadd(key, longitude, latitude, member);
        }
	}

	@Override
	public Long geoadd(final String key, final Map<String, GeoCoordinate> memberCoordinateMap){
        try(Jedis jedis = pool.getResource()){
            return jedis.geoadd(key, memberCoordinateMap);
        }
	}

	@Override
	public Double geodist(final String key, final String member1, final String member2){
        try(Jedis jedis = pool.getResource()){
            return jedis.geodist(key, member1, member2);
        }
	}

	@Override
	public Double geodist(final String key, final String member1, final String member2, final GeoUnit unit){
        try(Jedis jedis = pool.getResource()){
            return jedis.geodist(key, member1, member2, unit);
        }
	}

	@Override
	public List<String> geohash(final String key, String... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.geohash(key, members);
        }
	}

	@Override
	public List<GeoCoordinate> geopos(final String key, String... members){
        try(Jedis jedis = pool.getResource()){
            return jedis.geopos(key, members);
        }
	}

	@Override
	public List<GeoRadiusResponse> georadius(final String key, final double longitude, final double latitude,
	                                         final double radius, final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadius(key, longitude, latitude, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusReadonly(final String key, final double longitude, final double latitude,
	                                                 final double radius, final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusReadonly(key, longitude, latitude, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadius(final String key, final double longitude, final double latitude,
	                                         final double radius, final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadius(key, longitude, latitude, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusReadonly(final String key, final double longitude, final double latitude,
	                                                 final double radius, final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusReadonly(key, longitude, latitude, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMember(final String key, final String member, final double radius,
	                                                 final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMember(key, member, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMemberReadonly(final String key, final String member, final double radius,
	                                                         final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMemberReadonly(key, member, radius, unit, param);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMemberReadonly(final String key, final String member, final double radius,
	                                                         final GeoUnit unit){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMemberReadonly(key, member, radius, unit);
		}
	}

	@Override
	public List<GeoRadiusResponse> georadiusByMember(final String key, final String member, final double radius,
	                                                 final GeoUnit unit, final GeoRadiusParam param){
		try(Jedis jedis = pool.getResource()){
			return jedis.georadiusByMember(key, member, radius, unit, param);
		}
	}

	@Override
	public List<Long> bitfield(final String key, final String... arguments){
        try(Jedis jedis = pool.getResource()){
            return jedis.bitfield(key, arguments);
        }
	}

	@Override
	public Long hstrlen(final String key, final String field){
        try(Jedis jedis = pool.getResource()){
            return jedis.hstrlen(key, field);
        }
	}

    @Override
    public void close() {
	    // методы close из pubSubWrapper и binaryPubSubWrapper
        // являются идемпотентными и не кидают исключений
        // по этому их можно просто закрывать без дополнительных проверок
        // и try catch блоков
        pubSubWrapper.close();
        binaryPubSubWrapper.close();
    }
}
