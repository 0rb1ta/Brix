package app.brix.core

/**
 * Похоже ли на адрес сервера: схема, `://` и непустой хост.
 *
 * Разбор руками, а не через `java.net.URI`: тот отдаёт `host = null` для имён с
 * подчёркиванием (`belabox_home.local`) и бросает исключение на символах вроде
 * `|` или пробела в streamid — то есть объявлял бы битыми адреса, к которым
 * подключение работает. Здесь цель скромнее: поймать пустое поле и адрес без
 * схемы, а не валидировать URL по RFC.
 */
fun looksLikeServerUrl(raw: String): Boolean {
    val s = raw.trim()
    val sep = s.indexOf("://")
    if (sep <= 0) return false
    val scheme = s.substring(0, sep)
    if (!scheme.first().isLetter() || !scheme.all { it.isLetterOrDigit() || it in "+-." }) return false
    val rest = s.substring(sep + 3)
    val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']').drop(1)
    } else {
        authority.substringBefore(':')
    }
    return host.isNotBlank()
}
