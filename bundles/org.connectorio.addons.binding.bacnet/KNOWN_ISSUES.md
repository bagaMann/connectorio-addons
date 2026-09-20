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
