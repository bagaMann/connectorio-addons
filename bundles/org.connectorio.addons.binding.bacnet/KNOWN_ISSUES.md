# BACnet TODO / known issues

## Repeated COV resubscriptions during bulk Item linking

Status: **deferred — fix after current long-running field test**

Observed during real openHAB testing on 2026-09-20 after loading many linked Items.

### Symptom

When many Items are linked in a short period, BACnet traffic shows a large temporary burst of repeated `SubscribeCOV` requests for objects that were already subscribed.

Observed capture:
- approximately 824 `SubscribeCOV` requests;
- approximately 28 unique BACnet objects;
- no continuous storm after initialization;
- no Reject / Abort / timeout observed;
- after the startup burst, traffic returns to normal health-check/COV operation.

### Root cause

`BACnetDeviceHandler.linked()` and `unlinked()` call `reconfigureSource()`.

Current `reconfigureSource()` behavior:
1. closes the complete `BACnetCovManager`;
2. stops the sampling source;
3. rebuilds configuration for all currently linked channels;
4. recreates all COV subscriptions.

Therefore sequential Item linking behaves roughly like:

```text
link Item 1 -> subscribe object 1
link Item 2 -> resubscribe objects 1,2
link Item 3 -> resubscribe objects 1,2,3
...
```

This creates an O(n²)-like startup subscription burst.

### Preferred fix

Keep the validated COV/BBMD/Foreign Device architecture unchanged.

Implement debounce/batching for link/unlink-driven `reconfigureSource()`:
- schedule one reconfiguration approximately 500-1000 ms after the last link/unlink event;
- cancel/reschedule the pending task while additional link/unlink events arrive;
- perform one final rebuild after the bulk Item linking settles.

A later, more invasive optimization could update only the affected channel/object subscription incrementally, but that is not required for the first fix.

### Acceptance criteria

- Bulk Item loading creates roughly one final COV subscription per BACnet object instead of repeatedly rebuilding all subscriptions.
- Present_Value + Status_Flags object-level COV behavior remains unchanged.
- Event_State + Out_Of_Service polling remains unchanged.
- COV renewal remains functional.
- BACnet health monitoring remains functional.
- Bridge configuration changes still automatically reconnect child BACnet device handlers.
- BBMD and Foreign Device behavior remain unchanged.
- No continuous subscription storm after initialization.

Do not change this behavior during the current stability test unless it causes an operational problem.


## Channel type to property mapping and write semantics

Status: **implemented in cleanup branch — ready for field testing**

The manual channel UI still exposes `propertyIdentifier`, although the selected BACnet channel type already determines which property must be used.

Desired fixed mapping:

```text
Present value — Binary    -> present-value
Present value — Number    -> present-value
Present value — Date/Time -> present-value
Present value — Text      -> present-value
Status flags              -> status-flags
Event state               -> event-state
Out of service            -> out-of-service
```

The UI should require only the channel type, BACnet object instance and BACnet object type for this part of configuration. The property identifier should be supplied internally from the channel type and must not be user-selectable.

Write semantics:
- Status flags: read-only.
- Event state: read-only.
- Out of service: read/write.
  - Implement a property-specific BACnet Boolean conversion for writes. The current generic write path converts commands using the BACnet object type, which is appropriate for Present_Value but not for Out_Of_Service.
- Present value channel variants remain read/write where the BACnet object permits writing.

Current implementation note:
`DeviceChannelConfig.readOnly` is present but `BACnetDeviceHandler.handleCommand()` does not enforce it. Do not rely on the current readOnly field as command protection. When this cleanup is implemented, read/write behavior should be explicit in channel types/handler logic.

Implementation completed:
- manual channel property selection removed from the UI;
- exposed channel type now determines the BACnet property internally;
- Status Flags and Event State writes are rejected by the handler;
- a new read/write Out Of Service channel type is exposed;
- Out Of Service writes use BACnet primitive Boolean conversion;
- legacy read-only Out Of Service channel type remains available internally for compatibility.

Refresh interval semantics to preserve:
- A non-zero channel `refreshInterval` overrides the device/bridge polling interval for that channel.
- A zero channel interval falls back to the device refresh interval, then bridge refresh interval, then the BACnet default polling interval.
- In COV-only mode, Present_Value and Status_Flags are not periodically polled, so their refresh interval does not drive regular updates.
- Event_State and Out_Of_Service are intentionally polled even in COV-only mode with the current implementation, because the tested IQ3 object-level COV does not reliably provide those properties.
