# Точка восстановления BACnet: 3 октября 2026 года

Статус: запись Weekly_Schedule на Trend IQ3xcite подтверждена пользователем и захватом BACnet. Дополнительное тестирование продолжается. Это контрольная точка исходников, а не архив установленных JAR и конфигурации openHAB.

## Проверенная пара исходников

| Репозиторий | Рабочая ветка | Проверенный коммит |
| --- | --- | --- |
| bagaMann/connectorio-addons | cleanup/bacnet-thing-model | 6f74bd5fde3a8a1f1280879ab73ee0542bbcf5ef |
| bagaMann/bacnet4j-wrapper | cleanup/bacnet-weekly-schedule | ed4f35277e8047ff5ad4937c4446e455275ea346 |

В обоих репозиториях создана ветка checkpoint/bacnet-schedule-tested-2026-10-03 от соответствующего проверенного коммита. Поверх добавлен только этот документ. Для точного восстановления кода использовать SHA из таблицы; новые изменения делать в рабочих ветках.

Среда: openHAB 5.2.1; ConnectorIO 5.0.0-SNAPSHOT; wrapper 1.3.0-SNAPSHOT; BACnet4J 6.1.0-beta.2; версия экспортов OSGi 6.1.0.beta2. Установленные IP/MSTP bundles требуют BACnet4J >=6.1.0 и <7.0.0. Сборка API с 6.0.0 нарушает их разрешение.

## Исправления

- Команды WeeklySchedule проходят через Temporal Item, включая класс из openHAB fragment.
- String-команды Weekly_Schedule преобразуются в WeeklyScheduleType в обоих обработчиках.
- Семь DailySchedule передаются конструктору BACnetArray, без запрещённого add().
- Тип Boolean определяется по прочитанному расписанию; ON/OFF передаются как BACnet Boolean.
- Чтение поддерживает SequenceOf и его подкласс BACnetArray.
- Wrapper читает Max_APDU_Length_Accepted и Segmentation_Supported у Device и использует эти параметры для отправки всей недели.
- Для IQ3 запрещён неподходящий обход записью дней по array index; неделя отправляется целиком без индекса.
- При обновлении wrapper API зависимым bundles требуется refresh, чтобы освободить старые классы.

## Подтверждённая проверка

3 октября, около 23:22–23:24 по Москве, устройство 1001 / SCHEDULE.1:
- сообщило maxAPDU=1476 и segmentation=segmented-both;
- подтвердило четыре записи всей недели ответами SimpleACK;
- при последующем чтении вернуло изменённые временные точки;
- в записи использовались Boolean false / OFF.

Имена свидетельств: bacnet-schedule-check4.pcap и Вставленный текст(20261003-202424).txt. Эти файлы не включены в репозиторий. Переходы ON, большие сегментированные расписания и другие модели контроллеров этим захватом не проверены.

## Сборка для восстановления

Не сбрасывать текущую рабочую директорию. Получить код в отдельных worktree:

```bash
git -C /opt/bacnet-dev/bacnet4j-wrapper fetch origin
git -C /opt/bacnet-dev/bacnet4j-wrapper worktree add --detach /opt/bacnet-dev/restore-schedule-2026-10-03/wrapper ed4f35277e8047ff5ad4937c4446e455275ea346
git -C /opt/bacnet-dev/connectorio-addons fetch origin
git -C /opt/bacnet-dev/connectorio-addons worktree add --detach /opt/bacnet-dev/restore-schedule-2026-10-03/addons 6f74bd5fde3a8a1f1280879ab73ee0542bbcf5ef

cd /opt/bacnet-dev/restore-schedule-2026-10-03/wrapper
mvn -pl api -am install -DskipTests

cd /opt/bacnet-dev/restore-schedule-2026-10-03/addons
mvn -U -Popenhab -pl bundles/org.connectorio.addons.binding.bacnet -am package -DskipTests
```

Каждую следующую операцию выполнять после BUILD SUCCESS предыдущей. Профиль -Popenhab обязателен: подключает репозитории openHAB. Не менять версии BOM ради устранения ошибки отсутствующего репозитория.

Эта проверка относится к API wrapper; установленные IP/MSTP bundles в финальной проверке сохранены. Полная пересборка и замена всех модулей wrapper из этой ветки не проверена.

## Обновление в openHAB

Сначала bundle:list: номера ниже соответствуют проверенной установке, в другой установке могут отличаться.
- 265: Wrapper API
- 266: Wrapper IP
- 267: Wrapper MSTP
- 269: BACnet binding
- 276: Temporal
- 277: Temporal Item
- 278: Temporal OpenHAB fragment; Resolved для fragment нормально.

После успешной сборки восстановленных исходников:

```text
bundle:update 265 file:/opt/bacnet-dev/restore-schedule-2026-10-03/wrapper/api/target/api-1.3.0-SNAPSHOT.jar
bundle:update 269 file:/opt/bacnet-dev/restore-schedule-2026-10-03/addons/bundles/org.connectorio.addons.binding.bacnet/target/org.connectorio.addons.binding.bacnet-5.0.0-SNAPSHOT.jar
bundle:refresh 265
bundle:start 266
bundle:start 267
bundle:start 269
bundle:list | grep -i bacnet
```

265, 266, 267 и 269 должны быть Active. При Installed сначала читать bundle:diag, а не удалять Thing или очищать кэш. Существующие Temporal bundles должны сохранять исправление принятия WeeklyScheduleType; Temporal Item обновляется по номеру 277, не 276.

Критерий проверки после восстановления: Complete weekly schedule ... write acknowledged, затем повторное чтение изменённого расписания из контроллера. Обновление Item через autoupdate само по себе не доказывает запись.

## Дальнейшая работа

Перед сменой базовой ветки сравнивать весь diff, pom.xml, версии BACnet4J и OSGi-экспорты. Эта контрольная точка должна сохраняться; дальнейшие изменения не вносить в неё.
