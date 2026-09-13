package app.brix.streaming.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** One feed on the streamer's screen from up to three independent clients —
 *  merged by timestamp, capped the same way each source caps itself. A
 *  disabled/stopped client just contributes an empty list forever, so
 *  callers don't need to conditionally assemble this list. */
fun mergeChatMessages(sources: List<Flow<List<ChatMessage>>>, maxMessages: Int): Flow<List<ChatMessage>> {
    if (sources.isEmpty()) return flowOf(emptyList())
    return combine(sources) { arrays ->
        arrays.asSequence()
            .flatMap { it.asSequence() }
            .sortedBy { it.timestampMs }
            .toList()
            .takeLast(maxMessages)
    }
}

/** "Connected" for the merged panel means at least one enabled source is —
 *  a single green dot for however many platforms are configured. */
fun mergeConnected(sources: List<Flow<Boolean>>): Flow<Boolean> {
    if (sources.isEmpty()) return flowOf(false)
    return combine(sources) { arr -> arr.any { it } }
}
