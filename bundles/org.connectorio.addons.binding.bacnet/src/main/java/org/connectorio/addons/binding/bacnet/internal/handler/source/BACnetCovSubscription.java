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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.CovSubscription;
import com.serotonin.bacnet4j.type.Encodable;

/**
 * Owns one BACnet COV subscription without changing the existing polling path.
 *
 * <p>This helper deliberately contains only lifecycle operations. Scheduling renewal,
 * fallback polling and openHAB channel updates are handled by the binding layer.</p>
 */
public final class BACnetCovSubscription implements AutoCloseable {

  private final BacNetClient client;
  private final BacNetObject object;
  private final int lifetime;
  private final boolean confirmed;
  private final Consumer<Encodable> callback;
  private final AtomicReference<CovSubscription> subscription = new AtomicReference<>();

  public BACnetCovSubscription(BacNetClient client, BacNetObject object, int lifetime, boolean confirmed,
      Consumer<Encodable> callback) {
    this.client = Objects.requireNonNull(client, "client");
    this.object = Objects.requireNonNull(object, "object");
    if (lifetime <= 0) {
      throw new IllegalArgumentException("COV lifetime must be greater than zero");
    }
    this.lifetime = lifetime;
    this.confirmed = confirmed;
    this.callback = Objects.requireNonNull(callback, "callback");
  }

  /** Start the subscription once. */
  public synchronized void start() {
    CovSubscription current = subscription.get();
    if (current != null && !current.isClosed()) {
      return;
    }

    subscription.set(client.subscribeCov(object, lifetime, confirmed,
        (source, presentValue, timeRemaining) -> callback.accept(presentValue)));
  }

  /** Renew the existing remote subscription. */
  public synchronized void renew() {
    CovSubscription current = subscription.get();
    if (current == null || current.isClosed()) {
      throw new IllegalStateException("COV subscription is not active");
    }
    current.renew();
  }

  public boolean isActive() {
    CovSubscription current = subscription.get();
    return current != null && !current.isClosed();
  }

  public int getLifetime() {
    return lifetime;
  }

  @Override
  public synchronized void close() {
    CovSubscription current = subscription.getAndSet(null);
    if (current != null) {
      current.close();
    }
  }
}
