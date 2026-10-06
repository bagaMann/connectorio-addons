# BACnet Trend Log UI stable checkpoint — 2026-10-06

This checkpoint records the exact source pair selected for work-machine testing.

## Exact source pair

- connectorio-addons: `stable/bacnet-trendlog-ui-2026-10-06`
  - SHA: `e89b514a9a19ad91682f2dc8b65a5f717835120b`
- bacnet4j-wrapper: `stable/bacnet-trendlog-ui-2026-10-06`
  - SHA: `53149cd1d0642880e23c95648fb0c177b2791b81`

## Confirmed functionality before freezing

- BACnet Trend Log ReadRange reads IQ3 Trend Log records.
- Historical records can be written to InfluxDB with original controller timestamps.
- Incremental synchronization uses the latest persisted timestamp as cursor.
- BACnet Trend Log Sync Thing runs ONLINE after archive initialization.
- UI configuration includes the Trend Log instance, archive Item, persistence service,
  controller time zone and automatic synchronization.
- `Initialize archive` is available as a UI channel and performs guarded initial import.
- Latest archived value/time diagnostic channels are present.
- The verified BACnet Boolean conversion fix used by Weekly Schedule is retained.

## Known limitation

Historical writes are made through ModifiablePersistenceService. The archive Item itself is not
updated as a live openHAB Item, so its current state can remain NULL and the standard Item Analyzer
may not present this imported archive like ordinary Item persistence. This is accepted for this
stable checkpoint.

## Reproducible build

Build the wrapper first so the exact 1.3.0-SNAPSHOT API/IP/MSTP artifacts are installed locally:

```bash
cd /opt/bacnet-dev/bacnet4j-wrapper
git fetch origin
git switch stable/bacnet-trendlog-ui-2026-10-06
git pull --ff-only
mvn -pl api,ip,mstp -am install -DskipTests
```

Then build both the BACnet binding JAR and BACnet KAR from the exact stable addons branch:

```bash
cd /opt/bacnet-dev/trendlog-ui-sync/addons
git fetch origin
git switch stable/bacnet-trendlog-ui-2026-10-06
git pull --ff-only
mvn -U -Popenhab -pl kars/org.connectorio.addons.kar.bacnet -am package -DskipTests
```

Expected deployable artifacts:

```text
bundles/org.connectorio.addons.binding.bacnet/target/org.connectorio.addons.binding.bacnet-5.0.0-SNAPSHOT.jar
kars/org.connectorio.addons.kar.bacnet/target/org.connectorio.addons.kar.bacnet-5.0.0-SNAPSHOT.kar
```

For work-machine testing, copy them under stable names without changing their internal Maven/OSGi versions:

```bash
mkdir -p /opt/bacnet-dev/releases/bacnet-trendlog-ui-2026-10-06

cp bundles/org.connectorio.addons.binding.bacnet/target/org.connectorio.addons.binding.bacnet-5.0.0-SNAPSHOT.jar \
  /opt/bacnet-dev/releases/bacnet-trendlog-ui-2026-10-06/org.connectorio.addons.binding.bacnet-5.0.0-STABLE-2026-10-06.jar

cp kars/org.connectorio.addons.kar.bacnet/target/org.connectorio.addons.kar.bacnet-5.0.0-SNAPSHOT.kar \
  /opt/bacnet-dev/releases/bacnet-trendlog-ui-2026-10-06/org.connectorio.addons.kar.bacnet-5.0.0-STABLE-2026-10-06.kar

sha256sum /opt/bacnet-dev/releases/bacnet-trendlog-ui-2026-10-06/*
```

Do not continue feature development directly on this stable branch.
