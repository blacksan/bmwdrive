# Research findings: ENET / DoIP on Android

Legend: **[V]** verified, **[U]** unverified / community-sourced -- confirm on the car.

## Android Ethernet reality (corrected)
- **[V]** Modern Android exposes a USB-C Ethernet adapter as a `TRANSPORT_ETHERNET`
  `android.net.Network`, and an app can route its sockets over it **without root** via
  `ConnectivityManager.requestNetwork(...)` + `bindProcessToNetwork(...)` (or
  `network.socketFactory`). This is how BimmerLink and similar apps talk to BMW ENET over a
  plain USB-C ENET cable / adapter. KEY detail: the request must **remove**
  `NET_CAPABILITY_INTERNET`, because the car's diagnostic link has no internet and the default
  request would never match.
- **[U]** The one real requirement is a kernel driver for the adapter's chipset. The common
  ASIX AX887xx, Realtek RTL815x and CDC-ECM/NCM controllers are widely included on mainstream
  phones; some phones need the wired connection enabled once in Settings. Phones without the
  driver simply won't surface the interface.
- An earlier draft of this doc claimed Android ignores non-`eth\d` adapters without root. That
  over-generalised an AOSP/embedded case and is **not** true for the mainstream USB-Ethernet
  path — corrected here.
- Fallback for unsupported phones: a **TCP bridge** (ENET-to-WiFi / ENET-to-TCP device on the
  OBD ENET cable; the ediabaslib "ENET WiFi Adapter" pattern).

The app supports three modes (`EnetMode`): **DIRECT** (default, USB-C ENET tethering, no root),
**BRIDGE** (fallback), **DEMO**.

## DoIP (ISO 13400)
- **[V]** Ports: TCP and UDP 13400.
- **[V]** Payload types: 0x0001 vehicle ID request, 0x0003 vehicle ID by VIN, 0x0004 announcement
  (all UDP); 0x0005 routing activation request, 0x0006 routing activation response (TCP);
  0x8001 diagnostic message; 0x8002 positive ack; 0x8003 negative ack.
- Header: `02 FD <type:2> <length:4>` then payload (see `transport/Doip.kt`).
- **[V]** Flow: UDP vehicle ID/announcement to discover the gateway IP -> TCP connect 13400 ->
  routing activation 0x0005 with tester source address **0x0E80** -> 0x0006 with code **0x10** =
  success -> wrap UDS in 0x8001 (source 0x0E80, target = ECU logical address).
- **[V]** A 0x8002 ack means "queued", NOT that the UDS service succeeded.
- **[V]** Car IP: DHCP if a server is present, else Auto-IP 169.254.x.x. Example tester
  169.254.1.10 / 255.255.0.0.
- **[U]** 160.48.199.x is commonly cited but unconfirmed -- kept only as the last-resort fallback
  (`EnetConfig.DEFAULT_GATEWAY_IP`). Prefer discovery / Auto-IP.
- **[U]** Whether a given bridge forwards UDP broadcasts. If not, discovery fails and the
  configured bridge IP is used.
- **[U]** Bridge default 192.168.16.254 is a typical value, not universal.

## Logical addresses
- **[V]** Tester 0x0E80, ZGW gateway 0x0010, engine ECU 0x0012 (the "DME" label is unverified).
- **[U]** All other entries in `EcuAddressBook` (EGS, DSC, EPS, BDC, KOMBI, HU, ...).

## UDS
- **[V]** Services 0x10, 0x14, 0x19 (sub 0x02 reportDTCByStatusMask), 0x22, 0x27.
- **[U]** Community-general: 0x19/0x22 reads usually work in the default session; 0x14 clear and
  writes need the extended session (0x10 0x03); protected operations need 0x27 SecurityAccess
  with a proprietary BMW seed/key. The key algorithm is NOT implemented
  (`RealUdsClient.computeSecurityKey` returns NotSupported).
- TesterPresent (0x3E) is sent periodically to hold a session (`RealUdsClient.startKeepAlive`).

## Data identifiers
- There is **no verified B48 DID list**. `CandidateDids` holds a few labelled candidates
  (standard ISO 14229 F1xx identifiers), all **[U]**.
- The portable option is OBD-II mode 01 PIDs: 0x0C rpm, 0x0D speed, 0x05 coolant
  (`RealUdsClient.readObdPid`). **[U]** whether the DME answers mode 01 over DoIP, and which
  addressing (physical 0x0012 vs a functional address) the gateway accepts.
- SOME/IP high-rate telemetry is NOT reachable over the OBD gateway.

## Fault codes
- 0x20F220 is described as coolant / HMM-related per the owner's notes **[U]**. The fault table
  is otherwise empty beyond a generic fallback; extend `FaultText`.
