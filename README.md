# Resource Booking

REST и gRPC API для бронирования переговорных и оборудования. Показывает свободные интервалы, создаёт и отменяет бронь. Kotlin, Spring Boot, PostgreSQL, Spring JDBC, Flyway, Protobuf.

Демонстрационный проект без авторизации: `bookedBy` хранит имя, но не подтверждает личность. Любой клиент может читать и отменять брони. Ключи идемпотентности общие для всего API. Публиковать такой экземпляр с настоящими данными не стоит.

## Запуск

Нужны Docker и Docker Compose:

```sh
docker compose up --build -d
curl http://localhost:8083/actuator/health
```

Или Java 21 и Maven 3.9+, если приложение запускается на хосте:

```sh
docker compose up -d postgres
mvn spring-boot:run
```

База доступна на `localhost:5434`, REST на `localhost:8083`, gRPC на `localhost:9090`. Для других портов задайте `DB_PORT`, `API_PORT` и `GRPC_PORT` при запуске Compose. При запуске через Maven подключение задаётся переменными `DB_URL`, `DB_USER`, `DB_PASSWORD`, порты через `PORT` и `GRPC_PORT`. По умолчанию имя базы, пользователь и пароль: `booking`.

```sh
curl http://localhost:8083/api/resources

curl -i http://localhost:8083/api/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: meeting-001' \
  -d '{"resourceId":"11111111-1111-1111-1111-111111111111","bookedBy":"Никита","startsAt":"2030-01-02T10:00:00Z","endsAt":"2030-01-02T11:00:00Z"}'

curl 'http://localhost:8083/api/resources/11111111-1111-1111-1111-111111111111/availability?from=2030-01-02T09:00:00Z&to=2030-01-02T12:00:00Z'

curl http://localhost:8083/api/bookings/BOOKING_ID
curl -X POST http://localhost:8083/api/bookings/BOOKING_ID/cancel
```

Замените `BOOKING_ID` на `id` из ответа. Даты в примере должны быть в будущем. Остановить приложение: `docker compose down`. Данные сохраняются в Docker volume.

## Поведение

Бронь длится не больше 24 часов и начинается в будущем. Для просмотра свободных окон можно запросить период до 31 дня. API принимает ISO 8601 со смещением и точностью до микросекунд; ответы возвращает в UTC. Интервалы включают начало и исключают конец, поэтому брони 10:00–11:00 и 11:00–12:00 допустимы.

PostgreSQL запрещает пересечение активных броней одного ресурса через `EXCLUDE USING gist`. Создания брони одного ресурса последовательно берут блокировку его строки, чтобы избежать взаимных блокировок при проверке пересечения. Разные ресурсы бронируются независимо. Отмена меняет статус и освобождает интервал; повторная отмена возвращает тот же результат.

При создании нужен `Idempotency-Key` длиной до 128 символов: латинские буквы, цифры, `.`, `_`, `:`, `-`. Ключ, SHA-256 запроса и ответ сохраняются в одной транзакции с бронью. Параллельный повтор ждёт первую транзакцию и возвращает исходный ответ `201` с заголовком `Idempotency-Replayed: true`. Другие данные с тем же ключом дают `409`. Пробелы по краям имени и эквивалентные часовые смещения нормализуются.

Сохраняется только успешное создание. Ошибка откатывает и бронь, и ключ, поэтому запрос можно исправить и повторить. Сохранённый ответ не меняется после отмены: актуальный статус доступен через `GET /api/bookings/{id}`. Ключи пока хранятся бессрочно.

Ошибки возвращаются как `application/problem+json`: `400` для некорректного запроса, `404` для отсутствующего ресурса или брони, `409` для занятого интервала или повторного использования ключа с другими данными.

## gRPC

Контракт находится в `src/main/proto/booking.proto`. Maven генерирует Java messages и stubs, Kotlin реализует `booking.v1.BookingApi`. Методы: `ListResources`, `GetResource`, `GetAvailability`, `CreateBooking`, `GetBooking`, `CancelBooking`. Сервер работает без TLS; Compose открывает оба API только на localhost. Reflection позволяет обращаться через grpcurl без отдельного файла схемы:

```sh
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext -d '{}' localhost:9090 booking.v1.BookingApi/ListResources

grpcurl -plaintext -H 'idempotency-key: meeting-001' \
  -d '{"resourceId":"11111111-1111-1111-1111-111111111111","bookedBy":"Никита","startsAt":"2030-01-02T10:00:00Z","endsAt":"2030-01-02T11:00:00Z"}' \
  localhost:9090 booking.v1.BookingApi/CreateBooking

grpcurl -plaintext \
  -d '{"resourceId":"11111111-1111-1111-1111-111111111111","from":"2030-01-02T09:00:00Z","to":"2030-01-02T12:00:00Z"}' \
  localhost:9090 booking.v1.BookingApi/GetAvailability

grpcurl -plaintext -d '{"id":"BOOKING_ID"}' localhost:9090 booking.v1.BookingApi/GetBooking
grpcurl -plaintext -d '{"id":"BOOKING_ID"}' localhost:9090 booking.v1.BookingApi/CancelBooking
```

Оба транспорта вызывают один `BookingService`. Метаданные `idempotency-key` соответствуют HTTP-заголовку `Idempotency-Key`: созданную через REST бронь можно повторить через gRPC с тем же ключом и данными, и наоборот. gRPC возвращает бронь и поле `replayed`. Ограничение пересечений и транзакция в PostgreSQL общие.

Некорректные UUID, имена, отсутствующие или неверные Timestamp дают `INVALID_ARGUMENT`. Для отсутствующих сущностей возвращается `NOT_FOUND`, для конфликтов времени и ключей возвращается `ALREADY_EXISTS`. В trailers `error-code` передаётся `SLOT_UNAVAILABLE`, `IDEMPOTENCY_KEY_REUSED` или другой код доменной ошибки. Недоступная база даёт `UNAVAILABLE`, непредвиденная ошибка даёт `INTERNAL` без деталей SQL.

Клиент может задать deadline, например `grpcurl -max-time 5 -plaintext ...`. Истёкший deadline не гарантирует откат уже начатой транзакции: повторяйте создание с тем же ключом. При остановке сервер перестаёт принимать вызовы, ждёт завершения текущих до 10 секунд и даёт до 5 секунд на принудительное завершение.

## Проверка

```sh
mvn verify
```

Тестам нужен работающий Docker. Testcontainers сам поднимает PostgreSQL; локальная база не используется. Проверяются REST и настоящий TCP gRPC: конфликт интервалов, отмена, свободные окна, валидация, повторы между транспортами, deadline, reflection и остановка сервера. Гонки проверяются для разных ключей на один интервал, одинаковых ключей и одинаковых ключей с разными данными.
