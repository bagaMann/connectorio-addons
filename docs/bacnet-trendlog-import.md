# BACnet Trend Log: ручной импорт одной страницы

Экспериментальная ветка `feature/bacnet-trendlog-import` продолжает проверенную ветку
`feature/bacnet-trendlog-read`. Первый этап импорта намеренно ограничен одной ручной
порцией до 10 записей. Автоматический опрос, курсор и полная выгрузка кольцевого буфера
пока не добавлены.

## Ограничения безопасности

- Команда только читает `Log_Buffer` контроллера и пишет результат в выбранную службу
  persistence openHAB. Контроллер, `Record_Count`, `Enable`, COV, polling и расписания
  не изменяются.
- Требуется точное завершающее слово `CONFIRM`.
- До первой записи проверяется вся полученная страница: тип каждой записи, полный timestamp,
  совместимость значения с Item и поддержка исторических timestamp службой persistence.
- Служебные `log-status`, `time-change`, `Null` и BACnet Error не импортируются: при их
  наличии прерывается вся страница.
- Текущее состояние Item не меняется. Записи получают исходное время контроллера,
  преобразованное из явно заданной временной зоны.

## Команда

```text
bacnet-trendlog import-page DEVICE_THING_UID INSTANCE POSITION COUNT ITEM SERVICE ZONE_ID CONFIRM
```

Для первого теста на уже проверенном `TREND_LOG:4` и тестовом Item:

```text
bacnet-trendlog import-page co7io-bacnet:ip-device:192_168_11_255:0_1001 4 996 5 InfluxDB_Connection_Test influxdb Europe/Moscow CONFIRM
```

Перед выполнением позицию следует ещё раз проверить командой `read`, поскольку IQ3 имеет
заполненный кольцевой буфер на 1000 записей и позиции сдвигаются. Для первой проверки
выбирать только пять последних обычных значений `Real` со всеми status flags `false`.

Успешный результат содержит `Imported=5`, имя Item, ID службы и обе временные зоны.
После этого в InfluxDB Data Explorer выбрать bucket `bacnet-trendlog`, measurement
`InfluxDB_Connection_Test` и диапазон не меньше семи дней. На графике должны остаться
тестовая точка `3.14` и появиться пять архивных точек с датами IQ3. Повторный импорт тех же
Item и timestamp предназначен для проверки отсутствия дублей в InfluxDB.

Если вывод содержит `Import aborted`, `does not support historical timestamps` или
`Trend Log read failed`, дальнейшие страницы не импортировать. Сначала сохранить вывод
консоли и проверить состояние bundles 265–269 и 326.

## Сборка и обновление

Wrapper API остаётся из `feature/bacnet-trendlog-read` (`53149cd`), работающие IP/MSTP
bundles не заменяются. В openHAB обновляется только binding:

```text
bundle:update 269 file:/opt/bacnet-dev/trendlog-import/addons/bundles/org.connectorio.addons.binding.bacnet/target/org.connectorio.addons.binding.bacnet-5.0.0-SNAPSHOT.jar
bundle:refresh 269
bundle:start 269
bundle:list | grep -i bacnet
bundle:diag 269
```

После первого импорта отдельно проверить обычное чтение BACnet, COV и weekly schedule.
Полную историю из 1000 записей этим этапом не импортировать.

## Полный ручной импорт после проверки одной страницы

Продолжение находится в изолированной ветке `feature/bacnet-trendlog-full-import`. Команда
`import-all` читает текущий `record-count` и требует, чтобы оператор явно указал это число.
Для IQ3 с проверенным заполненным буфером команда выглядит так:

```text
bacnet-trendlog import-all co7io-bacnet:ip-device:192_168_11_255:0_1001 4 1000 InfluxDB_Connection_Test influxdb Europe/Moscow CONFIRM
```

Чтение выполняется страницами по 10 записей. До первой записи в persistence команда:

