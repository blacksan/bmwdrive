# BMWDrive architecture & module contracts

Every layer depends only on the interface at its seam. Implement against these;
do not reach across layers.

## transport — `EnetTransport`
- Locate the ENET interface: a USB-C Ethernet adapter / ENET-USB cable. On Android
  this may surface as a managed `Network` (CDC-ECM/RNDIS/AX88179 in-kernel) or need
  `UsbManager` claiming. Prefer `ConnectivityManager` + `bindProcessToNetwork` to pin
  sockets to the adapter without root.
- DoIP (ISO 13400) on TCP 13400: vehicle identification (UDP 13400) → TCP connect →
  routing activation (type 0x0005, source = tester addr) → await positive activation.
- `sendUds(request, targetAddress)` wraps UDS in a DoIP diagnostic message (payload
  type 0x8001), absorbs 0x78 responsePending, returns the UDS payload.
- Addressing defaults live in `EnetConfig`; the research spec refines them.

## uds — `UdsClient`
- Implements 0x10/0x19/0x14/0x22/0x27 over `EnetTransport`.
- Ship the ECU address book (`EcuAddress`) from the EdiabasLib set and a fault-text
  table for decoding DTCs into `FaultCode.description`.
- `clearFaults` is DESTRUCTIVE — never call without UI confirmation.

## telemetry — `TelemetrySource`
- Poll `UdsClient.readDataById` for rpm/speed/temps/boost/throttle and/or OBD-II PIDs;
  fuse device IMU for `accelLong`/`accelLat`. Emit `VehicleState` via `StateFlow`.
- Provide a `DemoTelemetrySource` replaying a synthetic drive (no car needed).

## ui — Compose, Material 3
- Screens: Connect, Track (Redline, G-Force, Drag, Dyno, Warm-Up), Fault Scanner,
  Deploy, Settings. Gauges are Canvas-drawn and read `AppTheme.accents`.
- Four themes: M Red, M Tricolour, Ice White, Night Amber. Honour Material 3
  (dynamic scheme, tonal elevation, large touch targets, edge-to-edge insets).

## deploy — `HeadUnitDeployer` (OPTIONAL)
- SSH (sshj) to the MGU; mirror `push-to-car.sh`: check / install / restore / status.
- Surface the hard preconditions (route to head unit, root, oap-sdk) BEFORE any action.
