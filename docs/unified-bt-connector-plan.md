# Unified Bluetooth Connector Implementation Plan

## Goal

Produce one CanZE Android app that supports both:

- Bluetooth Classic ELM327 adapters using RFCOMM/SPP.
- Bluetooth Low Energy ELM327 adapters using GATT notifications and a writable characteristic.

The app should preserve the existing ELM327/CAN/ISO-TP behavior and expose one transport-neutral connector to the rest of the application.

## Repository and branch context

- Repository: `jha749/CanZE`
- Source branch: `master` (the repository default branch; it contains the BLE implementation).
- Reference branch: `bt-classic` (the original Bluetooth Classic implementation).
- Working branch: `feature/unified-bt-connector`

The branch-specific difference is concentrated in:

- `app/src/main/java/lu/fisch/canze/bluetooth/BluetoothManager.java`
- `app/src/main/AndroidManifest.xml`

`ELM327.java` uses the `BluetoothManager` byte/stream-style API and is effectively transport-neutral. It should not be rewritten as part of the initial integration.

## Existing implementations

### Bluetooth Classic implementation (`bt-classic`)

The Classic implementation:

- Creates an RFCOMM `BluetoothSocket`.
- Uses the SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
- Reads and writes through `InputStream` and `OutputStream`.
- Uses `BluetoothSocket.isConnected()` and `InputStream.available()`.
- Performs synchronous socket connection and stream initialization.
- Uses the existing retry thread and `BluetoothEvent` callbacks.

### BLE implementation (`master`)

The BLE implementation:

- Creates a `BluetoothGatt` connection.
- Uses the custom service UUID `0000fff0-0000-1000-8000-00805f9b34fb`.
- Receives data through notifications on `0000fff1-0000-1000-8000-00805f9b34fb`.
- Sends data through `0000fff2-0000-1000-8000-00805f9b34fb`.
- Buffers notification bytes in a `LinkedBlockingQueue<Byte>`.
- Negotiates ATT MTU and chunks writes according to the negotiated payload size.
- Serializes GATT writes because only one GATT operation should be outstanding at a time.
- Waits for asynchronous connection, service discovery, notification setup, and write callbacks.

The two implementations have different lifecycle and I/O mechanics, so they should remain separate internally.

## Target architecture

Use a stable transport interface and a facade:

```text
ELM327 / CanSee / Device
              |
              v
      BluetoothManager facade
              |
              v
      BluetoothTransport interface
          /               \
         v                 v
ClassicBluetoothTransport  BleBluetoothTransport
```

### Why a facade is preferred

Keeping `BluetoothManager` as the public entry point minimizes changes to existing callers. The current application already calls methods such as:

- `BluetoothManager.getInstance().connect(...)`
- `write(...)`
- `read(...)`
- `available()`
- `isConnected()`
- `disconnect()`
- `setBluetoothEvent(...)`
- `setDummyMode(...)`

The facade can delegate these methods to the selected backend while allowing the implementation to become transport-specific.

## Proposed files

Add:

- `app/src/main/java/lu/fisch/canze/bluetooth/BluetoothTransport.java`
- `app/src/main/java/lu/fisch/canze/bluetooth/ClassicBluetoothTransport.java`
- `app/src/main/java/lu/fisch/canze/bluetooth/BleBluetoothTransport.java`
- `app/src/main/java/lu/fisch/canze/bluetooth/BluetoothTransportFactory.java`
- Optionally `app/src/main/java/lu/fisch/canze/bluetooth/BluetoothTransportMode.java`

Refactor:

- `app/src/main/java/lu/fisch/canze/bluetooth/BluetoothManager.java`
- `app/src/main/java/lu/fisch/canze/activities/MainActivity.java`
- `app/src/main/java/lu/fisch/canze/activities/SettingsActivity.java`
- Relevant settings XML and string resources.

Do not initially change:

- `app/src/main/java/lu/fisch/canze/devices/ELM327.java`
- CAN frame definitions and vehicle assets.
- ISO-TP parsing and request logic.

## Transport interface

Define the smallest interface that captures the behavior required by current callers. A possible API is:

