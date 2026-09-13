package app.brix.streaming.chat

/**
 * Twitch IRC-over-WebSocket, minimal wire shape:
 * `[@tags ][:prefix ]<command>[ params...][ :trailing]`
 *
 * Pure parsing, no I/O — that's what makes it testable without a socket.
 * Anything we don't render (NOTICE, JOIN/PART echoes, USERSTATE, ROOMSTATE...)
 * comes back as [TwitchIrcEvent.Other] and the caller just ignores it.
 */
sealed class TwitchIrcEvent {
    data object Ping : TwitchIrcEvent()

    /** Server-initiated reconnect (maintenance, load-balancing) — a command,
     *  not an error. Gated behind the `twitch.tv/commands` capability. */
    data object Reconnect : TwitchIrcEvent()

    data class Privmsg(
        val channel: String,
        val displayName: String,
        val colorHex: String?,
        val text: String,
    ) : TwitchIrcEvent()

    data object Other : TwitchIrcEvent()
}

object TwitchIrcParser {

    fun parse(raw: String): TwitchIrcEvent {
        var rest = raw.trim()
        if (rest.isEmpty()) return TwitchIrcEvent.Other

        var tags: Map<String, String> = emptyMap()
        if (rest.startsWith("@")) {
            val sp = rest.indexOf(' ')
            if (sp == -1) return TwitchIrcEvent.Other
            tags = parseTags(rest.substring(1, sp))
            rest = rest.substring(sp + 1)
        }

        var prefix = ""
        if (rest.startsWith(":")) {
            val sp = rest.indexOf(' ')
            if (sp == -1) return TwitchIrcEvent.Other
            prefix = rest.substring(1, sp)
            rest = rest.substring(sp + 1)
        }

        val trailingSep = rest.indexOf(" :")
        val head: String
        val trailing: String?
        if (trailingSep >= 0) {
            head = rest.substring(0, trailingSep)
            trailing = rest.substring(trailingSep + 2)
        } else {
            head = rest
            trailing = null
        }
        val parts = head.split(' ').filter { it.isNotEmpty() }
        val command = parts.firstOrNull() ?: return TwitchIrcEvent.Other
        val params = parts.drop(1)

        return when (command) {
            "PING" -> TwitchIrcEvent.Ping
            "RECONNECT" -> TwitchIrcEvent.Reconnect
            "PRIVMSG" -> {
                val text = trailing ?: return TwitchIrcEvent.Other
                val channel = params.getOrNull(0)?.removePrefix("#") ?: ""
                val displayName = tags["display-name"]?.takeIf { it.isNotBlank() }
                    ?: prefix.substringBefore('!').takeIf { it.isNotBlank() }
                    ?: return TwitchIrcEvent.Other
                val color = tags["color"]?.takeIf { it.isNotBlank() }
                TwitchIrcEvent.Privmsg(channel = channel, displayName = displayName, colorHex = color, text = stripAction(text))
            }
            else -> TwitchIrcEvent.Other
        }
    }

    /** IRCv3 tags: `key1=val1;key2=val2` with `\:`/`\s`/`\\`/`\r`/`\n` escapes
     *  (spec: https://ircv3.net/specs/extensions/message-tags). We only ever
     *  read plain values (color, display-name), so a light unescape covers it. */
    private fun parseTags(raw: String): Map<String, String> =
        raw.split(';').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) pair to "" else pair.substring(0, eq) to unescapeTagValue(pair.substring(eq + 1))
        }

    private fun unescapeTagValue(value: String): String {
        if ('\\' !in value) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    ':' -> sb.append(';')
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(value[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    /** `/me hi` arrives as CTCP `ACTION hi` — unwrap it so the
     *  screen shows text, not control characters. */
    private fun stripAction(text: String): String {
        val ctcp = '\u0001'
        if (text.length >= 9 && text[0] == ctcp && text.endsWith(ctcp) &&
            text.startsWith("ACTION ", startIndex = 1)
        ) {
            return text.substring(8, text.length - 1)
        }
        return text
    }
}
