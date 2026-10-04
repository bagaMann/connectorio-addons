# BACnet Trend Log: первый этап, чтение архива

Экспериментальная ветка `feature/bacnet-trendlog-read` в обоих репозиториях.
Основа — проверенная точка `checkpoint/bacnet-schedule-tested-2026-10-03`:
binding `6f74bd5`, wrapper `ed4f352` плюс документация восстановления.
Рабочие ветки и checkpoint не изменены.

## Что добавлено

В wrapper добавлен отдельный класс `TrendLogs`, в биндинг — отдельная консольная команда
`bacnet-trendlog`. Существующие классы, POM, версии библиотек, обработчики расписаний,
COV и polling не изменяются. Новые классы используют уже работающий клиент устройства;
второй BACnet сервер или UDP сокет не создаётся.

Команда вручную читает одну порцию `Log_Buffer` через `ReadRange` по позиции.
По умолчанию 5 записей, максимум 10. Перед запросом читаются APDU/segmentation capabilities
контроллера. Затем выводятся исходные BACnet timestamp, тип, значение и status flags,
флаги ответа и, если устройство вернуло, firstSequenceNumber.
Дополнительно читаются object-name, record-count, buffer-size и log-interval.
В BACnet log-interval выражается в сотых долях секунды.

Запросы выполняются только после ручного вызова. Нет WriteProperty, очистки журнала,
изменения Record_Count/Enable, автоматического опроса, обновления Items или записи persistence.
Чтение не опустошает архив.

Это ещё не поддержка графиков и импорта истории. Первый этап нужен для проверки протокола
на реальном IQ3 до разработки синхронизации и сохранения данных.

## Сборка в отдельных каталогах

Не переключать существующие рабочие каталоги и не выполнять reset/clean.
Каталоги ниже должны ещё не существовать:

```bash
cd /opt/bacnet-dev/bacnet4j-wrapper
git fetch origin feature/bacnet-trendlog-read
git worktree add --detach /opt/bacnet-dev/trendlog-test/wrapper FETCH_HEAD

cd /opt/bacnet-dev/connectorio-addons
git fetch origin feature/bacnet-trendlog-read
git worktree add --detach /opt/bacnet-dev/trendlog-test/addons FETCH_HEAD

cd /opt/bacnet-dev/trendlog-test/wrapper
mvn -pl api -am install -DskipTests
```

Только после `BUILD SUCCESS` wrapper:

```bash
cd /opt/bacnet-dev/trendlog-test/addons
mvn -U -Popenhab -pl bundles/org.connectorio.addons.binding.bacnet -am package -DskipTests
```

Сохранены BACnet4J `6.1.0-beta.2` и OSGi export `6.1.0.beta2`. Профиль `-Popenhab` обязателен.
Использовать существующий Java 21/Maven на Debian. IP/MSTP wrapper не пересобирать и не заменять.
4 октября 2026 года среда разработки подготовлена локально: Maven 3.9.9 и Eclipse Temurin
JDK 21.0.12.1, сетевой прокси Maven и системное доверенное хранилище сертификатов.
Сборка wrapper API и полный reactor биндинга с указанными выше параметрами завершились
`BUILD SUCCESS`. Тесты пропущены (`-DskipTests`); ручное чтение IQ3 подтверждено ниже.
Проверены наличие `TrendLogs.class` в API JAR и `TrendLogCommand.class` с OSGi декларацией
консольного сервиса в JAR биндинга. При полной компиляции исправлен generic-параметр
обработчика в новой команде (`BACnetDeviceHandler<?>`).

На свежей машине также нужны локальные зависимости IP/MSTP `1.3.0-SNAPSHOT` с поддержкой
BBMD. Для этой компиляции они установлены из проверенной ветки
`stable/bacnet-tested-2026-10-01` wrapper, commit `4afe087401b2237f77d79718730997c99bd907c7`,
в отдельном worktree командой `mvn -pl ip,mstp install -DskipTests` (без `-am`, чтобы
сохранить установленный новый API). Эти JAR используются только как зависимости сборки;
работающие IP/MSTP bundles на сервере пользователя не заменяются. IP из новой ветки,
унаследованный от checkpoint, не имеет нужных BBMD методов для компиляции биндинга.
В существующей рабочей среде пользователя нужные зависимости уже использовались.

## Обновление только после двух успешных сборок

Сначала в openHAB console проверить реальные IDs:

```text
bundle:list | grep -i bacnet
bundle:list | grep -i temporal
```