```java
interface BluetoothTransport {
    int getHardwareState();

    void connect(String address, boolean secure, int retries);
    void connect();
    void disconnect();

    void write(String message);
    int read() throws IOException;
    int read(byte[] buffer) throws IOException;
    int available() throws IOException;
    boolean isConnected();

    void setBluetoothEvent(BluetoothEvent event);
    void setDummyMode(boolean dummyMode);
    boolean isDummyMode();
}
```

Before implementing, inspect all current `BluetoothManager` callers and keep only methods that are actually required. If the public API must remain source-compatible, retain the current methods on the facade even if the interface is smaller.

## Refactoring sequence

### Phase 1: Characterize the current behavior

1. Record the exact public API of the BLE `BluetoothManager` on `master`.
2. Record the exact public API and callback signatures of the Classic manager on `bt-classic`.
3. Search all callers, especially `MainActivity`, `Device`, `ELM327`, and `CanSee`.
4. Identify whether any caller depends on implementation-specific behavior, such as:
   - `BluetoothSocket` passed to a callback.
   - `BluetoothGatt` lifecycle behavior.
   - ACL disconnect broadcasts.
   - Blocking versus non-blocking connection semantics.
5. Document the adapter UUID assumptions and whether all supported BLE dongles use the current FFF0/FFF1/FFF2 UUIDs.

### Phase 2: Extract the Classic backend

1. Start from the known-good Classic code in `bt-classic`.
2. Move socket fields, RFCOMM creation, stream handling, retry logic, and socket cleanup into `ClassicBluetoothTransport`.
3. Preserve:
   - Secure/insecure RFCOMM behavior.
   - SPP UUID.
   - Retry count semantics.
   - `BluetoothEvent` ordering.
   - Dummy/HTTP mode behavior.
4. Do not change ELM327 framing or timing.
5. Make connection failure explicit internally so the factory/facade can try another backend in Auto mode.

### Phase 3: Extract the BLE backend

1. Start from the BLE code in `master`.
2. Move GATT fields, callbacks, MTU negotiation, service discovery, notification setup, queue handling, and chunked writes into `BleBluetoothTransport`.
3. Preserve:
   - FFF0/FFF1/FFF2 UUIDs.
   - CCCD notification setup.
   - ATT MTU negotiation.
   - Serialized writes and write timeouts.
   - Notification byte ordering.
   - Retry behavior.
   - Dummy/HTTP mode behavior.
4. Make all GATT callbacks verify that they belong to the currently active GATT instance where appropriate, to avoid stale callbacks from a previous connection changing current state.
5. Ensure disconnect releases all latches and does not leave a thread blocked indefinitely.

### Phase 4: Implement the facade

Refactor `BluetoothManager` into a delegating facade that owns the selected `BluetoothTransport`.

Responsibilities:

- Preserve the existing singleton entry point.
- Store the configured address, secure flag, retry count, event listener, and dummy mode.
- Select or replace the active transport before connecting.
- Delegate I/O calls to the active transport.
- Keep selection and replacement synchronized so reconnects cannot race with transport changes.
- Disconnect the old transport before replacing it.
- Expose a transport type/status for diagnostics and UI if useful.

Avoid a design in which both transports are connected simultaneously. One ELM327 adapter should have exactly one active transport at a time.

## Transport selection

Add an explicit mode:

```java
enum BluetoothTransportMode {
    AUTO,
    CLASSIC,
    BLE
}
```

Persist it in SharedPreferences, with `AUTO` as the default for existing users.

### Recommended Auto behavior

Use the following order:

1. If the user explicitly selected BLE or Classic, use only that transport.
2. In Auto mode, use a remembered transport type for the configured device address if one exists.
3. Otherwise determine likely transport from platform/device capabilities where reliable.
4. Attempt the preferred backend.
5. If connection setup fails before the transport becomes ready, close it and attempt the other backend.
6. Remember the successful transport for that address.

Do not use a blind fallback after a successful connection. Once one backend reports ready, all operations must remain on that backend until disconnect.

### Address and device-type caveat

A Bluetooth MAC address alone does not reliably identify whether a device is Classic-only, BLE-only, or dual-mode. Therefore:

- Do not rely solely on `BluetoothDevice.getType()` for correctness.
- Treat backend connection success and service/socket setup as authoritative.
- For BLE, verify the expected GATT service and characteristics.
- For Classic, verify RFCOMM connection and stream initialization.
- Provide explicit Classic/BLE settings for adapters that do not work correctly with Auto.

