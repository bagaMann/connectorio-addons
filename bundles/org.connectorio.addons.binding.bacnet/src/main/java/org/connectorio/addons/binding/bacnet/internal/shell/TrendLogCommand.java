/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.shell;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.LogRecord;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.TrendLogs;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.connectorio.addons.binding.bacnet.internal.handler.channel.converter.CompositeConverter;
import org.connectorio.addons.binding.bacnet.internal.handler.object.BACnetDeviceHandler;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.persistence.ModifiablePersistenceService;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.State;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/** Manual Trend Log diagnostics and explicitly confirmed persistence import; never changes the controller. */
@Component(immediate = true, service = ConsoleCommandExtension.class)
public class TrendLogCommand extends AbstractConsoleCommandExtension {

  private final ThingRegistry things;
  private final ItemRegistry items;
  private final PersistenceServiceRegistry persistenceServices;
  private final ZoneId openHABZone;

  @Activate
  public TrendLogCommand(@Reference ThingRegistry things, @Reference ItemRegistry items,
      @Reference PersistenceServiceRegistry persistenceServices, @Reference TimeZoneProvider timeZoneProvider) {
    super("bacnet-trendlog", "Read or manually import a small page of BACnet Trend Log records.");
    this.things = things;
    this.items = items;
    this.persistenceServices = persistenceServices;
    this.openHABZone = timeZoneProvider.getTimeZone();
  }

