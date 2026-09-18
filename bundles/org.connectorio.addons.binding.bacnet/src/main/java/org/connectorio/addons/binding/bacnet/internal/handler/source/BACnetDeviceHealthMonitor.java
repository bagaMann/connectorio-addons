/*
 * Copyright (C) 2026 ConnectorIO sp. z o.o.
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.handler.source;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.BypassBacnetConverter;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically verifies communication with a BACnet device using a cheap ReadProperty request.
 *
 * <p>The monitor is deliberately independent from channel polling and COV. A device can therefore
 * be monitored in polling, COV-only, or mixed mode without changing channel update behavior.</p>
 */
public final class BACnetDeviceHealthMonitor implements AutoCloseable {

  private final Logger logger = LoggerFactory.getLogger(BACnetDeviceHealthMonitor.class);

  private final BacNetClient client;
  private final BacNetObject deviceObject;
  private final ScheduledExecutorService scheduler;
  private final int intervalSeconds;
  private final int failureThreshold;
  private final Runnable onlineCallback;
  private final Runnable offlineCallback;

  private final BypassBacnetConverter converter = new BypassBacnetConverter();

  private ScheduledFuture<?> task;
  private int consecutiveFailures;
  private boolean offline;
  private volatile boolean closed;

  public BACnetDeviceHealthMonitor(BacNetClient client, Device device, ScheduledExecutorService scheduler,
      int intervalSeconds, int failureThreshold, Runnable onlineCallback, Runnable offlineCallback) {
    if (intervalSeconds <= 0) {
      throw new IllegalArgumentException("Health check interval must be greater than zero");
    }
    if (failureThreshold <= 0) {
      throw new IllegalArgumentException("Health check failure threshold must be greater than zero");
    }

    this.client = client;
    this.deviceObject = new BacNetObject(device, device.getInstanceNumber(), Type.DEVICE);
    this.scheduler = scheduler;
    this.intervalSeconds = intervalSeconds;
    this.failureThreshold = failureThreshold;
    this.onlineCallback = onlineCallback;
    this.offlineCallback = offlineCallback;
  }

  public synchronized void start() {
    if (closed || task != null) {
      return;
    }
    task = scheduler.scheduleWithFixedDelay(this::check, 0, intervalSeconds, TimeUnit.SECONDS);
  }

  private void check() {
    if (closed) {
      return;
    }

    try {
      Encodable value = client.getObjectPropertyValue(
        deviceObject,
        PropertyIdentifier.objectName.toString(),
        converter
      );
      if (value == null) {
        throw new IllegalStateException("BACnet Device Object_Name returned null");
      }

      consecutiveFailures = 0;
      if (offline) {
        offline = false;
        logger.info("BACnet communication recovered for {}", deviceObject.getDevice());
        onlineCallback.run();
      }
    } catch (RuntimeException e) {
      int failures = ++consecutiveFailures;
      if (!offline && failures >= failureThreshold) {
        offline = true;
        logger.warn("BACnet communication lost for {} after {} consecutive health check failures",
          deviceObject.getDevice(), failures, e);
        offlineCallback.run();
      } else {
        logger.debug("BACnet health check failed for {} ({}/{})",
          deviceObject.getDevice(), failures, failureThreshold, e);
      }
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (task != null) {
      task.cancel(false);
      task = null;
    }
  }
}
