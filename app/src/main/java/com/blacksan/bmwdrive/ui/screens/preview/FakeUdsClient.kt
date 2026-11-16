package com.blacksan.bmwdrive.ui.screens.preview

import com.blacksan.bmwdrive.uds.DiagSession
import com.blacksan.bmwdrive.uds.EcuAddress
import com.blacksan.bmwdrive.uds.FaultCode
import com.blacksan.bmwdrive.uds.UdsClient
import kotlinx.coroutines.delay

/** Small ECU book used by previews/demo mode. The real book comes from the EdiabasLib set. */
val DemoEcuBook = listOf(
    EcuAddress(0x0012, "DME", "Digital Motor Electronics"),
    EcuAddress(0x0018, "EGS", "Transmission Control"),
    EcuAddress(0x0029, "DSC", "Dynamic Stability Control"),
    EcuAddress(0x0063, "KOMBI", "Instrument Cluster"),
    EcuAddress(0x0010, "ZGW", "Central Gateway"),
)

/** In-memory [UdsClient] returning sample faults, so previews and demo mode need no transport. */
class FakeUdsClient(
    private val latencyMs: Long = 350,
    initial: List<FaultCode> = sampleFaults(),
) : UdsClient {
    private var faults: List<FaultCode> = initial

    override suspend fun openSession(ecu: EcuAddress, session: DiagSession): Result<Unit> = Result.success(Unit)

    override suspend fun readFaults(ecu: EcuAddress): Result<List<FaultCode>> {
        delay(latencyMs)
        return Result.success(faults.filter { it.ecu.logicalAddress == ecu.logicalAddress })
    }

    override suspend fun scanAll(book: List<EcuAddress>): Result<List<FaultCode>> {
        delay(latencyMs * 2)
        val addresses = book.map { it.logicalAddress }.toSet()
        return Result.success(faults.filter { it.ecu.logicalAddress in addresses })
    }

    override suspend fun clearFaults(ecu: EcuAddress, functional: Boolean): Result<Unit> {
        delay(latencyMs)
        faults = if (functional) emptyList() else faults.filterNot { it.ecu.logicalAddress == ecu.logicalAddress }
        return Result.success(Unit)
    }

    override suspend fun readDataById(ecu: EcuAddress, did: Int): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("FakeUdsClient has no DID data"))

    companion object {
        fun sampleFaults(): List<FaultCode> = listOf(
            FaultCode(DemoEcuBook[0], 0x20F220, 0x2F, "Charge-air pressure: below target"),
            FaultCode(DemoEcuBook[0], 0x2FA4A1, 0x09, "Fuel rail pressure sensor: plausibility"),
            FaultCode(DemoEcuBook[2], 0x5E2C, 0x2F, "Wheel speed sensor, front left: signal implausible"),
            FaultCode(DemoEcuBook[3], 0x930701, 0x08, "Communication: no message from head unit"),
        )
    }
}
