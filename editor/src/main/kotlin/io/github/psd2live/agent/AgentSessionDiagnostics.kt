package io.github.psd2live.agent

enum class AgentSessionEventKind { INITIALIZED, INITIALIZED_REPEATED, DELETE_REQUESTED, CLOSED }

/** A DELETE was being handled, the endpoint stopped, or the transport closed for an unspecified reason. */
enum class AgentSessionCloseReason { CLIENT_DELETE, SERVER_STOP, TRANSPORT_CLOSED }

data class AgentSessionEvent(
    val kind: AgentSessionEventKind,
    val sessionId: String,
    val client: String,
    /** Live sessions that have received notifications/initialized. */
    val activeSessions: Int,
    val initializationCount: Int,
    val lifetimeMs: Long,
    val closeReason: AgentSessionCloseReason? = null,
    /** First successful handshake for this client name/version during this endpoint's lifetime. */
    val firstClientConnection: Boolean = false,
)

/** Serializes lifecycle observations so counts and event order agree even across concurrent HTTP requests. */
class AgentSessionDiagnostics(
    private val observer: AgentCallObserver?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Session(val startedAt: Long) {
        var client = "MCP client"
        var initializationCount = 0
        var pendingDeletes = 0
    }

    private val sessions = linkedMapOf<String, Session>()
    private val connectedClients = mutableSetOf<String>()
    private var stopping = false

    @Synchronized fun opened(id: String) {
        sessions.putIfAbsent(id, Session(nanoTime()))
    }

    @Synchronized fun initialized(id: String, client: String) {
        val session = sessions[id] ?: return
        session.client = client
        session.initializationCount++
        val firstClientConnection = session.initializationCount == 1 && connectedClients.add(client)
        emit(if (session.initializationCount == 1) AgentSessionEventKind.INITIALIZED else AgentSessionEventKind.INITIALIZED_REPEATED,
            id, session, firstClientConnection = firstClientConnection)
    }

    @Synchronized fun deleteRequested(id: String) {
        val session = sessions[id] ?: return
        session.pendingDeletes++
        emit(AgentSessionEventKind.DELETE_REQUESTED, id, session)
    }

    /** A rejected DELETE must not become the explanation for a later, unrelated close. */
    @Synchronized fun deleteFinished(id: String) {
        sessions[id]?.let { it.pendingDeletes = (it.pendingDeletes - 1).coerceAtLeast(0) }
    }

    @Synchronized fun closed(id: String) {
        val session = sessions.remove(id) ?: return
        val reason = when {
            stopping -> AgentSessionCloseReason.SERVER_STOP
            session.pendingDeletes > 0 -> AgentSessionCloseReason.CLIENT_DELETE
            else -> AgentSessionCloseReason.TRANSPORT_CLOSED
        }
        emit(AgentSessionEventKind.CLOSED, id, session, reason)
    }

    @Synchronized fun serverStopping() { stopping = true }

    /** The HTTP endpoint is gone even if the client never sent DELETE. */
    @Synchronized fun serverStopped() {
        stopping = true
        sessions.keys.toList().forEach(::closed)
    }

    private fun emit(kind: AgentSessionEventKind, id: String, session: Session, reason: AgentSessionCloseReason? = null,
                     firstClientConnection: Boolean = false) {
        val event = AgentSessionEvent(
            kind, id, session.client, sessions.values.count { it.initializationCount > 0 },
            session.initializationCount, ((nanoTime() - session.startedAt) / 1_000_000).coerceAtLeast(0), reason, firstClientConnection,
        )
        runCatching { observer?.onSessionEvent(event) }
    }
}
