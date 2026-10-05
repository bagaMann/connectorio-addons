/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.config;

/** Configuration entered through the MainUI page of one BACnet Trend Log sync Thing. */
public class TrendLogSyncConfig {

  public int instance;
  public String itemName;
  public String persistenceService = "influxdb";
  public String controllerZone = "Europe/Moscow";
  public boolean enabled;
  public int intervalSeconds = 600;
}
