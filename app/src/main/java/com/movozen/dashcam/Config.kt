package com.movozen.dashcam

/**
 * MoboSafe Pocket Dashcam Challenge — fixed endpoints from the brief.
 *
 *   PUSH FRONT : rtmp://15.207.177.194:1936/hackathon/{ROLLNO}_front
 *   PUSH BACK  : rtmp://15.207.177.194:1936/hackathon/{ROLLNO}_back
 *   VIEWER     : http://15.207.177.194:8081/web/player.html
 */
object Config {

    const val RTMP_BASE = "rtmp://15.207.177.194:1936/hackathon/"
    const val VIEWER_URL = "http://15.207.177.194:8081/web/player.html"

    // Encoder settings recommended by the brief.
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val VIDEO_BITRATE = 2000 * 1000     // 2 Mbps
    const val SAMPLE_RATE = 44100
    const val STEREO = true
    const val AUDIO_BITRATE = 128 * 1000      // 128 kbps AAC

    const val RECONNECT_DELAY_MS = 3000L

    /**
     * Candidate stream keys, cycled by the KEY button.
     *
     * Slashes/dashes in a roll number break an RTMP path (everything after the
     * app name is parsed as extra path segments), so we always offer a
     * separator-free form and a digits-only form as fallbacks.
     *
     *   "BTECH2502322"   -> [BTECH2502322, 2502322]
     *   "BTECH/25002/23" -> [BTECH_25002_23, BTECH2500223, 2500223]
     */
    fun keyVariants(raw: String): List<String> {
        val clean = raw.trim().uppercase().filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return emptyList()
        val underscored = clean.replace("/", "_").replace("-", "_").replace("\\", "_")
        val stripped = clean.filter { it.isLetterOrDigit() }
        val digits = clean.filter { it.isDigit() }
        return listOf(underscored, stripped, digits).distinct().filter { it.isNotEmpty() }
    }

    fun frontUrl(key: String) = RTMP_BASE + key.trim() + "_front"
    fun backUrl(key: String) = RTMP_BASE + key.trim() + "_back"
}
