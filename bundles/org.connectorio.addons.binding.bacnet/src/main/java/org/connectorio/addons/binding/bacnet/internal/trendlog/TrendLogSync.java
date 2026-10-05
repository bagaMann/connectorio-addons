/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.trendlog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.LogRecord;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.TrendLogs;
import org.connectorio.addons.binding.bacnet.internal.handler.channel.converter.CompositeConverter;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.persistence.ModifiablePersistenceService;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.PersistedItem;
import org.openhab.core.types.State;

/**
 * Guarded timestamp-based incremental import for one BACnet Trend Log.
 *
 * <p>The controller's buffer positions are intentionally never stored as a cursor. They shift when a circular
 * buffer wraps; the persistence timestamp is the durable cursor instead.</p>
 */
public class TrendLogSync {

  public static final int PAGE_SIZE = 10;
  public static final int MAX_RECORDS = 5000;

  private final ItemRegistry items;
  private final PersistenceServiceRegistry persistenceServices;

  public TrendLogSync(ItemRegistry items, PersistenceServiceRegistry persistenceServices) {
    this.items = items;
    this.persistenceServices = persistenceServices;
  }

  public Result sync(BacNetClient client, BacNetObject object, String itemName, String persistenceServiceId,
      ZoneId zone) throws ItemNotFoundException {
    List<String> messages = new ArrayList<>();
    Item item = items.getItem(itemName);
    PersistenceService persistence = persistenceServices.get(persistenceServiceId);
    if (persistence == null) {
      return Result.aborted(messages, "Persistence service not found: " + persistenceServiceId);
    }
    if (!(persistence instanceof ModifiablePersistenceService)) {
      return Result.aborted(messages,
          "Persistence service does not support historical timestamp queries and writes: " + persistenceServiceId);
    }
    ModifiablePersistenceService modifiablePersistence = (ModifiablePersistenceService) persistence;
    PersistedItem persistedItem = modifiablePersistence.persistedItem(item.getName(), null);
    if (persistedItem == null) {
      return Result.aborted(messages, "Sync aborted: no persisted cursor exists for " + item.getName()
          + ". Initialize this archive first with bacnet-trendlog import-all.");
    }
    Instant cursor = persistedItem.getTimestamp().toInstant();
    messages.add("Latest persisted cursor: " + persistedItem.getTimestamp() + " | " + persistedItem.getState());

    int recordCount = readUnsignedProperty(client, object, "record-count");
    int bufferSize = readUnsignedProperty(client, object, "buffer-size");
    if (recordCount < 1 || recordCount > bufferSize || recordCount > MAX_RECORDS) {
      return Result.aborted(messages,
          "Sync aborted: invalid record-count=" + recordCount + ", buffer-size=" + bufferSize + ".");
    }

    int newestPagePosition = Math.max(1, recordCount - PAGE_SIZE + 1);
    int newestPageCount = recordCount - newestPagePosition + 1;
    ReadRangeAck newestPage = TrendLogs.readByPosition(client, object, newestPagePosition, newestPageCount);
    if (newestPage.getItemCount().intValue() != newestPageCount) {
      return Result.aborted(messages, "Sync aborted before persistence: newest page requested=" + newestPageCount
          + " returned=" + newestPage.getItemCount() + ".");
    }
    Encodable newestEntry = newestPage.getItemData().get(newestPageCount - 1);
    if (!(newestEntry instanceof LogRecord)) {
      return Result.aborted(messages, "Sync aborted before persistence: unexpected newest record type "
          + newestEntry.getClass().getName() + ".");
    }
    Instant newestTimestamp = toZonedDateTime((LogRecord) newestEntry, zone).toInstant();
    String newestSignature = newestEntry.toString();
    messages.add("Newest controller timestamp: " + newestTimestamp.atZone(zone));
    if (cursor.isAfter(newestTimestamp)) {
      return Result.aborted(messages, "Sync aborted: the latest persisted timestamp is newer than the controller archive. "
          + "Use a dedicated archive Item without live channel updates or unrelated persistence records.");
    }
    if (cursor.equals(newestTimestamp)) {
      return Result.complete(messages, 0, 0,
          "Sync complete: no records are newer than the persisted cursor. Imported=0.");
    }

    List<ImportRecord> records = new ArrayList<>();
    Set<Instant> timestamps = new HashSet<>();
    boolean cursorReached = false;
    int skippedNonValues = 0;
    int position = newestPagePosition;
    ReadRangeAck page = newestPage;
    while (true) {
      for (Encodable entry : page.getItemData()) {
        if (!(entry instanceof LogRecord)) {
          return Result.aborted(messages,
              "Sync aborted before persistence: unexpected record type " + entry.getClass().getName());
        }
        LogRecord source = (LogRecord) entry;
        ZonedDateTime timestamp = toZonedDateTime(source, zone);
        if (!timestamp.toInstant().isAfter(cursor)) {
          cursorReached = true;
          continue;
        }
        if (source.isLogStatus() || source.isTimeChange() || source.isNull() || source.isBACnetError()) {
          skippedNonValues++;
          continue;
        }
        ImportRecord record = toImportRecord(entry, item, zone);
        if (!timestamps.add(record.timestamp.toInstant())) {
          return Result.aborted(messages,
              "Sync aborted before persistence: duplicate timestamp " + record.timestamp + ".");
        }
        records.add(record);
      }
      if (cursorReached || position == 1) {
        break;
      }
      int previousPosition = Math.max(1, position - PAGE_SIZE);
      int requested = position - previousPosition;
      position = previousPosition;
      page = TrendLogs.readByPosition(client, object, position, requested);
      if (page.getItemCount().intValue() != requested) {
        return Result.aborted(messages, "Sync aborted before persistence: position=" + position + " requested="
            + requested + " returned=" + page.getItemCount() + ".");
      }
    }

    records.sort(Comparator.comparing(record -> record.timestamp.toInstant()));
    ImportRecord previous = null;
    for (ImportRecord record : records) {
      if (previous != null && !record.timestamp.toInstant().isAfter(previous.timestamp.toInstant())) {
        return Result.aborted(messages,
            "Sync aborted before persistence: timestamps are not strictly increasing at " + record.timestamp + ".");
      }
      previous = record;
    }

    ReadRangeAck newestCheck = TrendLogs.readByPosition(client, object, recordCount, 1);
    if (newestCheck.getItemCount().intValue() != 1
        || !newestSignature.equals(newestCheck.getItemData().get(0).toString())) {
      return Result.aborted(messages,
          "Sync aborted before persistence: the circular buffer moved while it was being read.");
    }
    int finalRecordCount = readUnsignedProperty(client, object, "record-count");
    if (finalRecordCount != recordCount) {
      return Result.aborted(messages, "Sync aborted before persistence: record-count changed from " + recordCount
          + " to " + finalRecordCount + ".");
    }
    if (!cursorReached) {
      messages.add("Warning: persisted cursor is older than the retained controller buffer; an earlier history gap "
          + "may exist. All retained newer values will be imported.");
    }
    if (records.isEmpty()) {
      return Result.complete(messages, 0, skippedNonValues,
          "Sync complete: no new value records to import; skipped non-value records=" + skippedNonValues + ".");
    }

    messages.add("Snapshot is stable. Persisting " + records.size() + " new records to " + persistence.getId()
        + ".");
    int stored = 0;
    for (ImportRecord record : records) {
      try {
        modifiablePersistence.store(item, record.timestamp, record.state);
        stored++;
      } catch (RuntimeException e) {
        return Result.aborted(messages, "Persistence sync failed after " + stored + "/" + records.size()
            + " records: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
    return Result.complete(messages, stored, skippedNonValues,
        "Sync complete: Imported=" + stored + "; skippedNonValues=" + skippedNonValues + "; item="
            + item.getName() + "; service=" + persistence.getId() + "; controllerZone=" + zone + ".");
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

  public static final class Result {
    private final boolean complete;
    private final int imported;
    private final int skippedNonValues;
    private final String summary;
    private final List<String> messages;

    private Result(boolean complete, int imported, int skippedNonValues, String summary, List<String> messages) {
      this.complete = complete;
      this.imported = imported;
      this.skippedNonValues = skippedNonValues;
      this.summary = summary;
      this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    private static Result aborted(List<String> messages, String summary) {
      messages.add(summary);
      return new Result(false, 0, 0, summary, messages);
    }

    private static Result complete(List<String> messages, int imported, int skippedNonValues, String summary) {
      messages.add(summary);
      return new Result(true, imported, skippedNonValues, summary, messages);
    }

    public boolean isComplete() {
      return complete;
    }

    public int getImported() {
      return imported;
    }

    public int getSkippedNonValues() {
      return skippedNonValues;
    }

    public String getSummary() {
      return summary;
    }

    public List<String> getMessages() {
      return messages;
    }
  }
}
