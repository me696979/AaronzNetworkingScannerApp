from pathlib import Path

main = Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt')
gradle = Path('app/build.gradle.kts')

s = main.read_text()

# Add Instant import if needed.
if 'import java.time.Instant\n' not in s:
    s = s.replace('import java.text.SimpleDateFormat\n', 'import java.text.SimpleDateFormat\nimport java.time.Instant\n', 1)

# Track the server timestamp at the live edge so stale calls can never be replayed.
needle = '    private var cursor: String? = null\n'
insert = '    private var cursor: String? = null\n    private var liveEdgeReceivedAt: Instant? = null\n'
if 'private var liveEdgeReceivedAt: Instant?' not in s:
    if needle not in s:
        raise SystemExit('cursor field marker not found')
    s = s.replace(needle, insert, 1)

# Reset the timestamp every time we intentionally jump to live edge.
needle = '        cursor = null\n\n        if (controllerFuture.isDone) {'
replace = '        cursor = null\n        liveEdgeReceivedAt = null\n\n        if (controllerFuture.isDone) {'
if needle not in s:
    raise SystemExit('restart cursor reset marker not found')
s = s.replace(needle, replace, 1)

# Replace startup live-edge lookup so we capture both latest ID and its server receive time.
old = '''                val liveEdge = withContext(Dispatchers.IO) {
                    getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=1")
                        ?.optString("latest_id")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                }

                if (!running || generation != scannerGeneration) return

                if (liveEdge.isNullOrBlank()) {
'''
new = '''                val liveEdgeJson = withContext(Dispatchers.IO) {
                    getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=1")
                }

                val liveEdge = liveEdgeJson
                    ?.optString("latest_id")
                    ?.takeIf { it.isNotBlank() && it != "null" }

                val liveEdgeTime = try {
                    liveEdgeJson
                        ?.optJSONArray("calls")
                        ?.optJSONObject(0)
                        ?.optString("received_at")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Instant.parse(it) }
                } catch (_: Exception) {
                    null
                }

                if (!running || generation != scannerGeneration) return

                if (liveEdge.isNullOrBlank()) {
'''
if old not in s:
    raise SystemExit('live edge lookup block not found')
s = s.replace(old, new, 1)

needle = '''                cursor = liveEdge
                findViewById<TextView>(R.id.status).text = "Waiting for next call…"
'''
replace = '''                cursor = liveEdge
                liveEdgeReceivedAt = liveEdgeTime
                findViewById<TextView>(R.id.status).text = "Waiting for next call…"
'''
if needle not in s:
    raise SystemExit('live edge assignment marker not found')
s = s.replace(needle, replace, 1)

# Before playback, reject anything at-or-before the server live-edge timestamp.
needle = '''            val tg = call.optString("talkgroup").toIntOrNull()
            if (tg != null && isTalkgroupBlocked(tg)) continue

            playCallAndWait(call, generation)
'''
replace = '''            // Defensive backlog guard: even if the queue endpoint returns stale
            // records after a restart/feed change, never play anything that was already
            // present when this scanner session established its live edge.
            val callReceivedAt = try {
                call.optString("received_at")
                    .takeIf { it.isNotBlank() }
                    ?.let { Instant.parse(it) }
            } catch (_: Exception) {
                null
            }

            val edgeTime = liveEdgeReceivedAt
            if (edgeTime != null && callReceivedAt != null && !callReceivedAt.isAfter(edgeTime)) {
                continue
            }

            val tg = call.optString("talkgroup").toIntOrNull()
            if (tg != null && isTalkgroupBlocked(tg)) continue

            playCallAndWait(call, generation)
'''
if needle not in s:
    raise SystemExit('poll playback marker not found')
s = s.replace(needle, replace, 1)

main.write_text(s)

g = gradle.read_text()
g = g.replace('versionCode = 15', 'versionCode = 16')
g = g.replace('versionName = "0.2.13"', 'versionName = "0.2.14"')
if 'versionCode = 16' not in g or 'versionName = "0.2.14"' not in g:
    raise SystemExit('version bump failed')
gradle.write_text(g)

# Validate required pieces before CI attempts a build.
check = main.read_text()
for required in [
    'private var liveEdgeReceivedAt: Instant? = null',
    'liveEdgeReceivedAt = liveEdgeTime',
    '!callReceivedAt.isAfter(edgeTime)',
    '/api/call-queue?feed=',
]:
    if required not in check:
        raise SystemExit(f'missing required 0.2.14 marker: {required}')

print('Scannerz 0.2.14 live-edge backlog fix applied and validated')
