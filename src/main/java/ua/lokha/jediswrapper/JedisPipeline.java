package ua.lokha.jediswrapper;

import lombok.Getter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

/**
 * Работает так же, как и {@link Pipeline}, только освобождает ресурс {@link #getJedis()} вместе с собой
 * в методе {@link #close()}.
 *
 * <p>Объект этого класса является ресурсом. После завершения работы с ним, следует вызвать {@link #close()}.
 */
public class JedisPipeline extends Pipeline {

    /**
     * Соединение с Redis, из которого создан этот pipeline.
     */
    @Getter
    private Jedis jedis;

    public JedisPipeline(Jedis jedis) {
        this.jedis = jedis;
    }

    /**
     * Работает так же, как и {@link Pipeline#close()}, только освобождает ресурс {@link #getJedis()}.
     */
    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            ThrowUtils.sneakyThrow(e);
        } finally {
            jedis.close();
        }
    }
}
