package com.blacksan.bmwdrive.deploy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * sshj implementation of [HeadUnitDeployer], mirroring idrive7-apps `push-to-car.sh`.
 *
 * Requires (and cannot provide): a network route to the head unit, root on the MGU and
 * BMW's oap-sdk. The host key is NOT pinned (the unit's key is unknown ahead of time),
 * so use only on a link you trust.
 *
 * `check` and `status` are strictly read-only on the unit.
 */
class SshHeadUnitDeployer : HeadUnitDeployer {

    override suspend fun check(target: SshTarget): Flow<DeployEvent> = session(target) { client ->
        emit(DeployEvent.Stage("Connected and authenticated", 1, CHECK_STAGES))
        emit(DeployEvent.Line("Connected to ${target.host}:${target.port} as ${target.username}"))

        emit(DeployEvent.Stage("Verify kit directory", 2, CHECK_STAGES))
        val kitExit = exec(client, CMD_TEST_KIT_DIR, null)
        if (kitExit != 0) {
            emit(DeployEvent.Failed("Kit directory $KIT_DIR not found on the unit (nothing was changed)."))
            return@session
        }
        emit(DeployEvent.Line("$KIT_DIR present"))

        emit(DeployEvent.Stage("Installer check (read-only)", 3, CHECK_STAGES))
        val exit = exec(client, CMD_CHECK, null)
        finish(exit, "Installer check")
    }

    override suspend fun install(target: SshTarget, kitPath: String): Flow<DeployEvent> =
        session(target) { client ->
            val kit = File(kitPath)
            if (!kit.exists()) {
                emit(DeployEvent.Failed("Kit not found on the phone: $kitPath"))
                return@session
            }
            emit(DeployEvent.Stage("Connected and authenticated", 1, INSTALL_STAGES))

            emit(DeployEvent.Stage("Upload kit", 2, INSTALL_STAGES))
            emit(DeployEvent.Line("Uploading ${kit.name} to $KIT_PARENT_DIR ..."))
            client.newSCPFileTransfer().upload(kit.absolutePath, "$KIT_PARENT_DIR/")
            emit(DeployEvent.Line("Upload complete"))

            // Remote installer prints progress; stage events are derived from its output.
            val exit = exec(client, CMD_INSTALL, INSTALL_STAGE_KEYWORDS)
            finish(exit, "Install")
        }

    override suspend fun restore(target: SshTarget): Flow<DeployEvent> = session(target) { client ->
        emit(DeployEvent.Stage("Connected and authenticated", 1, RESTORE_STAGES))
        emit(DeployEvent.Stage("Restore stock files", 2, RESTORE_STAGES))
        // The unit prompts for YES; the UI has already obtained explicit user confirmation.
        val exit = exec(client, CMD_RESTORE, null)
        finish(exit, "Restore")
    }

    override suspend fun status(target: SshTarget): Flow<DeployEvent> = session(target) { client ->
        emit(DeployEvent.Stage("Connected and authenticated", 1, STATUS_STAGES))
        emit(DeployEvent.Stage("Query status", 2, STATUS_STAGES))
        val s = exec(client, CMD_STATUS, null)
        if (s != 0) {
            finish(s, "Status")
            return@session
        }
        emit(DeployEvent.Stage("Query backups", 3, STATUS_STAGES))
        val b = exec(client, CMD_BACKUPS, null)
        finish(b, "Backups")
    }

    // ---- plumbing ---------------------------------------------------------------------

    private suspend fun FlowCollector<DeployEvent>.finish(exit: Int?, what: String) {
        if (exit == 0) emit(DeployEvent.Done)
        else emit(DeployEvent.Failed("$what failed (remote exit status ${exit ?: "unknown"})."))
    }

