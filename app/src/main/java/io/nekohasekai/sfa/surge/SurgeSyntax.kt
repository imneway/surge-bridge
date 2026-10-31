package io.nekohasekai.sfa.surge

class SurgeException(val line: Int, val reason: String) : IllegalArgumentException("Surge line $line: $reason")

internal data class Entry(val line: Int, val text: String)

internal object SurgeSyntax {
    /** Sections whose entries participate in conversion. Others are ignored with a notice. */
    val CONVERTED_SECTIONS = setOf("general", "proxy", "proxy group", "rule", "host")

    fun split(text: String, line: Int, separator: Char = ','): List<String> {
        val result = mutableListOf<String>()
        val part = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (c in text) {
            if (escaped) {
                part.append(c)
                escaped = false
                continue
            }
            if (quote != null) {
                if (c == '\\') {
                    escaped = true
                    part.append(c)
                } else {
                    part.append(c)
                    if (c == quote) quote = null
                }
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    quote = c
                    part.append(c)
                }
                c == '(' -> {
                    depth++
                    part.append(c)
                }
                c == ')' -> {
                    depth--
                    if (depth < 0) fail(line, "unbalanced parentheses")
                    part.append(c)
                }
                c == separator && depth == 0 -> {
                    result.add(part.toString().trim())
                    part.setLength(0)
                }
                else -> part.append(c)
            }
        }
        if (quote != null || escaped || depth != 0) fail(line, "unterminated quote or parentheses")
        result.add(part.toString().trim())
        return result
    }

    fun value(text: String): String {
        val t = text.trim()
        if (t.length < 2 || t.first() !in "\"'" || t.last() != t.first()) return t
        return t.substring(1, t.length - 1).replace(Regex("\\\\(.)"), "$1")
    }

    fun assignment(entry: Entry): Pair<String, String> {
        val at = entry.text.indexOf('=')
        if (at <= 0) fail(entry.line, "expected name = value")
        return value(entry.text.substring(0, at)) to entry.text.substring(at + 1).trim()
    }

    fun options(fields: List<String>, line: Int): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (field in fields) {
            val (key, value) = assignment(Entry(line, field))
            if (result.put(key.lowercase(), value(value)) != null) fail(line, "duplicate parameter $key")
        }
        return result
    }

    fun boolean(value: String, line: Int): Boolean = when (value.lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> fail(line, "expected true or false")
    }

    fun positive(value: String, line: Int): Int = value.toIntOrNull()?.takeIf { it > 0 }
        ?: fail(line, "expected a positive integer")

    fun clean(text: String): String {
        var quote: Char? = null
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (quote != null) {
                if (c == '\\') {
                    escaped = true
                } else if (c == quote) {
                    quote = null
                }
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if ((c == '#' || c == ';' || (c == '/' && text.getOrNull(i + 1) == '/')) &&
                (i == 0 || text[i - 1].isWhitespace())
            ) {
                return text.substring(0, i).trim()
            }
        }
        return text.trim()
    }

    /** Returns every section, including ones without an Android equivalent, keyed by lowercase name. */
    fun sections(source: String): Map<String, List<Entry>> {
        val sections = linkedMapOf<String, MutableList<Entry>>()
        var section: String? = null
        source.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val text = clean(raw)
            if (text.isEmpty()) return@forEachIndexed
            if (text.startsWith('[') && text.endsWith(']')) {
                section = text.substring(1, text.length - 1).trim().lowercase()
                if (section.isEmpty()) fail(index + 1, "empty section name")
                sections.getOrPut(section) { mutableListOf() }
            } else {
                val current = section ?: fail(index + 1, "content outside a section")
                sections.getValue(current).add(Entry(index + 1, text))
            }
        }
        return sections
    }

    fun fail(line: Int, message: String): Nothing = throw SurgeException(line, message)
}