  @Override
  public void execute(String[] args, Console console) {
    boolean read = args.length > 0 && "read".equals(args[0]) && args.length >= 3 && args.length <= 5;
    boolean importPage = args.length > 0 && "import-page".equals(args[0]) && args.length == 9;
    if (!read && !importPage) {
      getUsages().forEach(console::println);
      return;
    }
    try {
      int instance = Integer.parseInt(args[2]);
      int position = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      int count = read ? (args.length == 5 ? Integer.parseInt(args[4]) : 5) : Integer.parseInt(args[4]);
      if (instance < 0 || instance > 4194302 || position < 1 || count < 1 || count > 10) {
        console.println("Instance must be 0..4194302, position >= 1, count 1..10.");
        return;
      }
      Thing thing = things.get(new ThingUID(args[1]));
      if (thing == null || !(thing.getHandler() instanceof BACnetDeviceHandler)) {
        console.println("Specify the BACnet DEVICE Thing UID, not a network bridge or an Item.");
        return;
      }
      BACnetDeviceHandler<?> handler = (BACnetDeviceHandler<?>) thing.getHandler();
      Device device = handler.getDevice();
      if (device == null) {
        console.println("BACnet device is not initialized.");
        return;
      }
      BacNetClient client = handler.getClient().get(30, TimeUnit.SECONDS);
      BacNetObject object = new BacNetObject(device, instance, Type.TREND_LOG);
      console.println("Reading " + object + "; buffer position=" + position + ", count=" + count);
      // ReadRange is executed first, so optional metadata cannot prevent the archive read.
      ReadRangeAck ack = TrendLogs.readByPosition(client, object, position, count);
      console.println("Returned=" + ack.getItemCount() + "; flags=" + ack.getResultFlags()
          + "; firstSequenceNumber=" + ack.getFirstSequenceNumber());
      console.println("timestamp (controller local time) | type | value | status");
      for (Encodable entry : ack.getItemData()) {
        if (entry instanceof LogRecord) {
          LogRecord record = (LogRecord) entry;
          Encodable value = record.getChoice();
          String type = record.isLogStatus() ? "log-status" : record.isTimeChange() ? "time-change"
              : value.getClass().getSimpleName();
          console.println(record.getTimestamp() + " | " + type + " | " + value + " | " + record.getStatusFlags());
        } else {
          console.println("Unexpected record type: " + entry.getClass().getName() + " | " + entry);
        }
      }
      if (importPage) {
        importPage(args, ack, console);
        return;
      }
      for (String property : Arrays.asList("object-name", "record-count", "buffer-size", "log-interval")) {
        try {
          Encodable value = client.getObjectPropertyValue(object, property, raw -> raw);
          console.println(property + "=" + value);
        } catch (RuntimeException e) {
          console.println(property + " unavailable: " + e.getMessage());
        }
      }
      console.println("Read-only preview. Buffer positions may shift; no data was imported into persistence.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      console.println("Trend Log read interrupted.");
    } catch (Exception e) {
      console.println("Trend Log read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (NoClassDefFoundError | NoSuchMethodError e) {
      console.println("Trend Log API unavailable. Update the wrapper API from the same feature branch and refresh bundles.");
    }
  }

  @Override
  public List<String> getUsages() {
    return Arrays.asList(
        "bacnet-trendlog read DEVICE_THING_UID INSTANCE [POSITION [COUNT]] - read only; defaults 1, 5; max 10 records",
        "bacnet-trendlog import-page DEVICE_THING_UID INSTANCE POSITION COUNT ITEM SERVICE ZONE_ID CONFIRM - import one page; max 10 records");
  }

  private void importPage(String[] args, ReadRangeAck ack, Console console) throws ItemNotFoundException {
    if (!"CONFIRM".equals(args[8])) {
      console.println("Import was not started. The final argument must be exactly CONFIRM.");
      return;
    }
    Item item = items.getItem(args[5]);
    PersistenceService persistence = persistenceServices.get(args[6]);
    if (persistence == null) {
      console.println("Persistence service not found: " + args[6]);
      return;
    }
    if (!(persistence instanceof ModifiablePersistenceService)) {
      console.println("Persistence service does not support historical timestamps: " + args[6]);
      return;
    }
    ModifiablePersistenceService modifiablePersistence = (ModifiablePersistenceService) persistence;
    ZoneId zone = ZoneId.of(args[7]);
    List<ImportRecord> records = new ArrayList<>();
    for (Encodable entry : ack.getItemData()) {
      if (!(entry instanceof LogRecord)) {
        console.println("Import aborted: unexpected record type " + entry.getClass().getName());
        return;
      }
      LogRecord record = (LogRecord) entry;
      if (record.isLogStatus() || record.isTimeChange() || record.isNull() || record.isBACnetError()) {
        console.println("Import aborted: page contains a non-value record at " + record.getTimestamp());
        return;
      }
      State state = CompositeConverter.INSTANCE.fromBacNet(record.getChoice());
      if (state == null || item.getAcceptedDataTypes().stream().noneMatch(type -> type.isInstance(state))) {
        console.println("Import aborted: " + item.getName() + " does not accept "
            + (state == null ? record.getChoice().getClass().getSimpleName() : state.getClass().getSimpleName()));
        return;
      }
      records.add(new ImportRecord(toZonedDateTime(record, zone), state));
    }
    int stored = 0;
    for (ImportRecord record : records) {
      modifiablePersistence.store(item, record.timestamp, record.state);
      stored++;
    }
    console.println("Imported=" + stored + "; item=" + item.getName() + "; service=" + persistence.getId()
        + "; controllerZone=" + zone + "; openHABZone=" + openHABZone + ".");
    console.println("The Item's current state was not changed. Re-importing the same timestamps is intended to be idempotent in InfluxDB.");
  }

  private ZonedDateTime toZonedDateTime(LogRecord record, ZoneId zone) {
    com.serotonin.bacnet4j.type.constructed.DateTime timestamp = record.getTimestamp();
    com.serotonin.bacnet4j.type.primitive.Date date = timestamp.getDate();
    com.serotonin.bacnet4j.type.primitive.Time time = timestamp.getTime();
    if (!date.isSpecific() || !time.isFullySpecified()) {
      throw new IllegalArgumentException("Trend Log timestamp is not fully specified: " + timestamp);
    }
    LocalDate localDate = LocalDate.of(date.getCenturyYear(), date.getMonth().ordinal() + 1, date.getDay());
    LocalTime localTime = LocalTime.of(time.getHour(), time.getMinute(), time.getSecond(),
        time.getHundredth() * 10_000_000);
    return ZonedDateTime.of(LocalDateTime.of(localDate, localTime), zone);
  }

  private static final class ImportRecord {
    private final ZonedDateTime timestamp;
    private final State state;

    private ImportRecord(ZonedDateTime timestamp, State state) {
      this.timestamp = timestamp;
      this.state = state;
    }
  }
}
