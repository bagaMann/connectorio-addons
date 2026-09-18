/*
 * Copyright (C) 2019-2021 ConnectorIO sp. z o.o.
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 *     https://www.gnu.org/licenses/gpl-3.0.txt
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal.handler.object;

import com.serotonin.bacnet4j.obj.DeviceObject;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Null;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.JavaToBacNetConverter;
import org.code_house.bacnet4j.wrapper.api.Priorities;
import org.code_house.bacnet4j.wrapper.api.Priority;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.connectorio.addons.binding.bacnet.internal.BACnetBindingConstants;
import org.connectorio.addons.binding.bacnet.internal.command.PrioritizedCommand;
import org.connectorio.addons.binding.bacnet.internal.command.ResetCommand;
import org.connectorio.addons.binding.bacnet.internal.config.DeviceChannelConfig;
import org.connectorio.addons.binding.bacnet.internal.config.DeviceConfig;
import org.connectorio.addons.binding.bacnet.internal.discovery.BACnetPropertyDiscoveryService;
import org.connectorio.addons.binding.bacnet.internal.handler.BACnetObjectBridgeHandler;
import org.connectorio.addons.binding.bacnet.internal.handler.channel.converter.CompositeConverter;
import org.connectorio.addons.binding.bacnet.internal.handler.network.BACnetNetworkBridgeHandler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetCovManager;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetObjectsSampler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetPropertySampler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetSamplerComposer;
import org.connectorio.addons.binding.bacnet.internal.handler.source.ChannelCallback;
import org.connectorio.addons.binding.bacnet.internal.handler.source.SamplerCallback;
import org.connectorio.addons.binding.source.SourceFactory;
import org.connectorio.addons.binding.source.sampling.SamplingSource;
import org.connectorio.addons.communication.watchdog.Watchdog;
import org.connectorio.addons.communication.watchdog.WatchdogManager;
import org.connectorio.addons.link.LinkListener;
import org.connectorio.addons.link.LinkManager;
import org.connectorio.addons.temporal.item.TemporalItemFactory;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BACnetDeviceHandler<C extends DeviceConfig> extends BACnetObjectBridgeHandler<DeviceObject, BACnetNetworkBridgeHandler<?>, C>
    implements BACnetDeviceBridgeHandler<BACnetNetworkBridgeHandler<?>, C>, LinkListener {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final LinkManager linkManager;
  private final SourceFactory sourceFactory;
  private WatchdogManager watchdogManager;

  private Device device;
  private CompletableFuture<BacNetClient> clientFuture = new CompletableFuture<>();
  private boolean discoverObjects;
  private boolean pollingEnabled = true;
  private boolean covEnabled = true;
  private int covLifetime = 300;
  private Watchdog watchdog;
  private SamplingSource<BACnetPropertySampler> source;
  private BACnetCovManager covManager;

  public BACnetDeviceHandler(Bridge bridge, LinkManager linkManager, SourceFactory sourceFactory, WatchdogManager watchdogManager) {
    super(bridge);
    this.linkManager = linkManager;
    this.sourceFactory = sourceFactory;
    this.watchdogManager = watchdogManager;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<BACnetNetworkBridgeHandler<?>> getBridgeHandler() {
    return Optional.ofNullable(getBridge())
      .map(Bridge::getHandler)
      .filter(BACnetNetworkBridgeHandler.class::isInstance)
      .map(BACnetNetworkBridgeHandler.class::cast);
  }

  @Override
  public void initialize() {
    device = getBridgeConfig()
      .map(cfg -> {
        Integer networkNumber = Optional.ofNullable(cfg.network)
          .orElseGet(() -> getBridgeHandler().flatMap(BACnetNetworkBridgeHandler::getNetworkNumber).orElse(0));
        return createDevice(cfg, networkNumber);
      }).orElse(null);

    if (device != null) {
      getBridgeHandler().get().getClient().thenAccept(this::initializeChannels).whenComplete((client, error) -> {
        if (error != null) {
          logger.warn("Initialization of BACnet device handler failed, could not establish client connection", error);
        }
      });
    } else {
      updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Missing device configuration");
    }
  }

  protected void initializeChannels(BacNetClient client) {
    DeviceConfig deviceConfig = getConfigAs(DeviceConfig.class);
    discoverObjects = deviceConfig.discoverObjects;
    String updateMode = Optional.ofNullable(deviceConfig.updateMode).orElse("polling-cov");
    pollingEnabled = !"cov".equals(updateMode);
    covEnabled = !"polling".equals(updateMode);
    covLifetime = deviceConfig.covLifetime > 0 ? deviceConfig.covLifetime : 300;

    if (deviceConfig.discoverChannels && thing.getChannels().isEmpty()) {
      updateChannels(client);
    }

    this.source = sourceFactory.sampling(scheduler, new BACnetSamplerComposer(client));
    configureSource(client);

    source.start();
    linkManager.registerListener(thing, this);
    updateStatus(ThingStatus.ONLINE);
    clientFuture.complete(client);
  }

  @Override
  public void dispose() {
    linkManager.deregisterListener(thing, this);

    if (covManager != null) {
      covManager.close();
      covManager = null;
    }
    if (watchdog != null) {
      watchdog.close();
    }
    if (source != null) {
      source.stop();
    }
    super.dispose();
  }

  private void updateChannels(BacNetClient client) {
    BridgeBuilder builder = editThing();
    builder.withChannels(new ArrayList<>());
    DeviceConfig config = getConfigAs(DeviceConfig.class);
    for (BacNetObject object : client.getDeviceObjects(device)) {
      if (config.discoverPresentValue) {
        createChannel(builder, object, PropertyIdentifier.presentValue);
      }
      if (config.discoverStatusFlags && supportsStatusFlags(object.getType())) {
        createStatusFlagsChannel(builder, object);
      }
      if (config.discoverEventState && supportsEventState(object.getType())) {
        createEventStateChannel(builder, object);
      }
      if (config.discoverOutOfService && supportsOutOfService(object.getType())) {
        createOutOfServiceChannel(builder, object);
      }
      if (Type.SCHEDULE.equals(object.getType())) {
        createChannel(builder, object, PropertyIdentifier.weeklySchedule);
        createChannel(builder, object, PropertyIdentifier.exceptionSchedule);
        createChannel(builder, object, PropertyIdentifier.effectivePeriod);
        createChannel(builder, object, PropertyIdentifier.scheduleDefault);
      }
    }
    updateThing(builder.build());
  }

  private boolean supportsEventState(Type type) {
    switch (type) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
      case BINARY_INPUT:
      case BINARY_OUTPUT:
      case BINARY_VALUE:
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return true;
      default:
        return false;
    }
  }

  private boolean supportsOutOfService(Type type) {
    return supportsEventState(type);
  }

  private boolean supportsStatusFlags(Type type) {
    switch (type) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
      case BINARY_INPUT:
      case BINARY_OUTPUT:
      case BINARY_VALUE:
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return true;
      default:
        return false;
    }
  }

  private void createStatusFlagsChannel(BridgeBuilder builder, BacNetObject object) {
    String channelId = object.getType().name().toLowerCase() + "-" + object.getId() + "-status-flags";
    ChannelUID uid = new ChannelUID(thing.getUID(), channelId);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance", object.getId());
    properties.put("type", object.getType().name());
    properties.put("readOnly", true);
    properties.put("propertyIdentifier", PropertyIdentifier.statusFlags.toString());
    properties.put("refreshInterval", 0);
    Channel channel = ChannelBuilder.create(uid)
      .withType(new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceReadableStatusFlags"))
      .withConfiguration(new Configuration(properties))
      .withLabel(object.getName() + " - Status flags")
      .withDescription(object.getDescription())
      .withAcceptedItemType(CoreItemFactory.NUMBER)
      .build();
    builder.withChannel(channel);
  }

  private void createEventStateChannel(BridgeBuilder builder, BacNetObject object) {
    String channelId = object.getType().name().toLowerCase() + "-" + object.getId() + "-event-state";
    ChannelUID uid = new ChannelUID(thing.getUID(), channelId);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance", object.getId());
    properties.put("type", object.getType().name());
    properties.put("readOnly", true);
    properties.put("propertyIdentifier", PropertyIdentifier.eventState.toString());
    properties.put("refreshInterval", 0);
    Channel channel = ChannelBuilder.create(uid)
      .withType(new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceReadableEventState"))
      .withConfiguration(new Configuration(properties))
      .withLabel(object.getName() + " - Event state")
      .withDescription(object.getDescription())
      .withAcceptedItemType(CoreItemFactory.NUMBER)
      .build();
    builder.withChannel(channel);
  }

  private void createOutOfServiceChannel(BridgeBuilder builder, BacNetObject object) {
    String channelId = object.getType().name().toLowerCase() + "-" + object.getId() + "-out-of-service";
    ChannelUID uid = new ChannelUID(thing.getUID(), channelId);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance", object.getId());
    properties.put("type", object.getType().name());
    properties.put("readOnly", true);
    properties.put("propertyIdentifier", PropertyIdentifier.outOfService.toString());
    properties.put("refreshInterval", 0);
    Channel channel = ChannelBuilder.create(uid)
      .withType(new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceReadableOutOfService"))
      .withConfiguration(new Configuration(properties))
      .withLabel(object.getName() + " - Out of service")
      .withDescription(object.getDescription())
      .withAcceptedItemType(CoreItemFactory.SWITCH)
      .build();
    builder.withChannel(channel);
  }

  private void createChannel(BridgeBuilder builder, BacNetObject object, PropertyIdentifier propertyIdentifier) {
    String channelId = object.getType().name().toLowerCase() + "-" + object.getId() + "-" + propertyIdentifier.toString();
    ChannelUID uid = new ChannelUID(thing.getUID(), channelId);
    String itemType = mapItemType(object, propertyIdentifier);
    ChannelTypeUID channelType = mapChannelType(object, propertyIdentifier);
    if (itemType == null || channelType == null) {
      return;
    }

    Channel channel = ChannelBuilder.create(uid)
      .withType(channelType)
      .withConfiguration(channelConfiguration(object, propertyIdentifier))
      .withLabel(object.getName())
      .withDescription(object.getDescription())
      .withAcceptedItemType(itemType)
      .build();
    builder.withChannel(channel);
  }

  private String mapItemType(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    switch (object.getType()) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
        return CoreItemFactory.NUMBER;
      case BINARY_INPUT:
        return CoreItemFactory.CONTACT;
      case BINARY_OUTPUT:
      case BINARY_VALUE:
        return CoreItemFactory.SWITCH;
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return CoreItemFactory.NUMBER;
      case CHARACTER_STRING:
      case OCTET_STRING:
        return CoreItemFactory.STRING;
      case LARGE_ANALOG:
        return CoreItemFactory.NUMBER;
      case DATE_TIME:
      case TIME:
      case DATE_VALUE:
        return CoreItemFactory.DATETIME;
      case INTEGER:
      case POSITIVE_INTEGER:
        return CoreItemFactory.NUMBER;
      case DATE_TIME_PATTERN:
      case DATE_PATTERN:
      case TIME_PATTERN:
        return CoreItemFactory.STRING;
      case SCHEDULE:
        if (PropertyIdentifier.presentValue.equals(propertyIdentifier) || PropertyIdentifier.scheduleDefault.equals(propertyIdentifier)) {
          return CoreItemFactory.NUMBER;
        }
        if (PropertyIdentifier.weeklySchedule.equals(propertyIdentifier)) return TemporalItemFactory.WEEK_SCHEDULE;
        if (PropertyIdentifier.exceptionSchedule.equals(propertyIdentifier)) return TemporalItemFactory.CALENDAR;
    }
    return null;
  }

  private Configuration channelConfiguration(BacNetObject object) {
    return channelConfiguration(object, PropertyIdentifier.presentValue);
  }

  private Configuration channelConfiguration(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance", object.getId());
    properties.put("type", object.getType().name());
    properties.put("readOnly", false);
    properties.put("propertyIdentifier", propertyIdentifier.toString());
    properties.put("refreshInterval", 0);
    return new Configuration(properties);
  }

  private ChannelTypeUID mapChannelType(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    switch (object.getType()) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case BINARY_INPUT:
      case BINARY_OUTPUT:
      case BINARY_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableBinary");
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case CHARACTER_STRING:
      case OCTET_STRING:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableText");
      case LARGE_ANALOG:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case DATE_TIME:
      case TIME:
      case DATE_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableDateTime");
      case INTEGER:
      case POSITIVE_INTEGER:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case DATE_TIME_PATTERN:
      case DATE_PATTERN:
      case TIME_PATTERN:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableText");
      case CALENDAR:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableCalendar");
      case SCHEDULE:
        if (PropertyIdentifier.presentValue.equals(propertyIdentifier)) return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceReadableNumber");
        if (PropertyIdentifier.weeklySchedule.equals(propertyIdentifier)) return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableWeekSchedule");
        if (PropertyIdentifier.exceptionSchedule.equals(propertyIdentifier)) return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableCalendar");
    }
    return null;
  }

  protected abstract Device createDevice(C config, Integer networkNumber);

  @Override
  public void handleCommand(ChannelUID channelUID, Command command) {
    logger.debug("Handle command {} for channel {}", command, channelUID);

    if (!getBridgeHandler().isPresent()) {
      logger.error("Handler is not attached to an bridge or bridge initialization failed!");
      return;
    }

    final CompletableFuture<BacNetClient> clientFuture = getBridgeHandler().get().getClient();
    Channel channel = getThing().getChannel(channelUID);
    DeviceChannelConfig config = channel.getConfiguration().as(DeviceChannelConfig.class);
    BacNetObject object = new BacNetObject(device, config.instance, config.type);
    String attribute = config.propertyIdentifier;

    if (command == RefreshType.REFRESH) {
      if (source != null) {
        clientFuture.thenAccept(client -> {
          if (PropertyIdentifier.statusFlags.toString().equals(attribute)) {
            source.request(new BACnetObjectsSampler(client, object, PropertyIdentifier.statusFlags.toString(),
              value -> updateStatusFlags(channel, value)));
          } else if (PropertyIdentifier.eventState.toString().equals(attribute)) {
            source.request(new BACnetObjectsSampler(client, object, PropertyIdentifier.eventState.toString(),
              value -> updateEventState(channel, value)));
          } else if (PropertyIdentifier.outOfService.toString().equals(attribute)) {
            source.request(new BACnetObjectsSampler(client, object, PropertyIdentifier.outOfService.toString(),
              value -> updateOutOfService(channel, value)));
          } else {
            source.request(new BACnetObjectsSampler(client, object, attribute, new SamplerCallback(
              CompositeConverter.INSTANCE, new ChannelCallback(getCallback(), channel))));
          }
        });
      }
    } else if (command instanceof ResetCommand) {
      ResetCommand reset = (ResetCommand) command;
      JavaToBacNetConverter<Object> converter = (value) -> Null.instance;
      if (reset.getPriority() == null) {
        if (config.writePriority == null) clientFuture.join().setObjectPropertyValue(object, attribute, null, converter);
        else clientFuture.join().setObjectPropertyValue(object, attribute, null, converter, config.writePriority);
      } else {
        clientFuture.join().setObjectPropertyValue(object, attribute, null, converter, reset.getPriority());
      }
    } else {
      Priority priority = config.writePriority == null ? null : Priorities.get(config.writePriority)
        .orElseThrow(() -> new IllegalArgumentException("Unknown priority " + config.writePriority));
      if (command instanceof PrioritizedCommand) {
        PrioritizedCommand prioritizedCmd = (PrioritizedCommand) command;
        priority = prioritizedCmd.getPriority();
        command = prioritizedCmd.getCommand();
      }
      JavaToBacNetConverter<Command> converter = (value) -> BACnetValueConverter.openHabTypeToBacNetValue(object.getType().getBacNetType(), value);
      if (priority == null) clientFuture.join().setObjectPropertyValue(object, attribute, command, converter);
      else clientFuture.join().setObjectPropertyValue(object, attribute, command, converter, priority);
      logger.debug("Command {} for property {} executed successfully", command, object);
    }
  }

  @Override
  public Collection<Class<? extends ThingHandlerService>> getServices() {
    if (discoverObjects) return Collections.singleton(BACnetPropertyDiscoveryService.class);
    return Collections.emptySet();
  }

  @Override
  public CompletableFuture<BacNetClient> getClient() {
    return clientFuture;
  }

  @Override
  public Device getDevice() {
    return device;
  }

  @Override
  public void linked(ChannelUID channelUID) {
    reconfigureSource();
  }

  @Override
  public void unlinked(ChannelUID channelUID) {
    reconfigureSource();
  }

  private void reconfigureSource() {
    if (this.source != null) {
      getClient().thenAccept(client -> {
        if (covManager != null) {
          covManager.close();
          covManager = null;
        }
        this.source.stop();
        configureSource(client);
        this.source.start();
      });
    }
  }

  private void configureSource(BacNetClient client) {
    if (covManager != null) {
      covManager.close();
      covManager = null;
    }
    if (covEnabled) covManager = new BACnetCovManager(client, scheduler, covLifetime);

    for (Channel channel : thing.getChannels()) {
      if (!linkManager.isLinked(channel.getUID())) continue;

      DeviceChannelConfig config = channel.getConfiguration().as(DeviceChannelConfig.class);
      Long refreshInterval = Optional.ofNullable(config.refreshInterval).filter(value -> value != 0).orElse(getRefreshInterval());
      BacNetObject object = new BacNetObject(device, config.instance, config.type);

      if (PropertyIdentifier.statusFlags.toString().equals(config.propertyIdentifier)) {
        Consumer<Encodable> statusConsumer = value -> updateStatusFlags(channel, value);
        if (pollingEnabled) {
          source.add(refreshInterval, channel.getUID().getAsString(),
            new BACnetObjectsSampler(client, object, PropertyIdentifier.statusFlags.toString(), statusConsumer));
        }
        if (covManager != null) covManager.add(object, null, statusConsumer, null, null);
        continue;
      }

      if (PropertyIdentifier.eventState.toString().equals(config.propertyIdentifier)) {
        Consumer<Encodable> eventConsumer = value -> updateEventState(channel, value);
        source.add(refreshInterval, channel.getUID().getAsString(),
          new BACnetObjectsSampler(client, object, PropertyIdentifier.eventState.toString(), eventConsumer));
        if (covManager != null) covManager.add(object, null, null, eventConsumer, null);
        continue;
      }

      if (PropertyIdentifier.outOfService.toString().equals(config.propertyIdentifier)) {
        Consumer<Encodable> outOfServiceConsumer = value -> updateOutOfService(channel, value);
        source.add(refreshInterval, channel.getUID().getAsString(),
          new BACnetObjectsSampler(client, object, PropertyIdentifier.outOfService.toString(), outOfServiceConsumer));
        if (covManager != null) covManager.add(object, null, null, null, outOfServiceConsumer);
        continue;
      }

      Consumer<Encodable> consumer = new SamplerCallback(CompositeConverter.INSTANCE, new ChannelCallback(getCallback(), channel));
      if (pollingEnabled) {
        source.add(refreshInterval, channel.getUID().getAsString(),
          new BACnetObjectsSampler(client, object, config.propertyIdentifier, consumer));
      }
      if (covManager != null && PropertyIdentifier.presentValue.toString().equals(config.propertyIdentifier)) {
        covManager.add(object, consumer, null, null, null);
      }
    }

    if (covManager != null) covManager.start();
  }

  private void updateEventState(Channel channel, Encodable value) {
    if (!(value instanceof EventState)) {
      logger.warn("Expected EventState for channel {}, got {}", channel.getUID(), value);
      return;
    }
    getCallback().stateUpdated(channel.getUID(), new DecimalType(Integer.toString(((EventState) value).intValue())));
  }

  private void updateOutOfService(Channel channel, Encodable value) {
    if (!(value instanceof com.serotonin.bacnet4j.type.primitive.Boolean)) {
      logger.warn("Expected BACnet Boolean for channel {}, got {}", channel.getUID(), value);
      return;
    }
    boolean state = ((com.serotonin.bacnet4j.type.primitive.Boolean) value).booleanValue();
    getCallback().stateUpdated(channel.getUID(), state ? org.openhab.core.library.types.OnOffType.ON : org.openhab.core.library.types.OnOffType.OFF);
  }

  private void updateStatusFlags(Channel channel, Encodable value) {
    if (!(value instanceof StatusFlags)) {
      logger.warn("Expected StatusFlags for channel {}, got {}", channel.getUID(), value);
      return;
    }
    StatusFlags flags = (StatusFlags) value;
    int mask = 0;
    if (flags.isInAlarm()) mask |= 1;
    if (flags.isFault()) mask |= 2;
    if (flags.isOverridden()) mask |= 4;
    if (flags.isOutOfService()) mask |= 8;
    getCallback().stateUpdated(channel.getUID(), new DecimalType(Integer.toString(mask)));
  }}
