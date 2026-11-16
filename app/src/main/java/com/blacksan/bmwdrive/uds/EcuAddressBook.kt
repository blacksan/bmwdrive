package com.blacksan.bmwdrive.uds

/**
 * Curated ECU address book (DoIP logical addresses).
 *
 * VERIFIED: tester 0x0E80, ZGW 0x0010, engine ECU address 0x0012.
 * Everything else is BEST-EFFORT from community EDIABAS/ISTA material and is marked
 * "[unverified]" in its label -- confirm each by scanning on the car. The "DME" label
 * for 0x0012 is itself unverified (address verified, name not).
 */
object EcuAddressBook {
    const val TESTER_ADDRESS = 0x0E80

    val ZGW = EcuAddress(0x0010, "ZGW", "Central gateway")
    val DME = EcuAddress(0x0012, "DME", "Engine control (label unverified)")

    // UNVERIFIED -- best-effort addresses/labels for common G-series modules.
    val EGS = EcuAddress(0x0018, "EGS", "Transmission control [unverified]")
    val DSC = EcuAddress(0x0029, "DSC", "Dynamic stability control [unverified]")
    val EPS = EcuAddress(0x0030, "EPS", "Electric power steering [unverified]")
    val BDC = EcuAddress(0x0040, "BDC", "Body domain controller [unverified]")
    val KOMBI = EcuAddress(0x0060, "KOMBI", "Instrument cluster [unverified]")
    val HU = EcuAddress(0x0063, "HU", "Head unit / MGU [unverified]")
    val ACSM = EcuAddress(0x0001, "ACSM", "Airbag / crash safety module [unverified]")
    val IHKA = EcuAddress(0x0078, "IHKA", "Climate control [unverified]")
    val PMA = EcuAddress(0x0064, "PMA", "Parking assistant [unverified]")
    val TCB = EcuAddress(0x0031, "TCB", "Telematic communication box [unverified]")
    val RDC = EcuAddress(0x0072, "RDC", "Tyre pressure monitoring [unverified]")
    val FLA = EcuAddress(0x0050, "FLA", "High-beam assistant / camera [unverified]")
    val HKFM = EcuAddress(0x0068, "HKFM", "Tailgate function module [unverified]")

    /** Addresses whose logical address has been verified (see class doc). */
    val verifiedAddresses: Set<Int> = setOf(ZGW.logicalAddress, DME.logicalAddress)

    fun isVerified(ecu: EcuAddress): Boolean = ecu.logicalAddress in verifiedAddresses

    /** Default scan order: gateway and engine first. */
    val all: List<EcuAddress> = listOf(
        ZGW, DME, EGS, DSC, EPS, BDC, KOMBI, HU, ACSM, IHKA, PMA, TCB, RDC, FLA, HKFM,
    )

    fun byAddress(address: Int): EcuAddress? = all.firstOrNull { it.logicalAddress == address }
}