## Connection and callback contract

Define and document one callback contract for both backends:

1. `onBeforeConnect()` exactly once per connection attempt.
2. `onAfterConnect()` only after the backend is fully usable.
   - Classic: socket connected and streams available.
   - BLE: GATT connected, services found, write characteristic found, notifications enabled or otherwise confirmed usable.
3. `onBeforeDisconnect()` once for an intentional disconnect.
4. `onAfterDisconnect()` after resources are released.

For failed attempts, either add an internal failure callback/result or ensure the facade can observe failure without incorrectly calling `onAfterConnect()`.

The existing `MainActivity` callback should remain responsible for:

- Bluetooth icon state.
- Device initialization after a successful connection.
- User-facing disconnect notification.

## I/O and concurrency requirements

The current ELM327 implementation polls `isConnected()`, `available()`, and `read()` from a worker thread. The unified API must preserve these semantics:

- `available()` must not block.
- `read()` must return immediately when no byte is available.
- `read()` must return `-1` when disconnected or when no byte is available, matching the current BLE behavior.
- Classic reads should avoid introducing an unexpected blocking read in code paths that first check `available()`.
- `write()` must not interleave bytes from concurrent callers.
- BLE writes must remain serialized at the GATT-operation level.
- Disconnect must unblock pending connect/write operations.
- All transport state visible across callback and worker threads must be `volatile`, synchronized, or otherwise safely published.

Consider adding a transport-level lock around write operations for both backends so the facade has identical semantics.

## Android permissions and manifest

Retain support for both transport families:

- Android 12/API 31 and newer:
  - `BLUETOOTH_CONNECT`
  - `BLUETOOTH_SCAN`
- Older Android versions:
  - legacy Bluetooth permissions as required by the project’s minimum/target SDK and discovery behavior.

The existing BLE manifest uses `neverForLocation` for scanning. Verify that this is appropriate for the supported adapters and that Classic-only devices still work when the user has granted the required runtime permissions.

Review `MainActivity.checkPermissions()` because the current implementation has separate permission request paths and callback handling. Make sure:

- Permission callbacks do not fall through between cases unintentionally.
- `configureBluetoothManager()` is called only after all required permissions for the selected mode are available.
- Auto mode requests the union of permissions needed by both backends.
- Permission denial produces a useful message rather than an apparent connection timeout.

## Settings/UI changes

Add a setting such as `Bluetooth transport` with:

- Automatic
- Bluetooth Classic
- Bluetooth LE

Requirements:

- Default to Automatic for new and existing users.
- Preserve the setting across upgrades.
- Explain that some adapters require explicitly selecting Classic or BLE.
- Stop the active device/transport before applying a changed mode.
- Reconnect using the new mode after settings are saved.

Optionally display the active transport in a diagnostic area or debug log:

- `Connected via Bluetooth Classic`
- `Connected via Bluetooth LE`

## Error handling and fallback details

Use typed/internal failure reasons where practical:

- Missing Bluetooth hardware.
- Bluetooth disabled.
- Permission denied.
- Invalid/empty address.
- RFCOMM socket failure.
- GATT connection failure.
- Expected BLE service missing.
- Expected BLE characteristic missing.
- Notification subscription failure.
- Write timeout.
- Read/disconnect failure.

For Auto mode:

- Fall back only for connection/setup failures.
- Do not hide a protocol-level ELM327 initialization failure by repeatedly switching transports.
- Include the attempted transport names in debug output.
- Avoid infinite rapid fallback loops; use the existing retry delay and bounded per-cycle attempts.

Example diagnostic sequence:

```text
Auto: attempting BLE for AA:BB:CC:DD:EE:FF
BLE: service FFF0 not found
Auto: attempting Classic RFCOMM
Classic: connected
Transport selected: CLASSIC
```

## Testing plan

### Unit-level tests

Add tests for transport-neutral behavior using fake transports where the project’s test setup permits:

- Delegation from facade to active backend.
- Mode selection.
- Auto fallback from BLE to Classic.
- Auto fallback from Classic to BLE.
- No fallback after a backend reports successful connection.
- Event callback ordering.
- Dummy mode behavior.
- Write serialization.
- Read/available semantics.
- Disconnect clearing the active transport.

### Instrumented/device tests

Test on a representative Android version range:

