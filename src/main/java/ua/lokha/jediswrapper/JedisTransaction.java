package ua.lokha.jediswrapper;

import lombok.Getter;
import lombok.Lombok;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

/**
 * Работает так же, как и {@link Transaction}, только освобождает ресурс {@link #getJedis()} вместе с собой
 * в методе {@link #close()}.
 *
 * <p>Объект этого класса является ресурсом. После завершения работы с ним, следует вызвать {@link #close()}.
 */
public class JedisTransaction extends Transaction {

    /**
     * Соединение с Redis, из которого создана эта транзакция.
     */
    @Getter
    private Jedis jedis;

    public JedisTransaction(Jedis jedis) {
        super(jedis.getClient());
        this.jedis = jedis;
    }

    /**
     * Работает так же, как и {@link JedisTransaction#close()}, только освобождает ресурс {@link #getJedis()}.
     */
    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            Lombok.sneakyThrow(e);
        } finally {
            jedis.close();
        }
    }
}
