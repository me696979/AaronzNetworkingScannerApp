from pathlib import Path

main = Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt')
s = main.read_text()

# Refresh the managed talkgroup list periodically so Admin TGID/name changes
# reach the app even if the SSE connection was interrupted or an event was missed.
needle = '''        scope.launch {
            while (isActive) {
                updateStatsAndAlerts()
                delay(15000)
            }
        }
'''
replace = '''        scope.launch {
            while (isActive) {
                updateStatsAndAlerts()
                loadTalkgroups()
                delay(15000)
            }
        }
'''
if replace not in s:
    if needle not in s:
        raise SystemExit('stats refresh loop marker not found')
    s = s.replace(needle, replace, 1)

# Also refresh immediately whenever the Activity returns to the foreground.
needle = '''    override fun onResume() {
        super.onResume()

        if (
'''
replace = '''    override fun onResume() {
        super.onResume()

        scope.launch { loadTalkgroups() }

        if (
'''
if replace not in s:
    if needle not in s:
        raise SystemExit('onResume marker not found')
    s = s.replace(needle, replace, 1)

main.write_text(s)

check = main.read_text()
for required in [
    'updateStatsAndAlerts()\n                loadTalkgroups()',
    'scope.launch { loadTalkgroups() }',
    '/api/public/talkgroups?feed=',
]:
    if required not in check:
        raise SystemExit(f'missing TGID refresh marker: {required}')

print('Scannerz 0.2.14 TGID refresh fix applied and validated')
