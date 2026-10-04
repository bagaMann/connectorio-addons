/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.shell;

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

/** Manual diagnostics only: does not update Items, persist data, or change the controller. */
@Component(immediate = true, service = ConsoleCommandExtension.class)
public class TrendLogCommand extends AbstractConsoleCommandExtension {

  private final ThingRegistry things;

  @Activate
  public TrendLogCommand(@Reference ThingRegistry things) {
    super("bacnet-trendlog", "Read a small page of BACnet Trend Log records.");
    this.things = things;
  }

  @Override
  public void execute(String[] args, Console console) {
    if (args.length < 3 || args.length > 5 || !"read".equals(args[0])) {
      getUsages().forEach(console::println);
      return;
    }
    try {
      int instance = Integer.parseInt(args[2]);
      int position = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      int count = args.length == 5 ? Integer.parseInt(args[4]) : 5;
      if (instance < 0 || instance > 4194302 || position < 1 || count < 1 || count > 10) {
        console.println("Instance must be 0..4194302, position >= 1, count 1..10.");
        return;
      }
      Thing thing = things.get(new ThingUID(args[1]));
      if (thing == null || !(thing.getHandler() instanceof BACnetDeviceHandler)) {
        console.println("Specify the BACnet DEVICE Thing UID, not a network bridge or an Item.");
        return;
      }
      BACnetDeviceHandler handler = (BACnetDeviceHandler) thing.getHandler();
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
    return Arrays.asList("bacnet-trendlog read DEVICE_THING_UID INSTANCE [POSITION [COUNT]] - read only; defaults 1, 5; max 10 records");
  }
}
