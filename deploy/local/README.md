# Подробности локального стенда

Для полного запуска из чистого клона используйте `bash up.sh` из корня репозитория.
Команды ниже описывают отдельные этапы и диагностику.


Кластер: Colima / k3s, ARM64, 6 CPU, 10 GiB RAM. Контекст: `colima`.
Приложение: namespace `petclinic`. Агент: namespace `datadog`.
Datadog Site: `datadoghq.eu` (EU1).

## Версии

- Исходники и образы Petclinic: `v3.4.1` / Docker tag `3.4.1`.
  У готовых образов есть ARM64; опубликованного Docker tag `4.0.1` нет.
- Datadog Helm chart: `3.260.0`.
- Java SDK: `v1.67.1`, внедряется Admission Controller через init container.
- PostgreSQL: `17.8`; JDBC-драйвер: `42.7.14` с проверкой SHA-256 при сборке.
- Apache Kafka: `3.9.1`, один KRaft-брокер, 3 партиции на топик.
- Файлы `chart/config/*.yml` скопированы из официального
  `spring-petclinic-microservices-config`, commit
  `323993ce2519c6d02df63e08bf4458d123d3b611` (Apache-2.0).
  Config Server читает локальную копию, поэтому запуск не зависит от GitHub.

## Запуск приложения

Из корня репозитория:

```sh
colima start --kubernetes --cpu 6 --memory 10
helm upgrade --install petclinic ./deploy/local/chart --kube-context colima \
  --namespace petclinic --create-namespace --reuse-values --wait --timeout 8m
kubectl --context colima -n petclinic port-forward service/api-gateway 8080:8080
```

Приложение: http://localhost:8080. Пробный составной запрос:
http://localhost:8080/api/gateway/owners/1.
Вызов проходит через gateway, customers-service и visits-service.

Развёрнуты config-server, discovery-server (Eureka), customers-service,
visits-service, vets-service и api-gateway. В текущем стенде три бизнес-сервиса
используют PostgreSQL (см. ниже). При первом запуске без PostgreSQL-профиля
они используют встроенную HSQLDB, данные которой сбрасываются при перезапуске.
GenAI, Admin Server, Zipkin, Prometheus и Grafana не запускаются.
Встроенный экспорт трейсов Micrometer отключён, чтобы использовать Java SDK Datadog.
Метрики Micrometer остаются включены.

## Подключение Datadog

Создайте API key в новом аккаунте EU1, затем выполните:

```sh
bash deploy/local/connect-datadog.sh
```

Скрипт запросит ключ со скрытым вводом. Ключ передаётся через stdin в Kubernetes
Secret `datadog/datadog-api-key`, не сохраняется в файле и не попадает в аргументы
процессов. Application key не нужен. Скрипт установит Agent и Cluster Agent,
затем перезапустит Petclinic с Java SDK.

После этого при следующих обновлениях приложения сохраняйте инструментацию:

```sh
helm upgrade petclinic ./deploy/local/chart --kube-context colima \
  --namespace petclinic --reuse-values --set datadogEnabled=true --wait --timeout 8m
```

## Что собирается

- Метрики Kubernetes, узла и контейнеров.
- Трейсы Java/Spring и runtime-метрики SDK (`jvm.*`).
- Метрики Micrometer с `/actuator/prometheus` четырёх прикладных сервисов.
  В Datadog они получают префикс `petclinic.`. Сбор всех доступных семейств
  предназначен для исследования покрытия; лимит — 2000 метрик на проверку.
- Логи шести Java-сервисов. Настроен вывод MDC-полей Datadog для связи с трейсами.
- Метрики PostgreSQL (`postgresql.*`), логи БД и Database Monitoring:
  статистика запросов, активность, образцы запросов и планы выполнения.

Общие теги: `env:local`, `version:3.4.1`, `service:petclinic-<имя>`.
Имя кластера: `petclinic-colima`.
Метрики OpenMetrics учитываются по правилам custom metrics Datadog.
Профилировщик в этом запуске не включён.

## PostgreSQL

В namespace `petclinic` работает StatefulSet `petclinic-postgres`, Service `postgres`
и PVC `data-petclinic-postgres-0` на 2 GiB. Один сервер обслуживает отдельные базы
`customers`, `vets`, `visits`; у каждой свой пользователь приложения.
Пароли сгенерированы и хранятся в Secret `petclinic/petclinic-postgres`.
Данные сохраняются при перезапуске сервисов и пода PostgreSQL. Это один экземпляр
БД без реплики и резервного копирования; удаление PVC удалит данные.
База доступна внутри кластера, порт на компьютере не публикуется.

Чтобы воспроизвести настройку после базового запуска и подключения Datadog:

```sh
bash deploy/local/enable-postgres.sh
```