- проверяет `record-count` и `buffer-size`;
- загружает и преобразует весь снимок в памяти;
- отклоняет служебные записи, несовместимые значения, неполные timestamp, дубликаты и
  нарушение хронологического порядка;
- повторно читает первую позицию и `record-count`, чтобы обнаружить сдвиг кольцевого буфера.

Только после сообщения `Snapshot is stable` начинается запись в InfluxDB. Повторный импорт
тех же Item и timestamp остаётся идемпотентным для InfluxDB. Команда ручная: фоновые задачи,
таймеры и автоматический курсор этим этапом не добавляются.

### Проверенный результат на IQ3

4 октября 2026 года полный импорт `TREND_LOG:4` с заполненным кольцевым буфером успешно
проверен на openHAB 5.2.1:

```text
Validated 1000/1000 records.
Snapshot is stable. Persisting 1000 records to influxdb.
Imported=1000; item=InfluxDB_Connection_Test; service=influxdb; controllerZone=Europe/Moscow; openHABZone=Europe/Moscow.
```

Во время проверки binding оставался `Active`; wrapper API/IP/MSTP не обновлялись. Текущее
состояние Item не менялось. Это контрольная точка до разработки автоматической синхронизации.

## Ручная инкрементальная синхронизация

Ветка `feature/bacnet-trendlog-sync` добавляет следующий изолированный этап — ручную команду,
которая использует последнюю запись Item в выбранной persistence-службе как курсор:

```text
bacnet-trendlog sync co7io-bacnet:ip-device:192_168_11_255:0_1001 4 InfluxDB_Connection_Test influxdb Europe/Moscow CONFIRM
```

Команда читает заполненный кольцевой буфер с конца страницами по 10 записей и останавливается,
когда достигает сохранённого timestamp. До записи новых значений она повторно проверяет самую
новую позицию и `record-count`, поэтому сдвиг буфера приводит к безопасному прерыванию.

Item для синхронизации должен быть выделенным архивным Item без привязанного live-канала и
без посторонних записей в той же measurement. Иначе последний timestamp в persistence может
оказаться новее архива контроллера; команда обнаружит это и завершится без записи. Если история
Item пуста, сначала обязательно выполнить проверенный `import-all`.

Служебные записи BACnet (`log-status`, `time-change`, `Null`, BACnet Error) не импортируются,
но учитываются при движении по буферу. Автоматический таймер этим этапом ещё не добавляется.

## MainUI: Trend Log Sync Thing

Ветка `feature/bacnet-trendlog-ui-sync` добавляет дочерний Thing `BACnet Trend Log Sync`.
Он создаётся в MainUI под соответствующим BACnet/IP или BACnet/MSTP device и содержит
отдельную настройку одного Trend Log. Обычная эксплуатация больше не требует консольной
команды: MainUI хранит instance, архивный Item, persistence-службу, часовую зону и интервал.

Для каждого Trend Log создать отдельный Thing и выбрать:

- `Trend Log instance` — BACnet instance журнала;
- `Archive Item` — выделенный Item без live-канала;
- `Persistence service` — `influxdb`;
- `Controller time zone` — `Europe/Moscow`;
- `Enable automatic synchronization` — выключено до первой инициализации;
- `Synchronization interval` — 600 секунд для журнала IQ3 с шагом пять минут.

Перед первым включением archive Item необходимо один раз заполнить существующей командой
`import-all`. Она остаётся диагностическим и восстановительным инструментом вместе с
`read`, `import-page` и ручным `sync`. После появления курсора в InfluxDB включённый Thing
сам запускает тот же guarded timestamp-based sync. При ошибке или сдвиге буфера записи в
persistence не выполняются.

Thing публикует UI-каналы `last-sync`, `last-imported`, `status` и `last-error`. Их можно
связать с обычными Text/Number Item и разместить на странице MainUI; сам архивный Item
открывается в Analyzer как график накопленной истории.
