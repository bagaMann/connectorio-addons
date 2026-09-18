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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one COV subscription per BACnet object used by device channels. */
public final class BACnetCovManager implements AutoCloseable {
  private static final int MAX_RETRY_SECONDS = 30;
  private final Logger logger = LoggerFactory.getLogger(BACnetCovManager.class);
  private final BacNetClient client;
  private final ScheduledExecutorService scheduler;
  private final int lifetime;
  private final int renewalSeconds;
  private final int retrySeconds;
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private boolean closed;

  public BACnetCovManager(BacNetClient client, ScheduledExecutorService scheduler, int lifetime) {
    this.client = client;
    this.scheduler = scheduler;
    this.lifetime = lifetime > 0 ? lifetime : 300;
    this.renewalSeconds = Math.max(1, this.lifetime * 4 / 5);
    this.retrySeconds = Math.min(MAX_RETRY_SECONDS, Math.max(1, this.lifetime / 10));
  }

  public synchronized void add(BacNetObject object, Consumer<Encodable> presentValueCallback,
      Consumer<Encodable> statusFlagsCallback) {
    add(object, presentValueCallback, statusFlagsCallback, null, null);
  }

  public synchronized void add(BacNetObject object, Consumer<Encodable> presentValueCallback,
      Consumer<Encodable> statusFlagsCallback, Consumer<Encodable> eventStateCallback,
      Consumer<Encodable> outOfServiceCallback) {
    if (closed) return;
    String key = object.getType().name() + ":" + object.getId();
    Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(object));
    if (presentValueCallback != null) entry.presentValueCallbacks.add(presentValueCallback);
    if (statusFlagsCallback != null) entry.statusFlagsCallbacks.add(statusFlagsCallback);
    if (eventStateCallback != null) entry.eventStateCallbacks.add(eventStateCallback);
    if (outOfServiceCallback != null) entry.outOfServiceCallbacks.add(outOfServiceCallback);
  }

  public synchronized void start() {
    if (closed) return;
    for (Entry entry : entries.values()) start(entry);
  }

  private synchronized void start(Entry entry) {
    if (closed || entry.subscription != null) return;
    try {
      entry.subscription = new BACnetCovSubscription(client, entry.object, lifetime, false,
        value -> dispatch(entry.presentValueCallbacks, value),
        value -> dispatch(entry.statusFlagsCallbacks, value),
        value -> dispatch(entry.eventStateCallbacks, value),
        value -> dispatch(entry.outOfServiceCallbacks, value));
      entry.subscription.start();
      logger.debug("COV active for {} lifetime={}s callbacks[presentValue={}, statusFlags={}, eventState={}, outOfService={}]",
        entry.object, lifetime, entry.presentValueCallbacks.size(), entry.statusFlagsCallbacks.size(),
        entry.eventStateCallbacks.size(), entry.outOfServiceCallbacks.size());
      schedule(entry, renewalSeconds);
    } catch (RuntimeException e) {
      entry.subscription = null;
      logger.warn("Unable to start COV for {}; polling remains available and COV will be retried", entry.object, e);
      schedule(entry, retrySeconds);
    }
  }

  private void renew(Entry entry) {
    try {
      BACnetCovSubscription subscription;
      synchronized (this) {
        if (closed) return;
        subscription = entry.subscription;
      }
      if (subscription == null || !subscription.isActive()) {
        synchronized (this) { entry.subscription = null; }
        start(entry);
        return;
      }
      subscription.renew();
      logger.debug("COV renewed for {} lifetime={}s", entry.object, lifetime);
      schedule(entry, renewalSeconds);
    } catch (RuntimeException e) {
      logger.warn("Unable to renew COV for {}; polling remains available and COV will be retried", entry.object, e);
      schedule(entry, retrySeconds);
    }
  }

  private synchronized void schedule(Entry entry, int delaySeconds) {
    if (closed) return;
    if (entry.task != null) entry.task.cancel(false);
    entry.task = scheduler.schedule(() -> renew(entry), delaySeconds, TimeUnit.SECONDS);
  }

  private static void dispatch(List<Consumer<Encodable>> callbacks, Encodable value) {
    for (Consumer<Encodable> callback : new ArrayList<>(callbacks)) callback.accept(value);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (Entry entry : entries.values()) {
      if (entry.task != null) entry.task.cancel(false);
      if (entry.subscription != null) {
        try {
          entry.subscription.close();
        } catch (RuntimeException e) {
          logger.warn("Unable to cancel COV for {}", entry.object, e);
        }
      }
    }
    entries.clear();
  }

  private static final class Entry {
    private final BacNetObject object;
    private final List<Consumer<Encodable>> presentValueCallbacks = new ArrayList<>();
    private final List<Consumer<Encodable>> statusFlagsCallbacks = new ArrayList<>();
    private final List<Consumer<Encodable>> eventStateCallbacks = new ArrayList<>();
    private final List<Consumer<Encodable>> outOfServiceCallbacks = new ArrayList<>();
    private BACnetCovSubscription subscription;
    private ScheduledFuture<?> task;
    private Entry(BacNetObject object) { this.object = object; }
  }
}
