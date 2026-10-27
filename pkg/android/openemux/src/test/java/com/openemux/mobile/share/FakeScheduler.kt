package com.openemux.mobile.share

/** A [Scheduler] on a clock the test moves by hand. */
class FakeScheduler(var time: Long = 1_000_000L) : Scheduler {
    private class Task(val at: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    override fun now() = time

    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val task = Task(time + delayMs, block)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    /** Moves the clock, running what falls due on the way, in order. */
    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            time = next.at
            next.block()
        }
        time = end
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}
