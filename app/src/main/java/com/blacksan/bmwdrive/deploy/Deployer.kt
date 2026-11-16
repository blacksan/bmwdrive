package com.blacksan.bmwdrive.deploy

import kotlinx.coroutines.flow.Flow

/**
 * OPTIONAL head-unit deployer. Mirrors the idrive7-apps `push-to-car.sh` flow:
 * it SSHes to the MGU head unit and runs check / install / restore / status.
 *
 * HARD PRECONDITIONS this module cannot satisfy on its own and must surface to the
 * user before any action:
 *   - a ROUTE from the phone to the head unit's own Ethernet (socnet0). The ENET
 *     *OBD* cable reaches the DoIP diagnostic gateway, NOT the head unit SSH service;
 *     a separate link/route is required.
 *   - ROOT shell access on the MGU (the idrive7-apps project does not provide it).
 *   - BMW's `oap-sdk`, extracted from the user's own unit.
 *
 * The UI gates every destructive step and makes these preconditions explicit.
 */
interface HeadUnitDeployer {

    /** Validate reachability + credentials + kit presence without changing anything. */
    suspend fun check(target: SshTarget): Flow<DeployEvent>

    /** Back up, install the custom-apps kit, and restart the app platform. */
    suspend fun install(target: SshTarget, kitPath: String): Flow<DeployEvent>

    /** Revert touched files to the first (stock) backup — asks the unit to confirm. */
    suspend fun restore(target: SshTarget): Flow<DeployEvent>

    /** Query install status + backups on the unit. */
    suspend fun status(target: SshTarget): Flow<DeployEvent>
}

data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    /** Optional key material; password auth is also supported by the impl. */
    val privateKeyPem: String? = null,
    val password: String? = null,
)

/** Streamed progress so the UI can show a live log of the remote operation. */
sealed interface DeployEvent {
    data class Line(val text: String) : DeployEvent
    data class Stage(val name: String, val index: Int, val total: Int) : DeployEvent
    data class Failed(val reason: String) : DeployEvent
    data object Done : DeployEvent
}
