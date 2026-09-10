from pathlib import Path
import re
import runpy

# First permanently apply the already-tested 0.2.20 behavior to source.
runpy.run_path('tools/apply_0_2_20.py', run_name='__main__')

main = Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt')
gradle = Path('app/build.gradle.kts')
s = main.read_text()

# Track when the Activity actually went into the background. Long radio silence
# by itself must never stop the scanner; this timestamp is only lifecycle state.
marker = '    private var audioFocusLostAt = 0L\n'
if 'private var appBackgroundedAt = 0L' not in s:
    if marker not in s:
        raise SystemExit('audioFocusLostAt marker not found')
    s = s.replace(
        marker,
        marker + '    private var appBackgroundedAt = 0L\n',
        1
    )

old_resume = '''    override fun onResume() {
        super.onResume()

        scope.launch {
            loadFeeds()
            loadTalkgroups()
        }

        if (
            running &&
            audioFocusLostAt > 0 &&
            System.currentTimeMillis() - audioFocusLostAt >= 2500 &&
            !weatherInterrupting
        ) {
            audioFocusLostAt = 0L
            scope.launch {
                restartScannerAtLiveEdge("Returning to live calls…")
            }
        }
    }
'''

new_resume = '''    override fun onResume() {
        super.onResume()

        scope.launch {
            loadFeeds()
            loadTalkgroups()
        }

        val now = System.currentTimeMillis()
        val backgroundedFor =
            if (appBackgroundedAt > 0L) now - appBackgroundedAt else 0L

        /*
         * Android may suspend the Activity's scanner loop while the app is in
         * the background. If we return after a meaningful background period,
         * discard the old cursor and rejoin the server's current live edge.
         *
         * IMPORTANT: ordinary radio silence does NOT trigger this. The scanner
         * can sit idle for hours and still play the very next live call.
         */
        if (
            running &&
            backgroundedFor >= 15000L &&
            !weatherInterrupting &&
            !manualNwsActive
        ) {
            appBackgroundedAt = 0L
            audioFocusLostAt = 0L
            scope.launch {
                restartScannerAtLiveEdge(
                    "App resumed — returning to live calls…"
                )
            }
            return
        }

        appBackgroundedAt = 0L

        if (
            running &&
            audioFocusLostAt > 0 &&
            now - audioFocusLostAt >= 2500 &&
            !weatherInterrupting &&
            !manualNwsActive
        ) {
            audioFocusLostAt = 0L
            scope.launch {
                restartScannerAtLiveEdge("Returning to live calls…")
            }
        }
    }

    override fun onStop() {
        if (running) {
            appBackgroundedAt = System.currentTimeMillis()
        }
        super.onStop()
    }
'''

if old_resume not in s:
    raise SystemExit('onResume block not found')
s = s.replace(old_resume, new_resume, 1)

# Make sure stopping the scanner clears any stale lifecycle marker.
old_stop = '''        cursor = null
        audioFocusLostAt = 0L
'''
new_stop = '''        cursor = null
        audioFocusLostAt = 0L
        appBackgroundedAt = 0L
'''
if old_stop not in s:
    raise SystemExit('stopScanner reset block not found')
s = s.replace(old_stop, new_stop, 1)

main.write_text(s)

g = gradle.read_text()
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 23', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "0.2.21"', g, count=1)
gradle.write_text(g)

check = main.read_text()
for required in (
    'manualNwsActive',
    'enterManualNwsMode',
    'appBackgroundedAt',
    'backgroundedFor >= 15000L',
    'App resumed — returning to live calls…',
):
    if required not in check:
        raise SystemExit(f'missing {required}')
if 'minusSeconds(45)' in check:
    raise SystemExit('45-second busy-feed drop guard still exists')

print('Android 0.2.21 source migration validated')
