/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.addons.binding.bacnet.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.BaseDynamicStateDescriptionProvider;
import org.openhab.core.thing.events.ThingEventFactory;
import org.openhab.core.thing.i18n.ChannelTypeI18nLocalizationService;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.DynamicStateDescriptionProvider;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.StateDescriptionFragment;
import org.openhab.core.types.StateDescriptionFragmentBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = { DynamicStateDescriptionProvider.class, BACnetStateDescriptionProvider.class })
public class BACnetStateDescriptionProvider extends BaseDynamicStateDescriptionProvider {

  private final Map<ChannelUID, StateDescriptionFragment> fragments = new ConcurrentHashMap<>();

  @Activate
  public BACnetStateDescriptionProvider(
      @Reference EventPublisher eventPublisher,
      @Reference ItemChannelLinkRegistry itemChannelLinkRegistry,
      @Reference ChannelTypeI18nLocalizationService channelTypeI18nLocalizationService) {
    this.eventPublisher = eventPublisher;
    this.itemChannelLinkRegistry = itemChannelLinkRegistry;
    this.channelTypeI18nLocalizationService = channelTypeI18nLocalizationService;
  }

  @Override
  public @Nullable StateDescription getStateDescription(Channel channel, @Nullable StateDescription original,
      @Nullable Locale locale) {
    StateDescriptionFragment fragment = fragments.get(channel.getUID());
    return fragment != null ? fragment.toStateDescription() : super.getStateDescription(channel, original, locale);
  }

  public void setNumberFormat(ChannelUID channelUID, int decimalPlaces, @Nullable String bacnetUnit,
      boolean useBacnetUnit, boolean readOnly) {
    int places = Math.max(0, Math.min(6, decimalPlaces));
    String unit = useBacnetUnit ? displayUnit(bacnetUnit) : "";
    String pattern = "%." + places + "f" + (unit.isEmpty() ? "" : " " + escapePercent(unit));

    StateDescriptionFragment oldFragment = fragments.get(channelUID);
    StateDescriptionFragment newFragment = StateDescriptionFragmentBuilder.create()
      .withPattern(pattern)
      .withReadOnly(readOnly)
      .build();

    if (!newFragment.equals(oldFragment)) {
      fragments.put(channelUID, newFragment);
      ItemChannelLinkRegistry registry = this.itemChannelLinkRegistry;
      postEvent(ThingEventFactory.createChannelDescriptionChangedEvent(channelUID,
        registry != null ? registry.getLinkedItemNames(channelUID) : Set.of(),
        newFragment, oldFragment));
    }
  }

  private static String escapePercent(String unit) {
    return unit.replace("%", "%%");
  }

  private static String displayUnit(@Nullable String unit) {
    if (unit == null || unit.isBlank()) return "";
    String normalized = unit.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

    switch (normalized) {
      case "nounits": return "";
      case "degreescelsius": return "°C";
      case "degreesfahrenheit": return "°F";
      case "kelvin": return "K";
      case "percent":
      case "percentrelativehumidity": return "%";
      case "partspermillion": return "ppm";
      case "partsperbillion": return "ppb";
      case "pascals": return "Pa";
      case "hectopascals": return "hPa";
      case "kilopascals": return "kPa";
      case "millibars": return "mbar";
      case "bars": return "bar";
      case "watts": return "W";
      case "kilowatts": return "kW";
      case "megawatts": return "MW";
      case "watthours": return "Wh";
      case "kilowatthours": return "kWh";
      case "megawatthours": return "MWh";
      case "volts": return "V";
      case "millivolts": return "mV";
      case "kilovolts": return "kV";
      case "amperes": return "A";
      case "milliamperes": return "mA";
      case "hertz": return "Hz";
      case "kilohertz": return "kHz";
      case "meters": return "m";
      case "centimeters": return "cm";
      case "millimeters": return "mm";
      case "meterspersecond": return "m/s";
      case "cubicmeters": return "m³";
      case "liters": return "l";
      case "literspersecond": return "l/s";
      case "litersperminute": return "l/min";
      case "cubicmetersperhour": return "m³/h";
      case "luxes": return "lx";
      case "seconds": return "s";
      case "minutes": return "min";
      case "hours": return "h";
      case "days": return "d";
      default: return unit;
    }
  }
}
