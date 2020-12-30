### JedisWrapper

JedisWrapper - это обертка для библиотеки <https://github.com/redis/jedis>.

Стандартная библиотека Jedis не идеальна, по этому я сделал для нее обертку, которая решает некоторые 
проблемы и делает библиотеку лучше.

## Преимущества над обычным Jedis

*  JedisWrapper уменьшает количество кода. 
*  JedisWrapper берет на себя контроль за ресурсами. 
*  В JedisWrapper встроены улучшенные подписки PubSub, которые не требуют выделенных потоков. 
*  JedisWrapper избавляет вас от потребности бороться с checked исключениями. 

## Контроль над ресурсами

Работая с обычным Jedis разработчику необходимо самостоятельно 
контролировать получение ресурса из `JedisPool.getResource()`, а 
после освобождать ресурс с помощью `Jedis.close()`.

Минимальное обращение к API Jedis занимает 3 строчки кода:
```java
// JedisPool jedisPool = ...; 
try (Jedis jedis = jedisPool.getResource()) {
    jedis.set("key", "value");
}
```

Используя обертку JedisWrapper, этот код будет занимать всего 1 строку:
```java
// JedisWrapper jedisWrapper = ...; 
jedisWrapper.set("key", "value");
```

Класс `JedisWrapper` является оберткой сразу для `JedisPool` + `Jedis` классов.

Внутри класса `JedisWrapper` созданы методы-обертки для большей части методов из класса `Jedis`. 
При вызове любого метода из `JedisWrapper`,
будет взят ресурс `Jedis`, выполнена соотвествующая операция в нем, после чего ресурс будет освобожден.

Если все еще не понятно, я приведу пример метода `set(String key, String value)` из `JedisWrapper`:
```java
@Override
public String set(final String key, final String value){
    try(Jedis jedis = pool.getResource()){
        return jedis.set(key, value);
    }
}
```

## Улучшенные подписки PubSub

В Jedis реализована крайне неудачная API для подпискок. Каждая подписка для своей работы 
блокирует целый поток, что расточительно. Конечно, можно добавлять прослушиваемые каналы к уже созданной подписке,
но об этой возможности мало кто знает, и в целом это реализовано неудачно.

Минимум кода для создания подписки, используя Jedis API:
```java
new Thread(()->{
    // JedisPool jedisPool = ...; 
    try (Jedis jedis = jedisPool.getResource()) {
        jedis.subscribe(new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                // handle message
            }
        }, "channel-name");
    }
}).start();
```

Это катастрофа. Особенно забавно выглядит это полотно кода на фоне первых строк в 
документации к Jedis `Jedis was conceived to be EASY to use.`

Используя обертку JedisWrapper, этот код будет выглядеть так:
```java
jedisWrapper.subscribe((channel, message) -> {
    // handle message 
}, "channel-name");
```

В JedisWrapper для подписок созданы обертки `JedisPubSubWrapper` и `BinaryJedisPubSubWrapper`, 
использование которых даст следующие преимущества:
*  Использование одного потока для неограниченного количества подписок на прослушивание каналов.
*  Устойчивость к исключениям при обработке сообщений в методе `onMessage`. В Jedis при возникновении
неперехваченного исключения подписка обрывалась, в JedisWrapper исключение будет записано в лог вместе 
с информацией о канале и сообщением.
*  Возобновление работы подписки в случае ее обрыва. Если поизойдет какая-то ошибка, например,
с redis-сервером разорвется соединение на время,  `JedisPubSubWrapper` будет пробовать заново создать подписку с новым 
ресурсом `Jedis`.
*  Уменьшение количества кода.

### API

Точкой входа в API является класс `JedisWrapper`, который является оберткой и требует для своего 
создания `JedisPool`:
```java
JedisPool pool = new JedisPool(new GenericObjectPoolConfig(), "localhost", 6379);
JedisWrapper jedisWrapper = new JedisWrapper(pool);
```

С оберткой `jedisWrapper` можно работать так же, как и с обычным `Jedis`:
```java
jedisWrapper.set("key", "value");
String value = jedisWrapper.get("key");
jedisWrapper.publish("channel-name", "message")
```
В отличии от `JedisPool` не нужно получаться ресурс `Jedis` для выполнения операций, а после освобождать его.

