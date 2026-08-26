from pathlib import Path

p = Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt')
s = p.read_text()

old = '''        scope.launch { loadTalkgroups() }

        if ('''
new = '''        scope.launch {
            loadFeeds()
            loadTalkgroups()
        }

        if ('''
assert old in s, 'onResume anchor not found'
s = s.replace(old, new, 1)

old = '''            "talkgroups_changed" -> withContext(Dispatchers.Main) {
                loadTalkgroups()
            }

            "announcements_changed"'''
new = '''            "talkgroups_changed" -> withContext(Dispatchers.Main) {
                loadTalkgroups()
            }

            "feeds_changed" -> withContext(Dispatchers.Main) {
                val previousFeed = selectedFeedSlug
                val loaded = loadFeeds()
                if (loaded) {
                    loadTalkgroups()
                    if (running && previousFeed != selectedFeedSlug) {
                        restartScannerAtLiveEdge(
                            "Feed list updated — returning to live calls…"
                        )
                    }
                }
            }

            "announcements_changed"'''
assert old in s, 'live event anchor not found'
s = s.replace(old, new, 1)

old = '''                        launch { loadTalkgroups() }
                        launch { updateAnnouncements() }'''
new = '''                        launch { loadFeeds() }
                        launch { loadTalkgroups() }
                        launch { updateAnnouncements() }'''
assert old in s, 'SSE reconnect anchor not found'
s = s.replace(old, new, 1)

old = '''                val liveEdgeJson = withContext(Dispatchers.IO) {
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
                }'''
new = '''                // Establish the cursor from the server's dedicated live-edge
                // endpoint. Never use a no-cursor queue request here: that endpoint
                // may contain historical audio and can make a fresh app session
                // replay an old backlog.
                val liveEdgeJson = withContext(Dispatchers.IO) {
                    getJson(
                        "/api/call-queue/latest?feed=" +
                            URLEncoder.encode(selectedFeedSlug, "UTF-8")
                    )
                }

                val liveEdge = liveEdgeJson
                    ?.optString("latest_id")
                    ?.takeIf { it.isNotBlank() && it != "null" }

                // Calls returned after this cursor must also be fresh relative to
                // the moment the app established its live edge.
                val liveEdgeTime = Instant.now()'''
assert old in s, 'live edge block not found'
s = s.replace(old, new, 1)

old = '''            val edgeTime = liveEdgeReceivedAt
            if (edgeTime != null && callReceivedAt != null && !callReceivedAt.isAfter(edgeTime)) {
                continue
            }

            if (isTalkgroupBlocked(call)) continue'''
new = '''            val edgeTime = liveEdgeReceivedAt
            if (edgeTime != null && callReceivedAt != null && !callReceivedAt.isAfter(edgeTime)) {
                continue
            }

            // Hard freshness guard. A live scanner should never work through a
            // stored backlog after startup, reconnect, or a feed change.
            if (callReceivedAt != null && callReceivedAt.isBefore(Instant.now().minusSeconds(45))) {
                continue
            }

            if (isTalkgroupBlocked(call)) continue'''
assert old in s, 'backlog guard anchor not found'
s = s.replace(old, new, 1)

old = '''        feeds.clear()
        for (i in 0 until arr.length()) {'''
new = '''        val previousSlug = selectedFeedSlug
        feeds.clear()
        for (i in 0 until arr.length()) {'''
assert old in s, 'loadFeeds start anchor not found'
s = s.replace(old, new, 1)

old = '''        val saved = prefs.getString("selected_feed_slug", "").orEmpty()
        applySelectedFeed(feeds.firstOrNull { it.slug == saved } ?: feeds.first())
        findViewById<Button>(R.id.feedButton).visibility = if (feeds.size > 1) View.VISIBLE else View.GONE
        return loadTalkgroups()'''
new = '''        val saved = prefs.getString("selected_feed_slug", "").orEmpty()
        val selected = feeds.firstOrNull { it.slug == previousSlug }
            ?: feeds.firstOrNull { it.slug == saved }
            ?: feeds.first()
        applySelectedFeed(selected)
        findViewById<Button>(R.id.feedButton).visibility =
            if (feeds.size > 1) View.VISIBLE else View.GONE
        return true'''
assert old in s, 'loadFeeds end anchor not found'
s = s.replace(old, new, 1)

p.write_text(s)

g = Path('app/build.gradle.kts')
gs = g.read_text()
assert 'versionCode = 17' in gs and 'versionName = "0.2.15"' in gs, 'version anchor not found'
gs = gs.replace('versionCode = 17', 'versionCode = 18', 1)
gs = gs.replace('versionName = "0.2.15"', 'versionName = "0.2.16"', 1)
g.write_text(gs)

print('Applied live-edge, stale-audio, feed-refresh fixes and bumped to 0.2.16 code 18')
