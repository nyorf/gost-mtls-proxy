# gost-mtls-proxy

Английская версия — в [README.md](README.md).

Прозрачный прокси для API, который принимает только ГОСТ TLS с клиентским сертификатом.

## Что делает прокси

Некоторые API принимают только TLS-соединение с российскими алгоритмами ГОСТ и клиентским сертификатом.
Стандартные HTTP-клиенты не могут установить такое соединение.

`gost-mtls-proxy` — это образ контейнера с полным стеком ГОСТ TLS.
Ваш клиент отправляет обычный HTTP-запрос в контейнер.
Контейнер отправляет тот же запрос в целевой API по ГОСТ TLS 1.2 с вашим клиентским сертификатом.
Затем контейнер отправляет ответ целевого API обратно вашему клиенту.

Прокси сохраняет метод, путь, строку запроса, заголовки и тело.
Строка запроса доходит до целевого API без изменений. Параметр может встречаться в строке запроса более одного раза.
Прокси вносит в запрос только пять изменений:

- Прокси заменяет заголовок `Host` на хост целевого API.
- Прокси удаляет заголовок `X-Proxy-Token`.
- Прокси удаляет hop-by-hop-заголовки. Это `Connection`, `Keep-Alive`, `Proxy-Authenticate`,
  `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding` и `Upgrade`.
- Прокси заменяет заголовок `User-Agent` собственным значением, `GostMtlsProxy/v<version>`. Задайте
  `USER_AGENT_OVERRIDE` значение `false`, чтобы отправить заголовок `User-Agent` вашего клиента без изменений.
- Прокси задаёт заголовок `X-Request-Id` равным собственному id запроса: ваше значение, если оно непустое,
  иначе новый UUID4. До целевого API доходит ровно один заголовок `X-Request-Id`, независимо от того, что отправили вы.

Запрос к `http://localhost:8080/api/v2/company` становится запросом к
`https://gost-openapi.tbank.ru/api/v2/company`.

## Как это работает

```
                          gost-mtls-proxy container
                 +--------------------------------------------+
   plain HTTP    |  http4s app            stunnel client      |         GOST TLS 1.2
   request       |                                            |         client certificate
client --------->|  0.0.0.0:8080  ----->  127.0.0.1:8443      |-------> target API
       <---------|  (PORT)                (UPSTREAM_PORT)     |<------- (TARGET_URL)
   response      +--------------------------------------------+
```

Контейнер содержит два процесса:

1. Приложение http4s на Scala 3 слушает порт `PORT`. Порт по умолчанию — 8080.
2. Локальный процесс stunnel слушает `127.0.0.1` на порту `UPSTREAM_PORT`. Порт по умолчанию — 8443.

Приложение отправляет каждый запрос в stunnel через интерфейс loopback.
stunnel устанавливает соединение ГОСТ TLS 1.2 с целевым API.
stunnel также отправляет клиентский сертификат.
JVM не выполняет никакой работы с TLS.

### Алгоритмы ГОСТ

Стандартная сборка OpenSSL не содержит алгоритмов ГОСТ.
Образ добавляет их двумя отдельными сборками gost-engine:

- Dockerfile собирает gost-engine v3.0.3 из исходного кода в режиме engine.
  stunnel использует эту сборку для каждого TLS-соединения.
- Dockerfile собирает gost-engine master, коммит `3dd0f0e4299489a537398cfa4d9daad260ac87a8`, в режиме провайдера.
  Только эта сборка читает файл pfx от CryptoPro.

### Последовательность запуска

Скрипт entrypoint выполняет эти шаги при каждом запуске контейнера:

1. Скрипт проверяет `TARGET_URL`.
2. Скрипт выбирает режим сертификата: pfx или pem.
3. В режиме pfx скрипт преобразует файл pfx в файл сертификата и файл ключа.
4. Скрипт записывает файл конфигурации stunnel.
5. Скрипт запускает stunnel.
6. Скрипт ждёт, пока порт `UPSTREAM_PORT` не примет соединение. Предел — 5 секунд.
7. Скрипт удаляет `CERT_PASSWORD` и `CERT_PASSWORD_FILE` из окружения.
8. Скрипт запускает Java-приложение.

