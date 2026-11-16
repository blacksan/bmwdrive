package com.blacksan.bmwdrive.deploy

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted fake so the Deploy screen can be demonstrated without a real head unit. */
class DemoHeadUnitDeployer : HeadUnitDeployer {

    override suspend fun check(target: SshTarget): Flow<DeployEvent> = script(
        DeployEvent.Line("[demo] Connecting to ${target.host}:${target.port} ..."),
        DeployEvent.Stage("Connected and authenticated", 1, 3),
        DeployEvent.Stage("Verify kit directory", 2, 3),
        DeployEvent.Line("[demo] /var/opt/mgu-custom-apps present"),
        DeployEvent.Stage("Installer check (read-only)", 3, 3),
        DeployEvent.Line("[demo] root: ok"),
        DeployEvent.Line("[demo] oap-sdk: found"),
        DeployEvent.Line("[demo] free space: 412 MB"),
        DeployEvent.Done,
    )

    override suspend fun install(target: SshTarget, kitPath: String): Flow<DeployEvent> = script(
        DeployEvent.Stage("Connected and authenticated", 1, 6),
        DeployEvent.Stage("Upload kit", 2, 6),
        DeployEvent.Line("[demo] Uploading $kitPath ..."),
        DeployEvent.Stage("Back up touched files", 3, 6),
        DeployEvent.Line("[demo] backup #1 (stock) created"),
        DeployEvent.Stage("Unpack kit", 4, 6),
        DeployEvent.Line("[demo] unpacked 38 files"),
        DeployEvent.Stage("Regenerate SOME/IP configs", 5, 6),
        DeployEvent.Line("[demo] someip.json regenerated"),
        DeployEvent.Stage("Restart app platform", 6, 6),
        DeployEvent.Line("[demo] MainApp restarted"),
        DeployEvent.Done,
    )

    override suspend fun restore(target: SshTarget): Flow<DeployEvent> = script(
        DeployEvent.Stage("Connected and authenticated", 1, 2),
        DeployEvent.Stage("Restore stock files", 2, 2),
        DeployEvent.Line("[demo] Reverting to first (stock) backup ..."),
        DeployEvent.Line("[demo] 12 files restored"),
        DeployEvent.Done,
    )

    override suspend fun status(target: SshTarget): Flow<DeployEvent> = script(
        DeployEvent.Stage("Connected and authenticated", 1, 3),
        DeployEvent.Stage("Query status", 2, 3),
        DeployEvent.Line("[demo] custom apps: installed"),
        DeployEvent.Stage("Query backups", 3, 3),
        DeployEvent.Line("[demo] backup #1 (stock)"),
        DeployEvent.Line("[demo] backup #2"),
        DeployEvent.Done,
    )

    private fun script(vararg events: DeployEvent): Flow<DeployEvent> = flow {
        for (e in events) {
            delay(450)
            emit(e)
        }
    }
}
