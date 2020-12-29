package ua.lokha.jediswrapper;

/**
 * Интерфейс для обработки сообщений, которые приходят на прослушиваемый канал.
 */
public interface BinaryJedisPubSubListener {

    /**
     * Вызывается, когда на канал приходит сообщение.
     *
     * @param channel канал, на который пришло сообщение.
     * @param message сообщение.
     */
    void onMessage(byte[] channel, byte[] message) throws Exception;
}