- Android version before API 31.
- Android 12/API 31 or later.
- Device with Bluetooth Classic and BLE support.
- Device with Bluetooth disabled.
- Device without Bluetooth hardware, if available through an emulator/test device.

Test adapters:

- Known working Classic ELM327/SPP adapter.
- Known working BLE adapter using FFF0/FFF1/FFF2.
- Classic adapter with Auto mode.
- BLE adapter with Auto mode.
- Adapter with an incorrect/unexpected BLE service to verify useful failure behavior.

### Protocol regression tests

For each transport:

1. Connect.
2. Run ELM initialization.
3. Read a single-frame response.
4. Read a multi-frame ISO-TP response.
5. Send a single-frame request.
6. Send a multi-frame request.
7. Capture free frames using `ATMA`.
8. Stop capture using `x` and recover the prompt.
9. Switch between ECUs and CAN ID modes.
10. Disconnect and reconnect.

Compare responses and timing against the existing known-good branches. The transport change must not alter CAN payloads or ISO-TP decoding.

### Lifecycle tests

- Rotate or background/foreground the app.
- Leave Bluetooth on while opening a child activity.
- Use Bluetooth background mode.
- Trigger an ACL disconnect for Classic.
- Trigger a GATT disconnect for BLE.
- Tap reconnect repeatedly.
- Stop the app during connection setup.
- Disconnect while a BLE write is pending.

## Suggested commit sequence

1. `Add transport interface and mode model`
2. `Extract Bluetooth Classic transport`
3. `Extract BLE transport`
4. `Refactor BluetoothManager into transport facade`
5. `Add automatic transport selection and fallback`
6. `Add transport preference to settings`
7. `Harden permissions and lifecycle handling`
8. `Add tests and diagnostics`

Keep commits buildable where practical. Avoid combining UI changes, transport extraction, and protocol changes in one commit.

## Acceptance criteria

The implementation is ready for review when:

- One APK supports both Classic RFCOMM and BLE GATT adapters.
- Existing `ELM327` and CAN/ISO-TP logic remains unchanged unless a transport-independent bug is discovered.
- Auto mode can select the correct backend or fall back to the other backend.
- Classic-only and BLE-only explicit modes work.
- Existing users default to a non-breaking mode.
- Connection callbacks and Bluetooth UI state remain correct.
- Disconnect/reconnect works for both transports.
- Android permission handling works on both pre-API-31 and API-31+ devices.
- Tests cover successful connections, failures, fallback, and lifecycle interruption.
- Debug logs identify the selected transport and meaningful setup failures.

## Risks and open questions

1. **BLE UUID compatibility**: The current BLE implementation assumes FFF0/FFF1/FFF2. Confirm that every intended BLE dongle uses these UUIDs or add configurable/service-discovery support.
2. **Classic callback compatibility**: The Classic branch’s event callback signatures may differ from the BLE branch. Normalize them at the interface/facade boundary.
3. **Blocking behavior**: Classic `InputStream.read()` can block, while BLE queue reads are non-blocking. Preserve the behavior expected by `ELM327` and add tests around `available()`/`read()`.
4. **Fallback ambiguity**: A successful Bluetooth connection followed by an ELM protocol failure should not automatically be interpreted as a transport failure.
5. **Android permission flow**: The current permission callback logic should be reviewed for fall-through and mode-specific requirements.
6. **GATT stale callbacks**: Old GATT callbacks can arrive after a reconnect. Guard callbacks against stale connection instances.
7. **Device discovery**: The current settings flow appears to store an address. Confirm how users select BLE devices and whether Classic paired-device selection is sufficient.
8. **Minimum SDK/API behavior**: Verify that all BLE APIs and permission checks are compatible with the project’s configured compile, target, and minimum SDK versions.

## Recommended first implementation slice

For the first coding pass, implement only the transport extraction and facade:

1. Add `BluetoothTransport`.
2. Copy the Classic implementation into `ClassicBluetoothTransport`.
3. Copy the BLE implementation into `BleBluetoothTransport`.
4. Make `BluetoothManager` delegate to one selected backend.
5. Add a temporary programmatic/default mode selector.
6. Build and run protocol regression tests.

Only after that works should settings UI and automatic fallback be added. This separates mechanical refactoring risk from product-selection behavior and makes failures easier to triage.
