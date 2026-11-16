# BMWDrive

A native Android app (Kotlin + Jetpack Compose, Material 3) for a **BMW G20** that
connects over a **USB-C ENET cable** and speaks **DoIP / UDS (ISO 13400 / 14229)** to
the car's diagnostic network. It reads and clears fault codes, streams live engine
data, and renders track dashboards (tach, g-force, drag, dyno, warm-up) on the phone.

It also ships an **optional, root-gated head-unit deployer** that mirrors the
[`idrive7-apps`](https://github.com/cauliflowermossgrape/idrive7-apps) `push-to-car.sh`
flow for pushing MGU custom apps onto an iDrive 7 unit.

> **Not affiliated with BMW AG.** Hobby/diagnostic tool. Use parked or on a closed
> track. Clearing faults across modules can restart control units and reboot the head
> unit — read codes first.

## What works over the ENET (OBD) cable, and what does not

The ENET OBD cable puts the phone on the car's **diagnostic** Ethernet, reaching the
gateway (ZGW) over **DoIP, TCP/UDP 13400**. That makes these reliably achievable
without head-unit root:

- UDS diagnostics: read/clear DTCs (0x19 / 0x14), read data by identifier (0x22)
- Standard OBD-II mode 01 PIDs tunnelled over DoIP
- Live telemetry at modest poll rates, fused with the phone IMU for g-force

What the **OBD** cable does **not** give you:

- **SOME/IP** high-rate telemetry — that lives on the car's internal Ethernet, not at
  the OBD gateway.
- **Head-unit SSH** (`socnet0`) — a different network and, per `idrive7-apps`, requires
  **root** plus BMW's extracted **`oap-sdk`**, neither of which this app can provide.
  The Deploy tab is therefore gated behind an explicit precondition checklist.

## Architecture

```
transport/   EnetTransport  — USB-C Ethernet detection + DoIP socket & routing activation
uds/         UdsClient      — UDS services, ECU address book, fault-text decoding
telemetry/   TelemetrySource— periodic 0x22/PID polling fused with device IMU
ui/          Compose Material 3 — Connect, Track dashboards, Fault Scanner, Deploy, Settings
deploy/      HeadUnitDeployer — optional SSH push (check/install/restore/status)
```

The layers depend only on the interfaces in each package (`EnetTransport`,
`UdsClient`, `TelemetrySource`, `HeadUnitDeployer`), so transports and UIs are
swappable and a **demo mode** drives every screen with no car attached.

## Build

Requires Android Studio (Koala+) and JDK 17.

```
./gradlew :app:assembleDebug
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module contracts and
[`docs/ENET-SETUP.md`](docs/ENET-SETUP.md) for the USB-C ENET wiring and addressing.

## License

MIT — see [LICENSE](LICENSE).
