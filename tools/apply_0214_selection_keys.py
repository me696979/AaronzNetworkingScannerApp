from pathlib import Path

main = Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt')
gradle = Path('app/build.gradle.kts')

s = main.read_text()

# Talkgroup rows need a stable server-provided selection key. Managed rows use
# db:<row id>; SDRTrunk-discovered rows use sdr:<tgid>.
old = '''    private data class TalkgroupOption(\n        val id: Int,\n        val name: String,\n        val talkgroupIds: String,\n        val allIds: Set<Int>\n    )\n'''
new = '''    private data class TalkgroupOption(\n        val id: Int,\n        val selectionKey: String,\n        val name: String,\n        val talkgroupIds: String,\n        val allIds: Set<Int>\n    )\n'''
if old in s:
    s = s.replace(old, new, 1)

s = s.replace(
    '    private val blockedTalkgroups = mutableSetOf<Int>()\n',
    '    private val blockedTalkgroups = mutableSetOf<String>()\n',
    1,
)

# Client-side call filter: use resolved database row when available, otherwise
# the live SDRTrunk TGID. Keep legacy numeric preferences compatible.
old = '''            val tg = call.optString("talkgroup").toIntOrNull()\n            if (tg != null && isTalkgroupBlocked(tg)) continue\n\n            playCallAndWait(call, generation)\n'''
new = '''            if (isTalkgroupBlocked(call)) continue\n\n            playCallAndWait(call, generation)\n'''
if old in s:
    s = s.replace(old, new, 1)

old = '''    private fun isTalkgroupBlocked(tgid: Int): Boolean {\n        val managed = talkgroups.firstOrNull { tgid in it.allIds }\n        return if (managed != null) managed.id in blockedTalkgroups else tgid in blockedTalkgroups\n    }\n'''
new = '''    private fun callSelectionKey(call: JSONObject): String {\n        val databaseId = call.optInt("talkgroup_database_id", 0)\n        if (databaseId > 0) return "db:$databaseId"\n\n        return "sdr:${call.optString("talkgroup").trim()}"\n    }\n\n    private fun isTalkgroupBlocked(call: JSONObject): Boolean {\n        val key = callSelectionKey(call)\n        if (key in blockedTalkgroups) return true\n\n        // Backward compatibility for preferences saved by 0.2.13 and earlier.\n        val legacyTgid = call.optString("talkgroup").trim()\n        return legacyTgid.isNotBlank() && legacyTgid in blockedTalkgroups\n    }\n'''
if old in s:
    s = s.replace(old, new, 1)

# Parse selection_key from the same live server list used by the web player.
old = '''            incoming.add(\n                TalkgroupOption(\n                    id = id,\n                    name = tg.optString("name", "Talkgroup $id"),\n                    talkgroupIds = idsText,\n                    allIds = allIds\n                )\n            )\n'''
new = '''            val databaseId = tg.optInt("database_id", 0)\n            val selectionKey = tg.optString("selection_key").trim().ifBlank {\n                if (databaseId > 0) "db:$databaseId" else "sdr:$id"\n            }\n\n            incoming.add(\n                TalkgroupOption(\n                    id = id,\n                    selectionKey = selectionKey,\n                    name = tg.optString("name", "Talkgroup $id"),\n                    talkgroupIds = idsText,\n                    allIds = allIds\n                )\n            )\n'''
if old in s:
    s = s.replace(old, new, 1)