    /**
     * Opens a connection, runs [body], and always closes the client. Any exception is
     * mapped to a [DeployEvent.Failed] with a human-readable reason.
     */
    private fun session(
        target: SshTarget,
        body: suspend FlowCollector<DeployEvent>.(SSHClient) -> Unit,
    ): Flow<DeployEvent> = flow {
        val client = SSHClient()
        try {
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.timeout = SOCKET_TIMEOUT_MS
            emit(DeployEvent.Line("Connecting to ${target.host}:${target.port} ..."))
            client.connect(target.host, target.port)
            authenticate(client, target)
            body(client)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DeployEvent.Failed(describe(e, target)))
        } finally {
            try {
                if (client.isConnected) client.disconnect()
            } catch (_: IOException) {
            }
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun authenticate(client: SSHClient, target: SshTarget) {
        val pem = target.privateKeyPem
        val pw = target.password
        when {
            !pem.isNullOrBlank() -> {
                val keys = client.loadKeys(pem, null, null)
                client.authPublickey(target.username, keys)
            }
            pw != null -> client.authPassword(target.username, pw)
            else -> throw UserAuthException("No password or private key supplied")
        }
    }

    /**
     * Runs [command] (stderr merged into stdout), emitting each line. If [stageKeywords]
     * is given, the first line mentioning a keyword emits a Stage. Returns the remote exit
     * status, or null if the server did not report one.
     */
    private suspend fun FlowCollector<DeployEvent>.exec(
        client: SSHClient,
        command: String,
        stageKeywords: List<Pair<String, String>>?,
    ): Int? {
        val sess = client.startSession()
        try {
            val cmd = sess.exec("$command 2>&1")
            var stageIdx = 0
            cmd.inputStream.bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (stageKeywords != null && stageIdx < stageKeywords.size) {
                        val (kw, label) = stageKeywords[stageIdx]
                        if (line.contains(kw, ignoreCase = true)) {
                            // Stages 1 and 2 (connect, upload) were already emitted.
                            emit(DeployEvent.Stage(label, stageIdx + 3, INSTALL_STAGES))
                            stageIdx++
                        }
                    }
                    emit(DeployEvent.Line(line))
                }
            }
            cmd.join(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)
            return cmd.exitStatus
        } finally {
            try {
                sess.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun describe(e: Exception, t: SshTarget): String = when (e) {
        is UserAuthException ->
            "Authentication failed for ${t.username}@${t.host}. Check the password/key; root SSH must be enabled on the unit."
        is ConnectException ->
            "Connection refused by ${t.host}:${t.port}. Is SSH running on the head unit, and is the port right?"
        is SocketTimeoutException ->
            "Timed out talking to ${t.host}:${t.port}. No route to the head unit? (The ENET OBD cable does not reach it.)"
        is NoRouteToHostException ->
            "No route to ${t.host}. The phone needs a network path to the head unit's own Ethernet."
        is UnknownHostException -> "Unknown host: ${t.host}"
        is TransportException -> "SSH transport error: ${e.message ?: e.javaClass.simpleName}"
        is ConnectionException -> "SSH connection error: ${e.message ?: e.javaClass.simpleName}"
        is IOException -> "I/O error: ${e.message ?: e.javaClass.simpleName}"
        else -> "Unexpected error: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val KIT_DIR = "/var/opt/mgu-custom-apps"
        const val KIT_PARENT_DIR = "/var/opt"
        const val REMOTE_INSTALLER = "$KIT_DIR/install.sh"

        const val CMD_TEST_KIT_DIR = "test -d $KIT_DIR"
        const val CMD_CHECK = "$REMOTE_INSTALLER check"
        const val CMD_INSTALL = "$REMOTE_INSTALLER install"
        const val CMD_RESTORE = "echo YES | $REMOTE_INSTALLER restore"
        const val CMD_STATUS = "$REMOTE_INSTALLER status"
        const val CMD_BACKUPS = "$REMOTE_INSTALLER backups"

        const val CHECK_STAGES = 3
        const val INSTALL_STAGES = 6
        const val RESTORE_STAGES = 2
        const val STATUS_STAGES = 3

        /** Ordered (output keyword, stage label); stage index = position + 3. */
        val INSTALL_STAGE_KEYWORDS = listOf(
            "backup" to "Back up touched files",
            "unpack" to "Unpack kit",
            "config" to "Regenerate SOME/IP configs",
            "restart" to "Restart app platform",
        )

        const val CONNECT_TIMEOUT_MS = 10_000
        const val SOCKET_TIMEOUT_MS = 30_000
        const val EXEC_TIMEOUT_SEC = 600L
    }
}