Скрипт записывает незашифрованный закрытый ключ только в `/run/gost-proxy/key.pem`.
Режим этого файла — 600.
Владелец — пользователь `gostproxy` без прав root, uid 10001.

### Образ

Финальная стадия образа начинается с `ubuntu:24.04`. Сборка усиливает защиту этой стадии:

- Сборка очищает оба механизма источников apt. Финальный образ не может установить пакет во время работы.
- Сборка удаляет документы, страницы руководства и локали.
- Сборка делает сокращённый JRE с помощью jlink. Набор модулей — `java.base`, `java.management` и `jdk.unsupported`.
- Контейнер запускается от пользователя `gostproxy` без прав root.

Размер образа — примерно 244 МБ.

## Необходимое программное обеспечение

Для контейнера из опубликованного образа:

- podman или docker. Образ работает с обоими.
- Клиентский сертификат для целевого API в формате pfx или PEM.
- Сетевой доступ к целевому API.

Для сборки из исходного кода:

- podman или docker с сетевым доступом. Сборка загружает gost-engine и sbt.
- JDK 21 и sbt 1.12.15 для тестов на вашей машине. Сборка образа их не использует.

## Быстрый старт

**NOTE: запросы в этом документе используют ГОСТ API Т-Банка как пример целевого API. Справочник API — [здесь](https://developer.tbank.ru/docs/api/get-api-v-2-company).**

### Файл pfx без пароля

1. Скопируйте ваш файл pfx в текущий каталог под именем `client.pfx`.
2. Запустите контейнер.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

Скрипт entrypoint находит `/certs/client.pfx` без `CERT_PATH`.

### Файл pfx с паролем

**CAUTION: ИСПОЛЬЗУЙТЕ В ПАРОЛЕ PFX ТОЛЬКО СИМВОЛЫ ASCII. GOST-ENGINE НЕ МОЖЕТ ПРОЧИТАТЬ ПАРОЛЬ С
ДРУГИМИ СИМВОЛАМИ. КОНТЕЙНЕР ОСТАНАВЛИВАЕТСЯ С ОШИБКОЙ.**

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PATH=/certs/client.pfx \
  -e CERT_PASSWORD=your-pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PATH=/certs/client.pfx \
  -e CERT_PASSWORD=your-pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### Файл pfx с файлом пароля

Пароль в командной строке виден в списке процессов.
Файл пароля не даёт значению попасть в список процессов.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PASSWORD_FILE=/run/secrets/pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/pfx-password.txt:/run/secrets/pfx-password:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PASSWORD_FILE=/run/secrets/pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/pfx-password.txt:/run/secrets/pfx-password:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### Сертификат и ключ в формате PEM

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PEM_PATH=/certs/client.pem \
  -e KEY_PEM_PATH=/certs/client-key.pem \
  -v "$PWD/certs:/certs:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PEM_PATH=/certs/client.pem \
  -e KEY_PEM_PATH=/certs/client-key.pem \
  -v "$PWD/certs:/certs:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### Контейнер с общим секретом

Задайте `PROXY_TOKEN` длинное случайное значение.
После этого каждая вызывающая сторона должна отправлять это значение в заголовке `X-Proxy-Token`.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e PROXY_TOKEN=change-me-to-a-long-random-value \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e PROXY_TOKEN=change-me-to-a-long-random-value \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### Запрос через прокси

Отправьте ваш токен API в заголовке `Authorization`.
Прокси отправляет этот заголовок в целевой API без изменений.

```bash
curl -s http://localhost:8080/api/v2/company \
  -H 'Authorization: Bearer t.XXXXXXXXXXXXXXXXXXXXXX'
```

С `PROXY_TOKEN` необходим ещё один заголовок:

```bash
curl -s http://localhost:8080/api/v2/company \
  -H 'X-Proxy-Token: change-me-to-a-long-random-value' \
  -H 'Authorization: Bearer t.XXXXXXXXXXXXXXXXXXXXXX'
```

## Конфигурация

| Переменная | Необходима | По умолчанию | Описание |
|---|---|---|---|
| `TARGET_URL` | Да | нет | Адрес целевого API. Формат — `https://host[:port]` без пути, без строки запроса и без фрагмента. |
| `CERT_PATH` | Нет | `/certs/client.pfx`, когда этот файл есть | Путь к файлу pfx в контейнере. |
| `CERT_PASSWORD` | Нет | нет | Пароль файла pfx. |
| `CERT_PASSWORD_FILE` | Нет | нет | Путь к файлу, который содержит пароль файла pfx. |
| `CERT_PEM_PATH` | Нет | нет | Путь к клиентскому сертификату в формате PEM. |
| `KEY_PEM_PATH` | Нет | нет | Путь к закрытому ключу в формате PEM. |
| `PORT` | Нет | `8080` | Порт для обычных HTTP-запросов вашего клиента. |
| `UPSTREAM_PORT` | Нет | `8443` | Порт loopback локального процесса stunnel. |
| `TLS_VERIFY` | Нет | `true` | Значение `false` отключает проверку цепочки сертификатов целевого API. `TLS_VERIFY` принимает те же значения, что и `LOG_BODIES` и `USER_AGENT_OVERRIDE`. Неверное значение останавливает контейнер при запуске. |
| `CA_BUNDLE_PATH` | Нет | встроенный бандл CA | Путь к файлу бандла CA в контейнере. Это значение заменяет встроенный бандл CA. |
| `EXTRA_CA_PATH` | Нет | нет | Путь к файлу с дополнительными сертификатами CA в контейнере. Прокси добавляет этот файл к текущему бандлу CA. |
| `PROXY_TOKEN` | Нет | нет | Общий секрет. Каждая вызывающая сторона должна отправлять это значение в заголовке `X-Proxy-Token`. Пустое значение останавливает контейнер при запуске. |
| `USER_AGENT_OVERRIDE` | Нет | `true` | Значение `false` отправляет заголовок `User-Agent` вашего клиента в целевой API без изменения. |
| `LOG_BODIES` | Нет | `true` | Значение `false` не даёт телам JSON попасть в строки логов. |
| `LOG_BODY_MAX_BYTES` | Нет | `10485760` | Порог в байтах, который решает, помещает ли прокси тело в буфер, чтобы записать его в лог. Это значение никогда не ограничивает сам проксируемый запрос. Детали — в разделе «Логи». |
| `LOG_LEVEL` | Нет | `INFO` | `DEBUG`, `INFO`, `WARNING` или `ERROR`, без учёта регистра. `WARN` тоже принимается как псевдоним `WARNING`. Неверное значение останавливает контейнер при запуске. Уровни и что добавляет `DEBUG` — в разделе «Логи». |
| `SERVICE_NAME` | Нет | `gost-mtls-proxy` | Значение поля `system` и поля `api` в каждой строке лога. |
| `ENV` | Нет | `dev` | Значение поля `env` в каждой строке лога. Прокси приводит это значение к нижнему регистру. |

Dockerfile также принимает два аргумента сборки: `CI_COMMIT` и `CI_REF`.
Сборка сохраняет оба значения в образе как переменные окружения.
Оба значения появляются в объекте `ci` каждой строки лога.

`PORT` и `UPSTREAM_PORT` принимают число от 0 до 65535.
`TLS_VERIFY`, `LOG_BODIES` и `USER_AGENT_OVERRIDE` принимают `true`, `1`, `yes`, `on`, `false`, `0`, `no` и `off`.
`LOG_LEVEL` принимает `DEBUG`, `INFO`, `WARNING` и `ERROR`, а также псевдоним `WARN` для `WARNING`.
`LOG_BODY_MAX_BYTES` принимает неотрицательное число байт.
Неверное значение останавливает контейнер с сообщением в stderr.

**NOTE: скрипт entrypoint всегда задаёт `UPSTREAM_HOST` значение `127.0.0.1`. Другое значение не действует.**

## Режимы клиентского сертификата

Прокси читает клиентский сертификат в одном из двух режимов.
Эти два режима взаимно исключают друг друга.
Контейнер с `CERT_PATH` и `CERT_PEM_PATH` одновременно останавливается с ошибкой.

### Режим pfx

Прокси читает файл PKCS#12:

- Задайте `CERT_PATH` путь к файлу pfx в контейнере.
- Как вариант, смонтируйте ваш файл pfx в `/certs/client.pfx`. Тогда скрипт находит его без `CERT_PATH`.
- Для файла pfx с паролем задайте `CERT_PASSWORD` или `CERT_PASSWORD_FILE`.
- `CERT_PASSWORD` и `CERT_PASSWORD_FILE` взаимно исключают друг друга. Оба вместе останавливают контейнер.

При каждом запуске скрипт преобразует файл pfx:

1. Скрипт читает файл pfx через провайдер gostprov.
2. Скрипт записывает закрытый ключ в `/run/gost-proxy/key.pem` с режимом 600.
3. Скрипт отделяет конечный сертификат от сертификатов CA.
4. Скрипт записывает конечный сертификат и сертификаты CA в `/run/gost-proxy/cert.pem`.
5. Скрипт передаёт оба файла в stunnel.

### Файлы pfx от CryptoPro

Инструменты CryptoPro записывают файл pfx с проприетарным шифрованием на основе пароля.
Идентификатор объекта — `1.2.840.113549.1.12.1.80`.
Стандартная сборка OpenSSL не может прочитать файл pfx такого типа.
BouncyCastle тоже не может его прочитать.

Поэтому образ содержит вторую сборку gost-engine в режиме провайдера.
Патч `patches/0001-cryptopro-keybag-empty-password.patch` добавляет поддержку пустого пароля.
Без этого патча файл pfx с пустым паролем останавливает преобразование.

Преобразование — единственный шаг с конфигурацией провайдера `/etc/ssl/gostprov.cnf`.
Engine и провайдер для одних и тех же алгоритмов в одной конфигурации ломают TLS-соединение stunnel.

### Режим pem

Прокси читает два отдельных файла:

- Задайте `CERT_PEM_PATH` путь к клиентскому сертификату.
- Задайте `KEY_PEM_PATH` путь к закрытому ключу.
- Обе переменные необходимы. Одна переменная без второй останавливает контейнер.

Скрипт пишет предупреждение в лог, когда файл ключа доступен для чтения всем.
Скрипт передаёт оба файла в stunnel без изменений.

## Хранилище доверенных сертификатов

Репозиторий содержит 7 сертификатов CA в `certs/`:

| Файл | Описание |
|---|---|
| `cryptopro-gost-root-ca.pem` | CryptoPro GOST Root CA. |
| `cryptopro-tls-ca.pem` | CryptoPro TLS CA. |
| `russian-trusted-root-ca.pem` | Russian Trusted Root CA, RSA, от Минцифры России. |
| `russian-trusted-sub-ca.pem` | Russian Trusted Sub CA 2022 года, RSA. |
| `russian-trusted-sub-ca-2024.pem` | Russian Trusted Sub CA 2024 года, RSA. |
| `russian-trusted-gost-root-ca.pem` | Минцифры России НУЦ корневой, ГОСТ. |
| `russian-trusted-gost-sub-ca.pem` | Минцифры России НУЦ подчиненный, ГОСТ. |

Сборка делает с этими файлами две вещи:

1. Сборка копирует каждый файл в системное хранилище доверенных сертификатов образа.
2. Сборка объединяет все файлы в `/etc/gost-proxy/ca-bundle.pem`. stunnel читает этот бандл.

### Как обновить сертификат CA

1. Замените файл PEM в `certs/`.
2. Соберите образ заново.

Обновления во время работы нет. Новый сертификат делает необходимым новый образ.

### Как использовать собственные сертификаты CA

Задайте `CA_BUNDLE_PATH` путь к вашему файлу бандла CA. Это значение заменяет встроенный бандл CA.
Задайте `EXTRA_CA_PATH` путь к файлу с дополнительными сертификатами CA. Прокси добавляет этот файл к текущему бандлу CA.
Обе переменные действуют при каждом запуске контейнера. Пересборка образа не нужна.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CA_BUNDLE_PATH=/etc/gost-proxy/custom-ca-bundle.pem \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/custom-ca-bundle.pem:/etc/gost-proxy/custom-ca-bundle.pem:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CA_BUNDLE_PATH=/etc/gost-proxy/custom-ca-bundle.pem \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/custom-ca-bundle.pem:/etc/gost-proxy/custom-ca-bundle.pem:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

## Проверка работоспособности

Прокси отвечает на `GET /healthz` в формате JSON:

- Прокси открывает TCP-соединение к локальному порту stunnel. Таймаут — 1 секунда.
- Успешное соединение даёт статус 200 и тело `{"status":"ok"}`.
- Неуспешное соединение даёт статус 503 и тело `{"status":"upstream unreachable"}`.
- `/healthz` не пишет строк в лог.
- `PROXY_TOKEN` не действует на `/healthz`.

Образ также содержит инструкцию `HEALTHCHECK` для этого пути.
Интервал — 30 секунд, таймаут — 5 секунд, начальный период — 10 секунд.
Число повторов — 3.

## Логи

Каждая строка прокси — это однострочный JSON в stdout.
stunnel пишет свою диагностику в stderr.

Каждая строка содержит эти поля:

| Поле | Описание |
|---|---|
| `message` | Текст события. |
| `level` | `DEBUG`, `INFO`, `WARNING` или `ERROR`. Строка ниже настроенного `LOG_LEVEL` не попадает в stdout. |
| `@timestamp` | Время с микросекундами и смещением UTC. |
| `logger` | `app.http`, `app.outbound` или `app.operations` для строк прокси. Скрипт entrypoint использует значение `entrypoint`. Мост slf4j передаёт запись http4s или cats-effect дальше с её собственным именем класса. Пример — `org.http4s.ember.server.EmberServerBuilder`. |
| `system` | Значение `SERVICE_NAME`. |
| `env` | Значение `ENV` в нижнем регистре. |
| `inst` | Имя хоста контейнера. |
| `ci` | Объект с `deployed_at`. Поля `commit` и `ref` есть только тогда, когда сборка задаёт `CI_COMMIT` и `CI_REF`. |
| `request_id` | Заголовок `X-Request-Id` вызывающей стороны, или новый UUID4, если он не был отправлен или был пустым. То же значение уходит в целевой API и возвращается в ответе. |
| `trace-id` | Значение `x-b3-traceid`, или часть trace из `traceparent`, или новое значение. |
| `span-id` | Новое значение из 16 символов для каждого запроса. |
| `method` | HTTP-метод запроса. |
| `route` | Путь запроса. |

Один запрос даёт шесть строк в этом порядке:

1. `Received http request`
2. `Method proxy was called for <path> with body '<type>'`
3. `Sending http request`
4. `Got http response with code=<code>`
5. `Method proxy returned response`
6. `api operation executed`

Последняя строка несёт поля `api`, `path`, `uri`, `ip`, `start_time`, `duration_ms`, `result` и `details`.
Объект `details` всегда содержит `http_code`.
Он также содержит `username`, `procedure_run_id`, `client_id` и `user_agent` из заголовков запроса
`X-Username`, `X-Procedure-Run-Id`, `X-Client-Id` и `User-Agent`.
Поле `user_agent` всегда содержит заголовок `User-Agent` вызывающей стороны. `USER_AGENT_OVERRIDE` не влияет на это поле.

### Уровни лога

`LOG_LEVEL` задаёт самый низкий уровень, который попадает в stdout. Порядок от низкого к высокому — `DEBUG`,
`INFO`, `WARNING` и `ERROR`.

- `LOG_LEVEL=DEBUG` не подавляет ничего.
- `LOG_LEVEL=INFO` подавляет строки `DEBUG`.
- `LOG_LEVEL=WARNING` подавляет строки `DEBUG` и `INFO`.
- `LOG_LEVEL=ERROR` подавляет строки `DEBUG`, `INFO` и `WARNING`. В stdout попадают только строки `ERROR`.

Поле `level` строки лога содержит `DEBUG`, `INFO`, `WARNING` или `ERROR`.
Запись библиотеки от моста slf4j подчиняется тому же порогу `LOG_LEVEL`, что и строки самого прокси.

### LOG_LEVEL=DEBUG

**WARNING: `LOG_LEVEL=DEBUG` ЗАПИСЫВАЕТ В ЛОГ ПОЛНОЕ ТЕЛО КАЖДОГО ЗАПРОСА И КАЖДОГО ОТВЕТА.
НЕ ИСПОЛЬЗУЙТЕ `LOG_LEVEL=DEBUG` В ПРОДОВОЙ СРЕДЕ.**

`LOG_BODY_MAX_BYTES` всё равно ограничивает этот режим. Меньшее значение ограничивает объём тела,
который может попасть в одну строку лога на `LOG_LEVEL=DEBUG`.

`LOG_LEVEL=DEBUG` добавляет три вещи поверх всех строк любого более низкого уровня:

- Строка 1 (`Received http request`) и строка 5 (`Method proxy returned response`) получают объект `debug`.
  Его поля — `http_version`, `query_params`, `outbound_uri` (полный URL цели, со строкой запроса), `remote_addr`,
  `request_content_type`, `request_content_length`, `response_content_type` и `response_content_length`.
  Поле отсутствует, а не равно null, если у прокси нет данных для него — например, `response_content_*` в строке 1,
  до ответа целевого API.
- Тело, попавшее в буфер, пишется в лог целиком: обрезка после 10000 символов и сводка при 256 КиБ, описанные ниже,
  не применяются. Тело типа `text` или `multipart` также пишется как текст, а не как пустая строка на любом другом
  уровне. Заголовки и ключи JSON ниже всё равно становятся `***`. Тело больше `LOG_BODY_MAX_BYTES` всё равно
  остаётся `unread` и никогда не попадает в буфер, на любом уровне.
- `GET /healthz`, молчащий на любом другом уровне, пишет `Received http request` и `api operation executed` на DEBUG.

`LOG_BODIES=false` всё равно сильнее, чем `LOG_LEVEL=DEBUG`: явный отказ от логирования тел действует на любом уровне.

### Что прокси не пишет в логи

- Значения заголовков `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` и
  `X-Proxy-Token` становятся `***` на любом `LOG_LEVEL`.
- Ключи JSON `token`, `raw_token`, `rawtoken`, `password`, `secret`, `apikey` и `api_key` становятся `***`
  на любом `LOG_LEVEL`.
- Прокси пишет только тела JSON, `text` и `multipart`. Тело другого типа никогда не попадает в лог.
  Тела `text` и `multipart` пишутся только на `LOG_LEVEL=DEBUG`; ниже этого уровня это пустая строка.
- Тело с `Content-Encoding`, отличным от `identity`, становится `binary`. Прокси никогда не читает его содержимое как JSON.
- `LOG_BODY_MAX_BYTES` задаёт порог в байтах, который решает, помещает ли прокси тело в буфер, чтобы записать его в лог.
  Тело в пределах порога попадает в буфер. Прокси определяет его тип по буферизованным байтам.
- Ниже `LOG_LEVEL=DEBUG` тело JSON больше 256 КиБ становится короткой сводкой, а более длинный текст обрезается
  после 10000 символов. На `LOG_LEVEL=DEBUG` тело JSON, попавшее в буфер, всегда пишется целиком.
- Тело больше порога `LOG_BODY_MAX_BYTES` никогда не попадает в буфер. Тело, отправленное с
  `Transfer-Encoding: chunked` и без `Content-Length`, тоже никогда не попадает в буфер. В обоих случаях
  тип в логе — `unread`, на любом `LOG_LEVEL`.
- `LOG_BODY_MAX_BYTES` решает только то, что прокси помещает в буфер для строки лога. Он никогда не
  ограничивает, не обрезает и не отбрасывает сам проксируемый запрос. Прокси передаёт целиком тело
  запроса в целевой API, независимо от порога. Прокси также возвращает целиком тело ответа вашему
  клиенту, независимо от порога.
- Значение `0` не помещает в буфер ни одного тела. Тогда каждое тело проходит без копирования данных
  (zero-copy). Его тип в логе — `unread`.
- `LOG_BODIES=false` заменяет текст каждого тела на `logging is disabled by flag`, на любом `LOG_LEVEL`.
- Строка запуска сообщает только `"proxy_token":true`. Значение `PROXY_TOKEN` никогда не попадает в лог.

### Пример строки

```json
{
    "@timestamp": "2026-08-07T09:14:22.203118+00:00",
    "ci": {
        "commit": "9f2c1ab3d4e5f60718293a4b5c6d7e8f90a1b2c3",
        "deployed_at": "2026-08-07T09:11:07.442015+00:00",
        "ref": "release/v1.0.0"
    },
    "env": "prod",
    "headers": [
        "Host: localhost:8080",
        "User-Agent: curl/8.5.0",
        "Accept: */*",
        "Authorization: ***"
    ],
    "inst": "8f1c0d2e4a7b",
    "level": "INFO",
    "logger": "app.http",
    "message": "Received http request",
    "method": "GET",
    "path": "/api/v2/company",
    "request_id": "6b1f4a2c-9d3e-4f57-8a01-2c3d4e5f6071",
    "route": "/api/v2/company",
    "span-id": "00f067aa0ba902b7",
    "system": "gost-mtls-proxy",
    "trace-id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "uri": "http://localhost:8080/api/v2/company"
}
```

## Безопасность

**WARNING: НЕ ОТКРЫВАЙТЕ ПОРТ ПРОКСИ В НЕДОВЕРЕННОЙ СЕТИ. КОНТЕЙНЕР СОДЕРЖИТ КЛИЕНТСКИЙ СЕРТИФИКАТ,
КОТОРЫЙ ИДЕНТИФИЦИРУЕТ ВАC. КАЖДАЯ ВЫЗЫВАЮЩАЯ СТОРОНА С ДОСТУПОМ К ПОРТУ МОЖЕТ
ДЕЙСТВОВАТЬ ОТ ЭТОГО ИМЕНИ.**

**WARNING: ЗАДАВАЙТЕ `TLS_VERIFY=false` ТОЛЬКО ДЛЯ СЕАНСА ОТЛАДКИ. ТОГДА ПРОКСИ ПРИНИМАЕТ ЛЮБОЙ
СЕРТИФИКАТ ОТ ЦЕЛЕВОГО API. ТОГДА АТАКУЮЩИЙ МОЖЕТ ЧИТАТЬ И МЕНЯТЬ ВАШ ТРАФИК.**

Контейнер пишет заметную строку `WARNING`, когда `TLS_VERIFY` равен `false`.

`PROXY_TOKEN` даёт второй уровень защиты.
`PROXY_TOKEN` не заменяет сетевой контроль.
Вызывающая сторона без правильного заголовка `X-Proxy-Token` получает статус 401 и тело `{"error":"unauthorized"}`.
Прокси сравнивает два значения функцией с постоянным временем работы.

Эти свойства образа снижают риск:

- Незашифрованный закрытый ключ существует только в `/run/gost-proxy`, с режимом 600 и владельцем `gostproxy`.
- Скрипт entrypoint удаляет `CERT_PASSWORD` и `CERT_PASSWORD_FILE` до запуска приложения.
- Контейнер запускается от пользователя `gostproxy` без прав root, uid 10001.
- Финальный образ не может установить пакет во время работы.
- Прокси удаляет заголовок `X-Proxy-Token` из каждого запроса к целевому API.

## Проблемы и решения

| Симптом | Причина | Действие |
|---|---|---|
| Целевой API отвечает 400 с `No required SSL certificate was sent`. | Целевой API не получил клиентский сертификат или не принял его. | Проверьте `CERT_PATH`. Убедитесь, что файл pfx содержит конечный сертификат и закрытый ключ. |
| Целевой API отвечает 401 с телом JSON. | Взаимное TLS-соединение корректно. Токен `Authorization` отсутствует или неверен. | Отправьте действительный токен в заголовке `Authorization`. |
| Целевой API отвечает 404 на каждый путь. | Заголовок `Host` не дошёл до целевого API. | Отправляйте ваши запросы через этот прокси. Прокси всегда задаёт заголовок `Host`. |
| Целевой API отвечает 200. | Запрос корректен и полон. | Действия не нужны. |
| Контейнер останавливается с `could not read ...: wrong CERT_PASSWORD, or unsupported pkcs12`. | Пароль неверен, или пароль содержит символы вне ASCII. | Задайте правильный пароль. Используйте только символы ASCII. |
| Контейнер останавливается с `it needs a password (CERT_PASSWORD)`. | У файла pfx есть пароль. В конфигурации пароля нет. | Задайте `CERT_PASSWORD` или `CERT_PASSWORD_FILE`. |
| Контейнер останавливается с `TARGET_URL must be https://host[:port] with no path`. | `TARGET_URL` содержит путь, строку запроса, фрагмент или неверную схему. | Задайте `TARGET_URL` значение `https://host` или `https://host:port`. |
| Контейнер останавливается с `no client certificate`. | Нет `CERT_PATH`, нет пары PEM и нет файла в `/certs/client.pfx`. | Смонтируйте ваш клиентский сертификат. Задайте правильные переменные. |
| Контейнер останавливается с `stunnel exited during startup`. | stunnel не может прочитать сертификат, ключ или бандл CA. | Прочитайте диагностику stunnel в stderr. |
| Контейнер останавливается с `stunnel is not listening on 127.0.0.1:8443 after 5s`. | Другой процесс в контейнере занимает этот порт. | Задайте `UPSTREAM_PORT` свободный порт. |
| Прокси отвечает 502 с `{"error":"upstream unreachable"}`. | Прокси не может подключиться к локальному порту stunnel. | Прочитайте диагностику stunnel в stderr. |
| Прокси отвечает 504 с `{"error":"upstream timeout"}`. | Целевой API не ответил вовремя. | Отправьте запрос заново. Проверьте сетевой маршрут до целевого API. |
| Прокси отвечает 401 с `{"error":"unauthorized"}`. | У `PROXY_TOKEN` есть значение. В запросе нет заголовка `X-Proxy-Token` или он неверен. | Отправьте правильное значение в заголовке `X-Proxy-Token`. |
| `/healthz` отвечает 503 с `{"status":"upstream unreachable"}`. | stunnel не принимает соединения на порту loopback. | Прочитайте диагностику stunnel в stderr. |

Ответы 400, 401 и 404 разделяют три разные неисправности.
Ответ 400 означает неисправность в клиентском сертификате.
Ответ 401 означает корректное взаимное TLS-соединение и неисправность в токене API.
Ответ 404 на каждый путь означает потерянный заголовок `Host`.

**NOTE: ответ 404 на каждый путь невозможен через этот прокси. Прокси всегда задаёт заголовок `Host`
целевого API.**

### Известное ограничение

Пароль pfx с символами вне ASCII не работает.
Это ограничение gost-engine, а не этого образа.
Текст ошибки — `could not read ...: wrong CERT_PASSWORD, or unsupported pkcs12`.

## Сборка из исходного кода

**CAUTION: СОБИРАЙТЕ ОБРАЗ КОМАНДОЙ `podman build --format docker`. ФОРМАТ OCI ПО УМОЛЧАНИЮ УДАЛЯЕТ
ИНСТРУКЦИЮ `HEALTHCHECK` И ПИШЕТ ТОЛЬКО ПРЕДУПРЕЖДЕНИЕ.**

```bash
# podman
podman build --format docker -t gost-mtls-proxy .

# docker
docker build -t gost-mtls-proxy .
```

Для сборки необходим сетевой доступ.
Сборка клонирует gost-engine с GitHub и загружает sbt.
Сборка также проверяет сумму SHA-256 архива sbt.

Сборка состоит из четырёх стадий:

1. `gostprov` собирает `gostprov.so` из gost-engine master на `ubuntu:26.04`. Минимум cmake у master —
   OpenSSL 3.4, и `ubuntu:26.04` содержит нужные заголовочные файлы.
2. `engine` собирает `gost.so` из gost-engine v3.0.3 на `ubuntu:24.04`.
3. `app` собирает assembly jar с помощью sbt. Затем jlink делает сокращённый JRE.
4. Финальная стадия объединяет все части на `ubuntu:24.04`.

### Тесты на вашей машине

```bash
sbt scalafmtCheckAll scalafmtSbtCheck test
```

Сборка использует Scala 3.8.4 и sbt 1.12.15.
Тесты не используют сертификат. Тесты не устанавливают соединение с целевым API.

### Реестры контейнеров

- `ghcr.io/nyorf/gost-mtls-proxy`
- `docker.io/nyorf/gost-mtls-proxy`

## Лицензия

Проект распространяется по лицензии Apache-2.0. Полный текст лицензии — в файле `LICENSE`.

Образ контейнера содержит стороннее ПО. Полный список — в файле `THIRD-PARTY-NOTICES.md`.

stunnel распространяется по лицензии GPL-2.0-or-later. Текст лицензии находится внутри образа, в `/usr/share/doc`.

Сертификаты CA в каталоге `certs/` — публичные сертификаты.
