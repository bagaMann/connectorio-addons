/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.handler.trendlog;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.connectorio.addons.binding.bacnet.internal.config.TrendLogSyncConfig;
import org.connectorio.addons.binding.bacnet.internal.handler.object.BACnetDeviceHandler;
import org.connectorio.addons.binding.bacnet.internal.trendlog.TrendLogSync;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scheduled UI-configured synchronization of one BACnet Trend Log to persistence. */
public class BACnetTrendLogSyncHandler extends BaseThingHandler {

  private static final int STARTUP_DELAY_SECONDS = 15;

  private final Logger logger = LoggerFactory.getLogger(BACnetTrendLogSyncHandler.class);
  private final TrendLogSync trendLogSync;
  private final AtomicBoolean syncing = new AtomicBoolean();

  private volatile ScheduledFuture<?> scheduledSync;
  private volatile boolean disposed;

  public BACnetTrendLogSyncHandler(Thing thing, ItemRegistry items,
      PersistenceServiceRegistry persistenceServices) {
    super(thing);
    this.trendLogSync = new TrendLogSync(items, persistenceServices);
  }

  @Override
  public void initialize() {
    disposed = false;
    TrendLogSyncConfig config = getConfigAs(TrendLogSyncConfig.class);
    String configurationError = configurationError(config);
    if (configurationError != null) {
      updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, configurationError);
      updateState("status", configurationError);
      return;
    }
    if (!getDeviceHandler().isPresent()) {
      String message = "Link this Trend Log Sync Thing to a BACnet device.";
      updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, message);
      updateState("status", message);
      return;
    }

    updateStatus(ThingStatus.ONLINE);
    if (!config.enabled) {
      updateState("status", "Automatic synchronization is disabled.");
      updateState("last-error", "None");
      return;
    }

    updateState("status", "Automatic synchronization is enabled.");
    scheduledSync = scheduler.scheduleWithFixedDelay(this::sync, STARTUP_DELAY_SECONDS, config.intervalSeconds,
        TimeUnit.SECONDS);
  }

  private String configurationError(TrendLogSyncConfig config) {
    if (config.instance < 0 || config.instance > 4194302) {
      return "BACnet Trend Log instance must be 0..4194302.";
    }
    if (config.itemName == null || config.itemName.trim().isEmpty()) {
      return "Select the dedicated archive Item.";
    }
    if (config.persistenceService == null || config.persistenceService.trim().isEmpty()) {
      return "Select the persistence service.";
    }
    if (config.intervalSeconds < 60) {
      return "Synchronization interval must be at least 60 seconds.";
    }
    try {
      ZoneId.of(config.controllerZone);
    } catch (RuntimeException e) {
      return "Invalid controller time zone: " + config.controllerZone;
    }
    return null;
  }

  private void sync() {
    if (disposed || !syncing.compareAndSet(false, true)) {
      return;
    }
    try {
      TrendLogSyncConfig config = getConfigAs(TrendLogSyncConfig.class);
      Optional<BACnetDeviceHandler<?>> deviceHandler = getDeviceHandler();
      if (!deviceHandler.isPresent()) {
        fail("Link this Trend Log Sync Thing to a BACnet device.", ThingStatusDetail.BRIDGE_OFFLINE);
        return;
      }
      Device device = deviceHandler.get().getDevice();
      if (device == null) {
        fail("BACnet device is not initialized.", ThingStatusDetail.BRIDGE_OFFLINE);
        return;
      }
      BacNetClient client = deviceHandler.get().getClient().get(30, TimeUnit.SECONDS);
      BacNetObject object = new BacNetObject(device, config.instance, Type.TREND_LOG);
      TrendLogSync.Result result = trendLogSync.sync(client, object, config.itemName, config.persistenceService,
          ZoneId.of(config.controllerZone));
      updateState("last-sync", ZonedDateTime.now().toString());
      updateState("last-imported", new DecimalType(result.getImported()));
      updateState("status", result.getSummary());
      if (result.isComplete()) {
        updateState("last-error", "None");
        updateStatus(ThingStatus.ONLINE);
      } else {
        updateState("last-error", result.getSummary());
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, result.getSummary());
      }
    } catch (Exception e) {
      logger.warn("Automatic Trend Log synchronization failed for {}", getThing().getUID(), e);
      fail("Synchronization failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
          ThingStatusDetail.COMMUNICATION_ERROR);
    } finally {
      syncing.set(false);
    }
  }

  private Optional<BACnetDeviceHandler<?>> getDeviceHandler() {
    return Optional.ofNullable(getBridge()).map(Bridge::getHandler).filter(BACnetDeviceHandler.class::isInstance)
        .map(handler -> (BACnetDeviceHandler<?>) handler);
  }

  private void fail(String message, ThingStatusDetail detail) {
    updateState("last-sync", ZonedDateTime.now().toString());
    updateState("last-error", message);
    updateState("status", message);
    updateStatus(ThingStatus.OFFLINE, detail, message);
  }

  private void updateState(String channelId, String value) {
    updateState(new ChannelUID(getThing().getUID(), channelId), new StringType(value));
  }

  @Override
  public void dispose() {
    disposed = true;
    ScheduledFuture<?> task = scheduledSync;
    scheduledSync = null;
    if (task != null) {
      task.cancel(false);
    }
    super.dispose();
  }
}
