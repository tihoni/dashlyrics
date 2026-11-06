package com.autolyrics.app.data

object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")

    // Patterns in title that should be stripped for lyrics search
    private val TITLE_SANITIZE_PATTERNS = listOf(
        // Audio quality badges that Spotify occasionally appends
        Regex("""\s*[•·|\-]\s*(?:Lossless|HiFi|Premium|Master|Dolby Atmos|Spatial Audio|HD|Ultra HD|360 Reality Audio).*$""", RegexOption.IGNORE_CASE),
        // Remaster annotations
        Regex("""\s*-\s*(?:Remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+Remaster(?:ed)?|Single Version|Radio Edit|Deluxe Edition|Live.*|Mono.*|Stereo.*|Explicit|Clean|Bonus Track|From\s".*").*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\((?:Remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+Remaster(?:ed)?|Deluxe|Bonus|Explicit|Clean|Live.*|Radio Edit|Single|Version|feat\.|ft\.|with\s|Mono|Stereo|Original.*)[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[(?:Remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+Remaster(?:ed)?|Deluxe|Bonus|Explicit|Clean|Live.*|Radio Edit|Single|Version|feat\.|ft\.|with\s|Mono|Stereo|Original.*)[^\]]*]""", RegexOption.IGNORE_CASE),
        // Featuring credits
        Regex("""\s*(?:feat\.|ft\.|featuring)\s+.*$""", RegexOption.IGNORE_CASE),
        // Trailing bullets or hyphens
        Regex("""\s*[•·|]\s*.*$""")
    )

    // Patterns in artist that should be stripped for lyrics search
    private val ARTIST_SANITIZE_PATTERNS = listOf(
        // Spotify quality tags like "Chuck Berry • Lossless" or "Artist - HiFi"
        Regex("""\s*[•·|\-]\s*(?:Lossless|HiFi|Premium|Master|Dolby Atmos|Spatial Audio|HD|Ultra HD|360 Reality Audio|FLAC|Audio).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[•·|]\s*.*$"""),
        // Featuring credits in artist field
        Regex("""\s*(?:feat\.|ft\.|featuring|with)\s+.*$""", RegexOption.IGNORE_CASE)
    )

    /**
     * Cleans track titles by stripping remaster notes, audio quality badges, and featuring credits.
     */
    fun sanitizeTitle(rawTitle: String): String {
        var cleaned = rawTitle.trim()
        for (pattern in TITLE_SANITIZE_PATTERNS) {
            cleaned = pattern.replace(cleaned, "")
        }
        return cleaned.trim().ifEmpty { rawTitle.trim() }
    }

    /**
     * Cleans artist names, especially stripping Spotify's streaming quality suffixes
     * (e.g. "Chuck Berry • Lossless" -> "Chuck Berry").
     */
    fun sanitizeArtist(rawArtist: String): String {
        var cleaned = rawArtist.trim()
        for (pattern in ARTIST_SANITIZE_PATTERNS) {
            cleaned = pattern.replace(cleaned, "")
        }
        return cleaned.trim().ifEmpty { rawArtist.trim() }
    }

    /**
     * Extracts the primary artist name if multiple artists are listed
     * (e.g. "Chuck Berry, Bo Diddley" -> "Chuck Berry" or "Artist A & Artist B" -> "Artist A").
     */
    fun extractPrimaryArtist(artist: String): String {
        val clean = sanitizeArtist(artist)
        val first = clean.split(Regex("""\s*(?:,|&|\band\b|/|\\)\s*""")).firstOrNull()?.trim()
        return if (!first.isNullOrBlank()) first else clean
    }

    /**
     * Normalizes punctuation (removes periods, smart quotes, etc.)
     * for fuzzy matching (e.g., "Johnny B. Goode" -> "Johnny B Goode").
     */
    fun normalizePunctuation(text: String): String {
        return text
            .replace(Regex("""[.\-–—'’"“”]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Parses standard LRC timestamps [mm:ss.xx] or [mm:ss.xxx] into
     * timeMs = (minutes * 60000) + (seconds * 1000) + (fractionMs).
     */
    fun parseSyncedLrc(lrcContent: String): List<LyricLine> {
        if (lrcContent.isBlank()) return emptyList()

        val parsedLines = mutableListOf<LyricLine>()

        for (rawLine in lrcContent.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val lyricText = trimmed.substring(matches.last().range.last + 1).trim()

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionStr = match.groupValues[3]

                val fractionMs = when (fractionStr.length) {
                    0 -> 0L
                    1 -> (fractionStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionStr.toLongOrNull() ?: 0L) * 10L
                    else -> fractionStr.take(3).toLongOrNull() ?: 0L
                }

                val timeMs = (minutes * 60_000L) + (seconds * 1_000L) + fractionMs
                parsedLines.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = lyricText.ifEmpty { "♪" }
                    )
                )
            }
        }

        return parsedLines.sortedBy { it.timeMs }
    }

    /**
     * Converts plain unsynced lyrics into LyricLine items with synthetic timestamps
     * distributed evenly across the song's duration, allowing auto-scrolling
     * and glanceable progression on Android Auto even without exact LRC timestamps.
     */
    fun parsePlainLyrics(plainContent: String, durationMs: Long = 0L): List<LyricLine> {
        if (plainContent.isBlank()) return emptyList()

        val rawLines = plainContent
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("Lead & Backing Vocals") }
            .toList()

        if (rawLines.isEmpty()) return emptyList()

        val effectiveDuration = if (durationMs > 15_000L) durationMs else (rawLines.size * 4_000L)
        val intervalMs = (effectiveDuration / rawLines.size.coerceAtLeast(1)).coerceIn(2_500L, 8_000L)

        return rawLines.mapIndexed { index, line ->
            LyricLine(timeMs = index * intervalMs, text = line)
        }
    }
}
