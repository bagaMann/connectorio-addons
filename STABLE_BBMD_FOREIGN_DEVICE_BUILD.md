# Stable BACnet KAR build — 2026-09-19

This build branch exists only to produce a reproducible KAR from the verified stable BACnet baseline.

Pinned sources:

- connectorio-addons: `stable/bbmd-foreign-device-2026-09-19`
  - `f1e84b1921d6e1c80b3d7c16ec08d31bc1878801`
- bacnet4j-wrapper: `stable/bbmd-foreign-device-2026-09-19`
  - `4afe087401b2237f77d79718730997c99bd907c7`

Verified features in this baseline:

- BACnet4J 6.1.0-beta.2
- COV Present_Value
- COV Status_Flags
- automatic COV renewal
- BACnet device health monitoring
- BBMD mode
- Foreign Device registration through a remote BBMD
- discovery through a remote BBMD
- automatic child-device reinitialization after IPv4 bridge configuration changes

This branch is not a development branch.
