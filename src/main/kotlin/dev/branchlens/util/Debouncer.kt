package dev.branchlens.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key coroutine debouncer. Cancels any in-flight job for the same key when a new event arrives.
 */
class Debouncer(private val scope: CoroutineScope) {
    private val pending = ConcurrentHashMap<Any, Job>()

    fun submit(key: Any, delayMs: Long, block: suspend CoroutineScope.() -> Unit) {
        val newJob = scope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(delayMs)
            block()
        }
        val previous = pending.put(key, newJob)
        previous?.cancel()
        newJob.invokeOnCompletion { pending.remove(key, newJob) }
        newJob.start()
    }

    fun cancel(key: Any) {
        pending.remove(key)?.cancel()
    }

    fun cancelAll() {
        for (job in pending.values) job.cancel()
        pending.clear()
    }
}
