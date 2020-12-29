package ua.lokha.jediswrapper;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Оболочка для массива байтов. Служит для использования массива байтов в качестве ключа в хеш-карте.
 */
@AllArgsConstructor
@Data
public class ByteArrayWrapper {
    private byte[] bytes;
}
