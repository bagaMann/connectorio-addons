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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.LogRecord;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
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

  private static final int PAGE_SIZE = 10;
  private static final int MAX_FULL_IMPORT_RECORDS = 5000;

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
    boolean importAll = args.length > 0 && "import-all".equals(args[0]) && args.length == 8;
    if (!read && !importPage && !importAll) {
      getUsages().forEach(console::println);
      return;
    }
    try {
      int instance = Integer.parseInt(args[2]);
      if (instance < 0 || instance > 4194302) {
        console.println("Instance must be 0..4194302.");
        return;
      }
      if ((importPage && !"CONFIRM".equals(args[8])) || (importAll && !"CONFIRM".equals(args[7]))) {
        console.println("Import was not started. The final argument must be exactly CONFIRM.");
        return;
      }
      int expectedCount = importAll ? Integer.parseInt(args[3]) : 0;
      if (importAll && (expectedCount < 1 || expectedCount > MAX_FULL_IMPORT_RECORDS)) {
        console.println("Expected record count must be 1.." + MAX_FULL_IMPORT_RECORDS + ".");
        return;
      }
      int position = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      int count = read ? (args.length == 5 ? Integer.parseInt(args[4]) : 5)
          : importPage ? Integer.parseInt(args[4]) : 0;
      if (!importAll && (position < 1 || count < 1 || count > PAGE_SIZE)) {
        console.println("Position must be >= 1 and count 1.." + PAGE_SIZE + ".");
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
      if (importAll) {
        importAll(args, expectedCount, client, object, console);
        return;
      }
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
        "bacnet-trendlog import-page DEVICE_THING_UID INSTANCE POSITION COUNT ITEM SERVICE ZONE_ID CONFIRM - import one page; max 10 records",
        "bacnet-trendlog import-all DEVICE_THING_UID INSTANCE EXPECTED_COUNT ITEM SERVICE ZONE_ID CONFIRM - validate and import a stable full buffer snapshot");
  }

  private void importPage(String[] args, ReadRangeAck ack, Console console) throws ItemNotFoundException {
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

  private void importAll(String[] args, int expectedCount, BacNetClient client, BacNetObject object, Console console)
      throws ItemNotFoundException {
    Item item = items.getItem(args[4]);
    PersistenceService persistence = persistenceServices.get(args[5]);
    if (persistence == null) {
      console.println("Persistence service not found: " + args[5]);
      return;
    }
    if (!(persistence instanceof ModifiablePersistenceService)) {
      console.println("Persistence service does not support historical timestamps: " + args[5]);
      return;
    }
    ModifiablePersistenceService modifiablePersistence = (ModifiablePersistenceService) persistence;
    ZoneId zone = ZoneId.of(args[6]);

    int recordCount = readUnsignedProperty(client, object, "record-count");
    int bufferSize = readUnsignedProperty(client, object, "buffer-size");
    if (recordCount != expectedCount) {
      console.println("Import aborted before reading: expected record-count=" + expectedCount + ", controller reports "
          + recordCount + ". Re-run the command with the current explicit count.");
      return;
    }
    if (recordCount > bufferSize || recordCount > MAX_FULL_IMPORT_RECORDS) {
      console.println("Import aborted: invalid record-count=" + recordCount + ", buffer-size=" + bufferSize + ".");
      return;
    }

    console.println("Reading stable snapshot of " + recordCount + " records from " + object + " in pages of "
        + PAGE_SIZE + ". Nothing will be persisted until the complete snapshot is validated.");
    List<ImportRecord> records = new ArrayList<>(recordCount);
    Set<java.time.Instant> timestamps = new HashSet<>(recordCount);
    ImportRecord previous = null;
    for (int position = 1; position <= recordCount; position += PAGE_SIZE) {
      int requested = Math.min(PAGE_SIZE, recordCount - position + 1);
      ReadRangeAck ack = TrendLogs.readByPosition(client, object, position, requested);
      if (ack.getItemCount().intValue() != requested) {
        console.println("Import aborted before persistence: position=" + position + " requested=" + requested
            + " returned=" + ack.getItemCount() + ".");
        return;
      }
      for (Encodable entry : ack.getItemData()) {
        ImportRecord record = toImportRecord(entry, item, zone);
        if (!timestamps.add(record.timestamp.toInstant())) {
          console.println("Import aborted before persistence: duplicate timestamp " + record.timestamp + ".");
          return;
        }
        if (previous != null && !record.timestamp.toInstant().isAfter(previous.timestamp.toInstant())) {
          console.println("Import aborted before persistence: timestamps are not strictly increasing at "
              + record.timestamp + ". The circular buffer may have moved.");
          return;
        }
        records.add(record);
        previous = record;
      }
      if (records.size() % 100 == 0 || records.size() == recordCount) {
        console.println("Validated " + records.size() + "/" + recordCount + " records.");
      }
    }
    if (records.size() != recordCount) {
      console.println("Import aborted before persistence: expected " + recordCount + " records, validated "
          + records.size() + ".");
      return;
    }

    ReadRangeAck firstCheck = TrendLogs.readByPosition(client, object, 1, 1);
    if (firstCheck.getItemCount().intValue() != 1) {
      console.println("Import aborted before persistence: could not re-check the first buffer record.");
      return;
    }
    ImportRecord firstNow = toImportRecord(firstCheck.getItemData().get(0), item, zone);
    ImportRecord firstSnapshot = records.get(0);
    if (!firstSnapshot.timestamp.toInstant().equals(firstNow.timestamp.toInstant())
        || !firstSnapshot.state.equals(firstNow.state)) {
      console.println("Import aborted before persistence: the circular buffer moved while it was being read.");
      return;
    }
    int finalRecordCount = readUnsignedProperty(client, object, "record-count");
    if (finalRecordCount != recordCount) {
      console.println("Import aborted before persistence: record-count changed from " + recordCount + " to "
          + finalRecordCount + ".");
      return;
    }

    console.println("Snapshot is stable. Persisting " + records.size() + " records to " + persistence.getId() + ".");
    int stored = 0;
    for (ImportRecord record : records) {
      try {
        modifiablePersistence.store(item, record.timestamp, record.state);
        stored++;
      } catch (RuntimeException e) {
        console.println("Persistence import failed after " + stored + "/" + records.size() + " records: "
            + e.getClass().getSimpleName() + ": " + e.getMessage());
        return;
      }
    }
    console.println("Imported=" + stored + "; item=" + item.getName() + "; service=" + persistence.getId()
        + "; controllerZone=" + zone + "; openHABZone=" + openHABZone + ".");
    console.println("The Item's current state was not changed. Re-importing the same timestamps is intended to be idempotent in InfluxDB.");
  }

  private int readUnsignedProperty(BacNetClient client, BacNetObject object, String property) {
    Encodable value = client.getObjectPropertyValue(object, property, raw -> raw);
    if (!(value instanceof UnsignedInteger)) {
      throw new IllegalArgumentException(property + " is not an UnsignedInteger: " + value);
    }
    return ((UnsignedInteger) value).intValue();
  }

  private ImportRecord toImportRecord(Encodable entry, Item item, ZoneId zone) {
    if (!(entry instanceof LogRecord)) {
      throw new IllegalArgumentException("unexpected record type " + entry.getClass().getName());
    }
    LogRecord record = (LogRecord) entry;
    if (record.isLogStatus() || record.isTimeChange() || record.isNull() || record.isBACnetError()) {
      throw new IllegalArgumentException("non-value record at " + record.getTimestamp());
    }
    State state = CompositeConverter.INSTANCE.fromBacNet(record.getChoice());
    if (state == null || item.getAcceptedDataTypes().stream().noneMatch(type -> type.isInstance(state))) {
      throw new IllegalArgumentException(item.getName() + " does not accept "
          + (state == null ? record.getChoice().getClass().getSimpleName() : state.getClass().getSimpleName()));
    }
    return new ImportRecord(toZonedDateTime(record, zone), state);
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
