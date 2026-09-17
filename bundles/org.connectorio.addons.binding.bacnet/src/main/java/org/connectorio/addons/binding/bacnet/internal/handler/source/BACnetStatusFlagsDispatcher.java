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
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import java.util.Objects;
import java.util.function.Consumer;

/** Splits BACnet Status_Flags into the four standard boolean states. */
public final class BACnetStatusFlagsDispatcher implements Consumer<Encodable> {
  private final Consumer<Boolean> inAlarm;
  private final Consumer<Boolean> fault;
  private final Consumer<Boolean> overridden;
  private final Consumer<Boolean> outOfService;

  public BACnetStatusFlagsDispatcher(Consumer<Boolean> inAlarm, Consumer<Boolean> fault,
      Consumer<Boolean> overridden, Consumer<Boolean> outOfService) {
    this.inAlarm = Objects.requireNonNull(inAlarm, "inAlarm");
    this.fault = Objects.requireNonNull(fault, "fault");
    this.overridden = Objects.requireNonNull(overridden, "overridden");
    this.outOfService = Objects.requireNonNull(outOfService, "outOfService");
  }

  @Override
  public void accept(Encodable value) {
    if (!(value instanceof StatusFlags)) {
      return;
    }
    StatusFlags flags = (StatusFlags) value;
    inAlarm.accept(flags.isInAlarm());
    fault.accept(flags.isFault());
    overridden.accept(flags.isOverridden());
    outOfService.accept(flags.isOutOfService());
  }
}