Для корректного завершения работы с `JedisWrapper` необходимо освободить ресурсы:
```java
jedisWrapper.close();
pool.close();
```

`JedisWrapper` не освободит ресурс `JedisPool`, по этому его надо освобождать отдельно.

## Подписки

Создание подписки на канал, используя обертку `JedisWrapper`:
```java
jedisWrapper.subscribe((channel, message) -> {
    // handle message 
}, "channel-name");
```

Слушатель подписки `JedisPubSubListener` (лямбда выражение) используется в качестве идентификатора подписки и позже 
может быть использовано для отмены подписки: 
```java
JedisPubSubListener subscribe = jedisWrapper.subscribe((channel, message) -> {
    // handle message 
}, "channel-name");

jedisWrapper.unsubscribe(subscribe);
```

Поскольку для всех подписок используется общий поток, все слушатели
 `JedisPubSubListener` вызываются в этом потоке по очереди относительно друг друга.
Это может привести к проблеме, когда один слушатель будет откладывать вызов других слушателей,
в результате конкруренции за использование общего потока.
Чтобы решить эту проблему, нужно создать `JedisWrapper`, используя его конструктор, где принимается
так же `Executor`:
```java
ExecutorService service = Executors.newFixedThreadPool(4,
    new ThreadFactoryBuilder().setNameFormat("Jedis PubSub %d").build());
JedisWrapper jedisWrapper = new JedisWrapper(pool, service);
```
С такой конфигурацией все вызовы слушателей будут оборачиваться в `Executor.execute(Runnable)`.
В качестве Excutor'a в примере используется `ExecutorService` на 4 потока.

### JedisPubSubWrapper и BinaryJedisPubSubWrapper

Имеется возможность использовать обертку для подписок отдельно от использования `JedisWrapper`.

Для этого нужно создать один инстанс обертки для подписок `JedisPubSubWrapper` и/или `BinaryJedisPubSubWrapper`, 
если вам так же требуются binary подписки: 
```java
JedisPool pool = new JedisPool(new GenericObjectPoolConfig(), "localhost", 6379);
JedisPubSubWrapper pubSub = new JedisPubSubWrapper(pool);
```

После чего можно создавать подписки:
```java
pubSub.subscribe((channel, message) -> {
    // handle message
}, "channel-name");
```

Метод `pubSub.subscribe` не будет блокировать поток, в отличии от `Jedis.subscribe`. 
Внутри `JedisPubSubWrapper` создается общий поток, в котором будут работать все подписки.

Для корректного завершения работы с `JedisPubSubWrapper` необходимо освободить ресурсы:
```java
pubSub.close();
pool.close();
```

`JedisPubSubWrapper` не освободит ресурс `JedisPool`, по этому его надо освобождать отдельно.

## Pipeline

`JedisWrapper` так же поддерживает pipeline:
```java
try (JedisPipeline pipeline = jedisWrapper.pipelined()) {
    Response<String> response1 = pipeline.get("key1");
    Response<String> response2 = pipeline.get("key2");
    pipeline.sync();
    String value1 = response1.get();
    String value2 = response2.get();
}
```

Отличие `JedisWrapper.pipelined()` от `Jedis.pipelined()` заключается в том, что этот метод возвращает объект pipeline `JedisPipeline`
вместо `Pipeline`. Отличительной особенностью `JedisPipeline` является то, что он при освобождении 
его ресурса `JedisPipeline.close()` так же освобождает ресурс `Jedis`, который хранит внутри себя.

## Транзации

`JedisWrapper` так же поддерживает транзации:
```java
try (JedisTransaction pipeline = jedisWrapper.multi()) {
    pipeline.set("key1", "value1");
    pipeline.set("key2", "value2");
    pipeline.exec();
}
```

Отличие `JedisWrapper.multi()` от `Jedis.multi()` заключается в том, что этот метод возвращает объект транзации `JedisTransaction`
вместо `Transaction`. Отличительной особенностью `JedisTransaction` является то, что он при освобождении 
его ресурса `JedisTransaction.close()` так же освобождает ресурс `Jedis`, который хранит внутри себя.