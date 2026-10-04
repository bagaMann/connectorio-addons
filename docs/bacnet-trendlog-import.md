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
