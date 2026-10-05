/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.shell;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.serotonin.bacnet4j.type.Encodable;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.connectorio.addons.binding.bacnet.internal.handler.object.BACnetDeviceHandler;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Read-only BACnet Schedule diagnostics. This command never writes to the controller.
 */
@Component(immediate = true, service = ConsoleCommandExtension.class)
public class ScheduleDiagnosticCommand extends AbstractConsoleCommandExtension {

  private static final List<String> PROPERTIES = Arrays.asList(
      "object-name",
      "present-value",
      "weekly-schedule",
      "schedule-default",
      "list-of-object-property-references",
      "priority-for-writing",
      "effective-period",
      "status-flags",
      "reliability",
      "out-of-service");

  private final ThingRegistry things;

  @Activate
  public ScheduleDiagnosticCommand(@Reference ThingRegistry things) {
    super("bacnet-schedule", "Read BACnet Schedule properties without changing the controller.");
    this.things = things;
  }

  @Override
  public void execute(String[] args, Console console) {
    if (args.length != 3 || !"read".equals(args[0])) {
      getUsages().forEach(console::println);
      return;
    }

    try {
      int instance = Integer.parseInt(args[2]);
      if (instance < 0 || instance > 4194302) {
        console.println("Schedule instance must be 0..4194302.");
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
      BacNetObject object = new BacNetObject(device, instance, Type.SCHEDULE);

      console.println("Read-only BACnet Schedule diagnostics for " + object);
      console.println("No WriteProperty request will be sent.");

      for (String property : PROPERTIES) {
        try {
          Encodable value = client.getObjectPropertyValue(object, property, raw -> raw);
          String type = value == null ? "null" : value.getClass().getName();
          console.println(property + " | " + type + " | " + value);
        } catch (RuntimeException e) {
          console.println(property + " | unavailable | " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      console.println("Schedule diagnostics interrupted.");
    } catch (Exception e) {
      console.println("Schedule diagnostics failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  @Override
  public List<String> getUsages() {
    return Arrays.asList(
        "bacnet-schedule read DEVICE_THING_UID INSTANCE - read Schedule properties only; never writes to BACnet");
  }
}
