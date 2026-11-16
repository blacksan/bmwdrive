# ENET setup

See docs/RESEARCH.md for what is verified vs unverified.

## Bridge vs direct
- **Bridge (default, recommended).** Plug an ENET-to-WiFi (or ENET-to-TCP) bridge into the OBD
  ENET cable, join its WiFi from the phone, and connect in BRIDGE mode. The phone speaks plain
  TCP to the bridge on port 13400. Typical bridge address: 192.168.16.254 (editable).
- **Direct USB-C ENET cable.** Android only promotes interfaces named `eth0`/`eth1`; USB CDC
  Ethernet adapters appear as `usb0` and are ignored without root. DIRECT mode tries to bind to
  an Ethernet `Network` and fails with a pointer to BRIDGE mode if none appears within a few
  seconds. Binding is process-wide while connected.
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
