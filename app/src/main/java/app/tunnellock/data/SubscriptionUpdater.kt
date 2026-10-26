package app.tunnellock.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Downloads subscriptions for the screen (on demand) and for the tunnel service
 * (in the background, when they are old). Never fetches the same one twice at once.
 * A failed download keeps the old servers.
 */
object SubscriptionUpdater {
    private const val TAG = "Subscription"
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updating: StateFlow<Set<String>> = _updating

    /** Returns null if this subscription is already being updated. */
    suspend fun update(store: AppStore, sub: Subscription): Result<SubscriptionResult>? {
        var claimed = false
        _updating.update {
            claimed = sub.id !in it
            if (claimed) it + sub.id else it
        }
        if (!claimed) return null
        return try {
            withContext(Dispatchers.IO) { runCatching { SubscriptionFetcher.fetch(sub.url) } }
                .onSuccess { store.replaceSubscriptionServers(sub.id, it.report.servers, it.usage) }
        } finally {
            _updating.update { it - sub.id }
        }
    }

    /** Refreshes the subscriptions not updated in the last [MAX_AGE_MS], if the user turned that on. */
    suspend fun updateStale(store: AppStore) {
        if (!store.autoUpdateSubscriptions.value) return
        val now = System.currentTimeMillis()
        val stale = store.subscriptions.value.filter { now - it.updatedAt > MAX_AGE_MS }
        if (stale.isEmpty()) return
        AppLog.i(TAG, "auto-update: ${stale.size} subscription(s) older than 12h")
        for (sub in stale) {
            update(store, sub)
                ?.onSuccess { AppLog.i(TAG, "auto-update: ${it.report.servers.size} servers") }
                ?.onFailure { AppLog.w(TAG, "auto-update failed, keeping the old servers: ${it.message}") }
        }
    }
}