В проверенной установке: 265 API, 266 IP, 267 MSTP, 269 BACnet binding.
При других IDs заменить числа ниже. Обновлять только API и binding, а не IP/MSTP/Temporal:

```text
bundle:update 265 file:/opt/bacnet-dev/trendlog-test/wrapper/api/target/api-1.3.0-SNAPSHOT.jar
bundle:update 269 file:/opt/bacnet-dev/trendlog-test/addons/bundles/org.connectorio.addons.binding.bacnet/target/org.connectorio.addons.binding.bacnet-5.0.0-SNAPSHOT.jar
bundle:refresh 265 269
bundle:start 266 267 269
bundle:list | grep -i bacnet
bundle:list | grep -i temporal
```

Refresh может кратковременно перезапустить зависимые bundles. При `Installed`/ошибке:
`bundle:diag 265 266 267 269`; дальше не выполнять запросы до устранения ошибки.
Процедура восстановления проверенной версии —
[checkpoint](checkpoints/bacnet-schedule-2026-10-03.md).

## Первая проверка на IQ3

Команда принимает UID **устройства**, instance Trend Log, затем позицию и число записей:

```text
bacnet-trendlog read DEVICE_THING_UID INSTANCE [POSITION [COUNT]]
```

Для устройства 1001 и объекта TRENDLOG:4:

```text
bacnet-trendlog read co7io-bacnet:ip-device:192_168_11_255:0_1001 4 1 5
```

Номер 4 — пример из списка объектов YABE, не утверждение, что именно он является
«Давление ГВС». Выбрать instance нужного журнала в YABE и сравнить object-name.

Сравнить пять записей с YABE: время, тип Real/Boolean, значения, статус.
Потом проверить следующую порцию:

```text
bacnet-trendlog read co7io-bacnet:ip-device:192_168_11_255:0_1001 4 6 5
```

Время выводится как локальное время контроллера без преобразования в UTC. BACnet DateTime
не несёт часовой пояс; перед импортом нужно явно согласовать зону (ожидается Europe/Moscow)
и проверить часы IQ3. Служебные log-status/time-change не выдаются за измерения давления.
Пустой ответ допустим для пустого журнала или позиции вне текущего буфера.

Позиции в кольцевом буфере сдвигаются при поступлении новых записей. Поэтому эти ручные
вызовы не являются алгоритмом синхронизации; по позициям нельзя надёжно дедуплицировать архив.

После обновления отдельно проверить прежние чтения/COV и запись weekly schedule с readback.
Сборка и ручное чтение IQ3 подтверждены. Отсутствие регрессий расписаний/COV после
этого обновления пока не подтверждено отдельным тестом пользователя.

## Результаты на IQ3, 4 октября 2026

Пользователь собрал wrapper API `53149cd` и binding `f88e5e21` с `BUILD SUCCESS`,
обновил JAR из каталогов `trendlog-test` и успешно выполнил три чтения.
Устройство 1001, TRENDLOG:4, object-name — **Давление ХВС** (не ГВС).

- Позиция 1, count 5: 01.10.2026 00:25–00:45; Real, пять записей.
- Позиция 6, count 5, при более позднем вызове: 01.10.2026 01:00–01:20.
- Позиция 996, count 5: 04.10.2026 11:30–11:50; last-item=true.
- Все показанные status flags false.
- record-count=1000, buffer-size=1000, log-interval=0.
- firstSequenceNumber=null во всех ответах; нельзя рассчитывать на номер первой записи.
- Внутри показанных порций временной шаг 5 минут. Нулевой log-interval нельзя
  использовать как свидетельство отсутствия записей или как оценку фактического шага.
- more-items=false встречается и на промежуточных порциях; конец буфера определяется
  last-item, а не только more-items.

Разрыв между первой и второй порциями согласуется со сдвигом позиций заполненного
кольцевого буфера между вызовами. Позиции не являются устойчивыми идентификаторами.
При разработке импорта нужна обработка сдвига/перекрытия и проверка полноты данных.
Эти тесты подтверждают ручное чтение небольших порций; выгрузка всего буфера,
импорт persistence, дубли/переполнение и преобразование часового пояса ещё не проверены.
Записи не очищались и Items не обновлялись.

## Следующий этап после проверки чтения

Добавить ограниченную постраничную синхронизацию, устойчивый курсор и обработку переполнения,
защиту от дублей и смены времени. Затем сохранять измерения с исходными отметками времени
в отдельную историю Item через persistence с поддержкой архивной записи (планируется InfluxDB).
Текущие Items и rrd4j не используются как канал воспроизведения старых измерений.
InfluxDB, Items и графики этим этапом не устанавливаются и не создаются.