Скрипт использует Docker context `colima`, создаёт Secrets, собирает три локальных
образа `petclinic-local/<service>:3.4.1-postgres1`, обновляет Datadog и Petclinic,
включая постоянную нагрузку. В готовые образы добавлены только JDBC-драйвер
и `application-postgresql.yml`; Maven и JDK на компьютере не нужны.
`imagePullPolicy: Never` предполагает образы в Docker локального узла Colima.
Для другого или многоузлового кластера потребуется загрузка образов в registry.

Схемы и исходные тестовые данные находятся в `chart/postgres/`. Они загружаются
один раз при создании пустого диска. `visits-v2.sql` переводит дату визита в
`TIMESTAMP(6)` под отображение Hibernate и сохраняет существующие даты.
В приложении включена проверка схемы (`ddl-auto: validate`), автоматическая
инициализация и пересоздание таблиц Spring отключены.

Для DBM включены `pg_stat_statements`, учёт времени I/O и идентификаторы запросов.
Пользователь `datadog` имеет `pg_monitor` и SELECT на прикладные таблицы,
но не является администратором. Функция EXPLAIN использует права вызывающего.
Пароль мониторинга скопирован в Secret `datadog/petclinic-postgres-monitor`
и передаётся Agent через переменную окружения. В трёх бизнес-сервисах включены
`DD_DBM_PROPAGATION_MODE=full` и инструментация JDBC DataSource для связи с APM.

В Datadog ищите `service:petclinic-postgres` в Database Monitoring,
а в Metrics Summary — префикс `postgresql.` и `env:local`.

Проверено 23 октября 2026: данные клиентов, ветеринаров и визитов совпали
с состоянием до переключения. Новый визит создан через HTTP API и прочитан
после перезапуска PostgreSQL, затем проверочная запись удалена.
Все восемь подов Petclinic (включая БД и генератор) готовы. Три проверки Postgres
собирают метрики и события DBM без ошибок; запросы приложений содержат
комментарии корреляции Datadog. Во время переключения и проверки перезапуска
были временные ошибки запросов; после восстановления счётчик ошибок стабилен.
В интерфейсе Datadog отображение DBM пока отдельно не проверялось.

SQL-проверки расширения, прав и типа даты находятся в `postgres/verify.sql`.
`enable-postgres.sh` запускает их после развёртывания.

## Kafka во взаимодействии приложения

Текущий сценарий:

```text
POST /api/visit/owners/6/pets/7/visits
  -> visits-service: PostgreSQL (визит + запись в outbox, одна транзакция)
  -> Kafka: petclinic.visit-created
  -> customers-service, группа petclinic-customers
  -> PostgreSQL: visit_notifications
GET /api/customer/owners/6/visit-notifications
```

Событие содержит `eventId` (UUID), `visitId`, `petId`, `visitDate` (`yyyy-MM-dd`).
Текст визита в Kafka не отправляется. Сервис клиентов сохраняет уведомление
и связывает его с владельцем через petId; отправки электронной почты нет.

Сначала визит и событие записываются в одной транзакции PostgreSQL.
После commit сервис отправляет событие в Kafka и ждёт подтверждения брокера.
Неотправленные события остаются в `visits.visit_outbox`; фоновая задача
повторяет отправку каждые 5 секунд. Удаление события происходит только после ACK.
Доставка как минимум однократная: уникальный eventId и `ON CONFLICT DO NOTHING`
защищают таблицу уведомлений от дублей. Ошибки БД приводят к повторной обработке,
а некорректные сообщения направляются в `petclinic.visit-created.DLT`.

Обычная отправка после commit сохраняет HTTP trace context; Datadog SDK
передаёт его через Kafka к потребителю. Фоновая повторная отправка создаёт
новый трейс; связь с исходным событием сохраняется через eventId в логах.
Чтобы сохранять исходную цепочку и при поздних повторах, потребуется сохранять
trace context в outbox.

Для развёртывания после настройки PostgreSQL и Datadog:

```sh
bash deploy/local/enable-kafka.sh
```

Скрипт запускает тесты и сборку Maven в Docker, собирает изменённые customers
и visits, добавляет таблицы миграциями, включает Kafka и Agent с JMX.
Бизнес-образы: `petclinic-local/{customers,visits}-service:3.4.1-kafka2`.
Vets остаётся на ранее собранном PostgreSQL-образе. Kafka доступна только внутри
кластера: `kafka.petclinic.svc.cluster.local:9092`, данные хранятся на PVC 2 GiB.
Это один брокер без репликации и SASL/TLS для локального исследования.
Срок хранения сообщений — 24 часа; лимит retention на партицию — 128 MiB.

