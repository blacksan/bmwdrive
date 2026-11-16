# ENET setup

See docs/RESEARCH.md for what is verified vs unverified.

## Direct vs bridge
- **Direct (default, recommended).** Plug the USB-C ENET cable / adapter straight into the
  phone. Modern Android brings the adapter up as a wired `TRANSPORT_ETHERNET` network and the
  app binds to it with **no root** — the same path BimmerLink uses. DIRECT mode requests that
  network (internet capability removed, since the car link is offline) and binds the process to
  it while connected. Requirement: the phone's kernel has the adapter's chipset driver (common
  ASIX / Realtek / CDC-ECM are widely supported); some phones need the wired link enabled once
  in Settings.
- **Bridge (fallback).** If the phone won't surface the USB-C adapter, plug an ENET-to-WiFi (or
  ENET-to-TCP) bridge into the OBD ENET cable, join its WiFi, and connect in BRIDGE mode. The
  phone then speaks plain TCP to the bridge on port 13400. Typical bridge address:
  192.168.16.254 (editable).
- **Demo.** Simulated car, no hardware.

## Addressing
- Car side: DHCP if a server exists, otherwise Auto-IP 169.254.x.x (example tester
  169.254.1.10 / 255.255.0.0). 160.48.199.x is unconfirmed; fallback only.
- DoIP: TCP/UDP 13400. Tester logical address 0x0E80, gateway ZGW 0x0010, engine ECU 0x0012.
- ENET cable activation: the OBD ENET pin (pin 8) may need to be activated on some cars --
  UNVERIFIED for G20; confirm with your cable/bridge documentation.

## Connection sequence
1. Optional UDP vehicle identification (0x0001 -> 0x0004) to discover the gateway IP.
2. TCP connect to gateway:13400.
3. Routing activation 0x0005 (source 0x0E80); expect 0x0006 code 0x10.
4. UDS in 0x8001 frames; 0x8002 acks are skipped; 0x8003 is a failure; NRC 0x78 is awaited.
