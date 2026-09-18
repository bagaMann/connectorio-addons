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
package org.connectorio.addons.binding.bacnet.internal.handler.network;

import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.ip.BacNetIpClient;
import org.connectorio.addons.binding.bacnet.internal.BACnetBindingConstants;
import org.connectorio.addons.binding.bacnet.internal.config.Ipv4Config;
import org.connectorio.addons.binding.bacnet.internal.discovery.BACnetDeviceDiscoveryService;
import org.connectorio.addons.binding.handler.polling.common.BasePollingBridgeHandler;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BACnetIpv4BridgeHandler extends BasePollingBridgeHandler<Ipv4Config> implements BACnetNetworkBridgeHandler<Ipv4Config> {

  private static final int DEFAULT_BACNET_PORT = 47808;
  private static final Pattern ROUTER_PATTERN = Pattern.compile(
    "^(?<network>\\d+)=(?<ip>\\d+\\.\\d+\\.\\d+\\.\\d+)(?::(?<port>\\d+))?$");
  private static final Pattern BBMD_PATTERN = Pattern.compile(
    "^(?<ip>\\d+\\.\\d+\\.\\d+\\.\\d+)(?::(?<port>\\d+))?(?:@(?<mask>\\d+\\.\\d+\\.\\d+\\.\\d+))?$");

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private CompletableFuture<BacNetClient> clientFuture = new CompletableFuture<>();
  private BacNetClient client;

  /**
   * Creates a new instance of this class for the {@link Bridge}.
   *
   * @param bridge the bridge that should be handled, not null
   */
  public BACnetIpv4BridgeHandler(Bridge bridge) {
    super(bridge);
  }

  @Override
  public void initialize() {
    Ipv4Config config = getBridgeConfig().orElse(null);
    if (config == null) {
      updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        "Missing BACnet/IP bridge configuration");
      return;
    }

    IpNetworkBuilder builder = new IpNetworkBuilder()
      .withBroadcast(config.broadcastAddress, 24)
      .withPort(config.port)
      .withLocalNetworkNumber(config.localNetworkNumber)
      .withReuseAddress(true);

    if (config.localBindAddress != null && !config.localBindAddress.trim().isEmpty()) {
      builder.withLocalBindAddress(config.localBindAddress.trim());
    }

    clientFuture.handleAsync((c, e) -> {
      if (e != null) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
      } else {
        updateStatus(ThingStatus.ONLINE);
      }
      return null;
    }, scheduler);
    clientFuture.thenAcceptAsync(c -> this.client = c, scheduler);

    scheduler.submit(() -> initializeClient(builder, config));
  }

  private void initializeClient(IpNetworkBuilder builder, Ipv4Config config) {
    BacNetIpClient cli = null;
    try {
      cli = new BacNetIpClient(builder.build(), getLocalDeviceId().orElse(1339));
      configureNetworkRouters(cli, config);
      cli.start();
      configureBbmd(cli, config);
      clientFuture.complete(cli);
    } catch (RuntimeException e) {
      logger.warn("Unable to initialize BACnet/IP client", e);
      if (cli != null) {
        try {
          cli.stop();
        } catch (RuntimeException stopError) {
          logger.debug("Unable to stop partially initialized BACnet/IP client", stopError);
        }
      }
      clientFuture.completeExceptionally(e);
    }
  }

  private void configureNetworkRouters(BacNetIpClient cli, Ipv4Config config) {
    List<String> routers = Optional.ofNullable(config.networkRouter).orElse(Collections.emptyList());
    for (String router : routers) {
      if (router == null || router.trim().isEmpty()) continue;

      Matcher matcher = ROUTER_PATTERN.matcher(router.trim());
      if (!matcher.matches() || !isValidIpv4(matcher.group("ip"))) {
        throw new IllegalArgumentException("Invalid BACnet network router: " + router);
      }

      int network = Integer.parseInt(matcher.group("network"));
      String ip = matcher.group("ip");
      int port = parsePort(matcher.group("port"), DEFAULT_BACNET_PORT);
      cli.addNetworkRouter(network, ip, port);
    }
  }

  private void configureBbmd(BacNetIpClient cli, Ipv4Config config) {
    String mode = Optional.ofNullable(config.bbmdMode)
      .orElse("disabled")
      .trim()
      .toLowerCase(Locale.ROOT);

    switch (mode) {
      case "":
      case "disabled":
        logger.debug("BACnet/IP BBMD mode disabled");
        return;

      case "bbmd":
        if (config.localBindAddress == null || config.localBindAddress.trim().isEmpty()
            || "0.0.0.0".equals(config.localBindAddress.trim())) {
          throw new IllegalArgumentException("BBMD mode requires a concrete Local address");
        }

        List<BacNetIpClient.BbmdEntry> peers = new ArrayList<>();
        for (String peer : Optional.ofNullable(config.bbmdPeers).orElse(Collections.emptyList())) {
          if (peer == null || peer.trim().isEmpty()) continue;
          peers.add(parseBbmdEntry(peer.trim(), true));
        }

        cli.enableBbmd(peers);
        logger.info("BACnet/IP BBMD enabled on {}:{} with {} remote BDT peer(s)",
          config.localBindAddress.trim(), config.port, peers.size());
        return;

      case "foreign":
        if (config.foreignBbmdServer == null || config.foreignBbmdServer.trim().isEmpty()) {
          throw new IllegalArgumentException("Foreign Device mode requires a BBMD server");
        }

        BacNetIpClient.BbmdEntry server = parseBbmdEntry(config.foreignBbmdServer.trim(), false);
        int ttl = config.foreignDeviceTtl > 0 ? config.foreignDeviceTtl : 600;
        cli.registerAsForeignDevice(server.getAddress(), server.getPort(), ttl);
        logger.info("BACnet/IP Foreign Device registered at {}:{} TTL={}s",
          server.getAddress(), server.getPort(), ttl);
        return;

      default:
        throw new IllegalArgumentException("Unsupported BBMD mode: " + config.bbmdMode);
    }
  }

  private BacNetIpClient.BbmdEntry parseBbmdEntry(String value, boolean allowMask) {
    Matcher matcher = BBMD_PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid BBMD entry: " + value);
    }

    String ip = matcher.group("ip");
    if (!isValidIpv4(ip)) {
      throw new IllegalArgumentException("Invalid BBMD IPv4 address: " + ip);
    }

    int port = parsePort(matcher.group("port"), DEFAULT_BACNET_PORT);
    String mask = matcher.group("mask");

    if (mask != null) {
      if (!allowMask) {
        throw new IllegalArgumentException("Distribution mask is not valid for Foreign Device server: " + value);
      }
      if (!isValidIpv4(mask)) {
        throw new IllegalArgumentException("Invalid BBMD distribution mask: " + mask);
      }
      return new BacNetIpClient.BbmdEntry(ip, port, mask);
    }

    return new BacNetIpClient.BbmdEntry(ip, port);
  }

  private int parsePort(String value, int defaultPort) {
    if (value == null || value.isEmpty()) return defaultPort;

    int port = Integer.parseInt(value);
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("BACnet/IP port out of range: " + port);
    }
    return port;
  }

  private boolean isValidIpv4(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) return false;

    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) return false;
      try {
        int octet = Integer.parseInt(part);
        if (octet < 0 || octet > 255) return false;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void dispose() {
    if (client != null) {
      client.stop();
    }

    clientFuture.cancel(true);
  }

  @Override
  public void handleCommand(ChannelUID channelUID, Command command) {

  }

  public CompletableFuture<BacNetClient> getClient() {
    return clientFuture;
  }

  @Override
  public Optional<Integer> getNetworkNumber() {
    return getBridgeConfig().map(cfg -> cfg.localNetworkNumber);
  }

  public Optional<Integer> getLocalDeviceId() {
    return getBridgeConfig().map(cfg -> cfg.localDeviceId);
  }

  @Override
  public Collection<Class<? extends ThingHandlerService>> getServices() {
    return Collections.singleton(BACnetDeviceDiscoveryService.class);
  }

  @Override
  protected Long getDefaultPollingInterval() {
    return BACnetBindingConstants.DEFAULT_POLLING_INTERVAL;
  }

}