В Datadog собираются JMX-метрики Kafka, consumer lag, логи и трассировка
producer/consumer. В двух приложениях включён `DD_DATA_STREAMS_ENABLED=true`.
В Micrometer добавлены счётчики публикаций, ошибок, обработки/дублей и размер outbox.
Ищите `service:petclinic-kafka`, топик `petclinic.visit-created`, метрики `kafka.*`
и `kafka.consumer_lag`.

Проверено: 11 Java-тестов прошли, HTTP-создание визита приводит к уведомлению,
повтор того же события оставляет одну строку, при остановке брокера событие
остаётся в outbox и доставляется после восстановления. Уникальное некорректное
сообщение дошло до `.DLT`, обработка остальных событий продолжилась. После
восстановления lag всех трёх партиций равен нулю, outbox пуст. В захваченных логах
14 обычных событий имеют одинаковый trace_id у отправителя и обработчика.
После обновления Agent проверка Kafka JMX собрала 162 сэмпла без ошибок,
проверка consumer — 9 сэмплов; trace writer отправил 12 трейсов / 44 спана
без ошибок. Это счётчики Agent на момент проверки, не число уникальных метрик.

Повторить сквозной тест (он кратковременно останавливает Kafka и создаёт визиты):

```sh
python3 deploy/local/kafka/smoke-test.py
```

Для обновления существующего полного стенда используйте `helm upgrade --reuse-values`.
`enable-postgres.sh` предназначен для PostgreSQL-этапа и переключит приложения
обратно на образы без Kafka; для полного стенда запускайте `enable-kafka.sh`.

## Проверка

```sh
helm lint deploy/local/chart
kubectl --context colima -n petclinic get pods
kubectl --context colima -n datadog get pods
kubectl --context colima -n datadog exec daemonset/datadog -c agent -- agent status
```

Через port-forward откройте `/`, `/api/customer/owners`, `/api/vet/vets`
и `/api/gateway/owners/6` (у его питомцев есть тестовые визиты).
Проверьте также `/actuator/prometheus` шлюза.
В Datadog найдите сервисы по `env:local`, метрики по `jvm.` и `petclinic.`,
логи по `service:petclinic-*`. Наличие конфигурации не подтверждает доставку:
её нужно проверить в Agent status и в самом Datadog после ввода ключа.

23 октября 2026: все шесть подов Ready; главная страница, списки клиентов и
ветеринаров, составной запрос владельца с визитами и endpoint Prometheus
вернули HTTP 200. У шлюза обнаружено 64 семейства метрик.
После подключения Datadog проверено состояние Agent 7.84.3: ключ EU1 принят,
от всех шести сервисов поступают трейсы; отправлено 137 трейсов / 503 спана.
Четыре проверки OpenMetrics собирают по 159–166 сэмплов за запуск без ошибок.
Agent сообщает об успешной отправке метрик и логов; ошибок HTTP и отправки
трейсов нет. Созданы 24 тестовых запроса. Это счётчики Agent на момент проверки,
а не количество уникальных метрик; счётчики логов включают все его источники.
Отображение данных в интерфейсе нового аккаунта отдельно не проверялось.

Для нагрузки в исходном проекте есть
`spring-petclinic-api-gateway/src/test/jmeter/petclinic_test_plan.jmx`.
Этот план требует JMeter Plugins (Ultimate Thread Group); в этот запуск
JMeter не установлен.

## Постоянная нагрузка

Включён Deployment `petclinic-load-generator`: пять последовательных GET-запросов
к шлюзу и пауза в одну секунду. Это до 5 запросов/с, фактическая частота зависит
от задержек. Маршруты: список клиентов, клиент №6, клиент №6 с визитами,
список ветеринаров и визиты питомца №7. При включённом Kafka-сценарии каждые
10 циклов также создаётся визит для питомца №7 — примерно один раз в 10 секунд.
Из-за этого списки визитов и уведомлений постепенно растут. Генератор использует
curl из уже скачанного образа Petclinic, без запуска дополнительной JVM.
Он продолжает работать после закрытия терминала и прекращает работу при остановке
Colima. Каждые 50 запросов пишет накопленные счётчики успехов и ошибок в stdout.

```sh
# Состояние и счётчики
kubectl --context colima -n petclinic logs deployment/petclinic-load-generator --tail=5

# Отключить нагрузку, сохранив инструментацию и остальные настройки
helm upgrade petclinic ./deploy/local/chart --kube-context colima \
  --namespace petclinic --reuse-values --set loadGenerator.enabled=false --wait

# Включить снова
helm upgrade petclinic ./deploy/local/chart --kube-context colima \
  --namespace petclinic --reuse-values --set loadGenerator.enabled=true --wait
```

## Остановка

```sh
colima stop
```

Это останавливает весь локальный кластер. Secret остаётся на диске Colima.
HTTP-интерфейс публикуется только через localhost port-forward.