# Migrate old numeric blocked TGID prefs to the new row-aware keys after the
# current feed list arrives. This keeps existing listener choices intact.
old = '''        talkgroups.clear()\n        talkgroups.addAll(incoming)\n        updateTalkgroupButton()\n        return incoming.isNotEmpty()\n'''
new = '''        talkgroups.clear()\n        talkgroups.addAll(incoming)\n\n        val legacyBlocked = blockedTalkgroups\n            .filter { it.all(Char::isDigit) }\n            .mapNotNull { it.toIntOrNull() }\n            .toSet()\n\n        if (legacyBlocked.isNotEmpty()) {\n            val migrated = incoming\n                .filter { tg -> tg.allIds.any { it in legacyBlocked } }\n                .map { it.selectionKey }\n\n            blockedTalkgroups.removeAll { it.all(Char::isDigit) }\n            blockedTalkgroups.addAll(migrated)\n            saveBlockedTalkgroups()\n        }\n\n        updateTalkgroupButton()\n        return incoming.isNotEmpty()\n'''
if old in s:
    s = s.replace(old, new, 1)

# SharedPreferences now stores exact selection-key strings.
old = '''        blockedTalkgroups.addAll(\n            prefs.getStringSet("blocked_talkgroups", emptySet())\n                .orEmpty().mapNotNull { it.toIntOrNull() }\n        )\n'''
new = '''        blockedTalkgroups.addAll(\n            prefs.getStringSet("blocked_talkgroups", emptySet())\n                .orEmpty()\n                .map { it.trim() }\n                .filter { it.isNotBlank() }\n        )\n'''
if old in s:
    s = s.replace(old, new, 1)

s = s.replace(
    '            .putStringSet("blocked_talkgroups", blockedTalkgroups.map { it.toString() }.toSet())\n',
    '            .putStringSet("blocked_talkgroups", blockedTalkgroups.toSet())\n',
    1,
)

old = '''        val existingIds = talkgroups.map { it.id }.toSet()\n        val blockedExisting = blockedTalkgroups.count { it in existingIds }\n'''
new = '''        val existingIds = talkgroups.map { it.selectionKey }.toSet()\n        val blockedExisting = blockedTalkgroups.count { it in existingIds }\n'''
if old in s:
    s = s.replace(old, new, 1)

s = s.replace(
    '            talkgroups[index].id !in blockedTalkgroups\n',
    '            talkgroups[index].selectionKey !in blockedTalkgroups\n',
    1,
)

s = s.replace(
    '                    if (!checked[i]) blockedTalkgroups.add(talkgroups[i].id)\n',
    '                    if (!checked[i]) blockedTalkgroups.add(talkgroups[i].selectionKey)\n',
    1,
)

# JSON preference payload now carries db:/sdr: keys, matching the server.
old = '''        val blocked = org.json.JSONArray()\n        blockedTalkgroups.sorted().forEach { blocked.put(it) }\n'''
new = '''        val blocked = org.json.JSONArray()\n        blockedTalkgroups.sorted().forEach { blocked.put(it) }\n'''
# Same text is valid for String set, left here intentionally for validation.

main.write_text(s)

# Keep the release at the already-selected 0.2.14 / versionCode 16.
g = gradle.read_text()
if 'versionCode = 16' not in g:
    g = g.replace('versionCode = 15', 'versionCode = 16')
if 'versionName = "0.2.14"' not in g:
    g = g.replace('versionName = "0.2.13"', 'versionName = "0.2.14"')
gradle.write_text(g)

check = main.read_text()
required = [
    'val selectionKey: String',
    'private val blockedTalkgroups = mutableSetOf<String>()',
    'private fun callSelectionKey(call: JSONObject): String',
    'return "db:$databaseId"',
    'return "sdr:${call.optString("talkgroup").trim()}"',
    'selectionKey = selectionKey',
    'talkgroups[index].selectionKey !in blockedTalkgroups',
    'blockedTalkgroups.add(talkgroups[i].selectionKey)',
    '/api/public/talkgroups?feed=',
    'liveEdgeReceivedAt = liveEdgeTime',
]
for marker in required:
    if marker not in check:
        raise SystemExit(f'missing required 0.2.14 marker: {marker}')

if 'versionCode = 16' not in gradle.read_text() or 'versionName = "0.2.14"' not in gradle.read_text():
    raise SystemExit('0.2.14 version markers missing')

print('Scannerz 0.2.14 selection-key + live-edge update applied and validated')
