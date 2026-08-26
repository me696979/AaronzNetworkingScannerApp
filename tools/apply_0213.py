from pathlib import Path

# Version
p=Path('app/build.gradle.kts'); s=p.read_text(); s=s.replace('versionCode = 14','versionCode = 15').replace('versionName = "0.2.12"','versionName = "0.2.13"'); p.write_text(s)

# Layout: feed selector after talkgroup button; weather header gets collapse controls and content wrapper.
p=Path('app/src/main/res/layout/activity_main.xml'); x=p.read_text()
if '@+id/feedButton' not in x:
    marker='''        <TextView\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:text="Choose exactly which agencies/talkgroups you want to hear. Your choices are saved on this device."'''
    add='''        <Button\n            android:id="@+id/feedButton"\n            android:layout_width="match_parent"\n            android:layout_height="50dp"\n            android:text="FEED: LOADING…"\n            android:textStyle="bold"\n            android:textColor="#D8F7DE"\n            android:backgroundTint="#173D20"\n            android:layout_marginTop="10dp"\n            android:visibility="gone" />\n\n'''
    if marker not in x: raise SystemExit('layout feed marker missing')
    x=x.replace(marker,add+marker,1)
if '@+id/weatherCollapse' not in x:
    marker='''            <TextView\n                android:layout_width="match_parent"\n                android:layout_height="wrap_content"\n                android:text="☁ LOCAL WEATHER"\n                android:textColor="#6CFF88"\n                android:textStyle="bold"\n                android:textSize="13sp"\n                android:fontFamily="monospace" />'''
    add=marker+'''\n\n            <Button\n                android:id="@+id/weatherCollapse"\n                android:layout_width="wrap_content"\n                android:layout_height="40dp"\n                android:text="EXPAND"\n                android:textSize="10sp"\n                android:textColor="#D8F7DE"\n                android:backgroundTint="#173D20"\n                android:layout_marginTop="8dp" />\n\n            <CheckBox\n                android:id="@+id/weatherAutoExpand"\n                android:layout_width="match_parent"\n                android:layout_height="wrap_content"\n                android:text="Auto-expand when a weather alert is active"\n                android:textColor="#D8F7DE"\n                android:fontFamily="monospace" />'''
    if marker not in x: raise SystemExit('weather marker missing')
    x=x.replace(marker,add,1)
p.write_text(x)

# Kotlin
p=Path('app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt'); s=p.read_text()
if 'private data class FeedOption' not in s:
    marker='''    private data class WeatherPoint(\n        val lat: Double,\n        val lon: Double,\n        val city: String = "",\n        val state: String = ""\n    )'''
    add=marker+'''\n\n    private data class FeedOption(\n        val slug: String,\n        val name: String,\n        val location: String,\n        val weatherRadioTalkgroup: String?\n    )'''
    if marker not in s: raise SystemExit('WeatherPoint marker missing')
    s=s.replace(marker,add,1)
if 'private val feeds = mutableListOf<FeedOption>()' not in s:
    marker='    private var activeSeriousWeatherAlert: JSONObject? = null\n'
    add=marker+'''    private val feeds = mutableListOf<FeedOption>()\n    private var selectedFeedSlug = "wv-cabell-001"\n    private var weatherRadioTalkgroup: String? = null\n    private var weatherCollapsed = true\n'''
    if marker not in s: raise SystemExit('state marker missing')
    s=s.replace(marker,add,1)
if 'R.id.feedButton).setOnClickListener' not in s:
    marker='        loadSavedBlockedTalkgroups()\n'
    add='''        findViewById<Button>(R.id.feedButton).setOnClickListener { showFeedDialog() }\n\n'''
    s=s.replace(marker,add+marker,1)
if 'setupWeatherCollapse()' not in s:
    s=s.replace('        setupWeatherControls()\n','        setupWeatherControls()\n        setupWeatherCollapse()\n',1)
s=s.replace('        scope.launch { loadTalkgroups() }\n','        scope.launch { loadFeeds() }\n',1)
# Scope call queue and talkgroups to feed.
s=s.replace('getJson("/api/call-queue/latest")','getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=1")')
s=s.replace('getJson("/api/call-queue?limit=20&after=$after")','getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=20&after=$after")')
s=s.replace('getJson("/api/public/talkgroups")','getJson("/api/public/talkgroups?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}")')
# Dynamic NWS endpoint.
s=s.replace('getJson(WeatherTakeoverConfig.LATEST_NWS_PATH)','weatherRadioTalkgroup?.let { tg -> getJson("/api/call-queue/latest-talkgroup?talkgroup=${URLEncoder.encode(tg, "UTF-8")}&max_age_seconds=300") }')
s=s.replace('call.optString("talkgroup", WeatherTakeoverConfig.NWS_TALKGROUP)','call.optString("talkgroup", weatherRadioTalkgroup ?: "")')

if 'private suspend fun loadFeeds(): Boolean' not in s:
    marker='    private suspend fun loadTalkgroups(): Boolean {'
    helpers='''    private suspend fun loadFeeds(): Boolean {\n        val data = withContext(Dispatchers.IO) { getJson("/api/feeds") } ?: return false\n        val arr = data.optJSONArray("feeds") ?: return false\n        feeds.clear()\n        for (i in 0 until arr.length()) {\n            val item = arr.getJSONObject(i)\n            val slug = item.optString("slug").trim()\n            if (slug.isBlank()) continue\n            val weather = item.optJSONObject("weather_radio")\n            feeds.add(FeedOption(slug, item.optString("name", slug), item.optString("location", ""), weather?.optString("talkgroup_id")?.takeIf { it.isNotBlank() && it != "null" }))\n        }\n        if (feeds.isEmpty()) return false\n        val saved = prefs.getString("selected_feed_slug", "").orEmpty()\n        applySelectedFeed(feeds.firstOrNull { it.slug == saved } ?: feeds.first())\n        findViewById<Button>(R.id.feedButton).visibility = if (feeds.size > 1) View.VISIBLE else View.GONE\n        return loadTalkgroups()\n    }\n\n    private fun applySelectedFeed(feed: FeedOption) {\n        selectedFeedSlug = feed.slug\n        weatherRadioTalkgroup = feed.weatherRadioTalkgroup\n        prefs.edit().putString("selected_feed_slug", feed.slug).apply()\n        findViewById<Button>(R.id.feedButton).text = "FEED: ${feed.name}"\n        val nws = findViewById<CheckBox>(R.id.weatherNwsRepeatEnabled)\n        nws.visibility = if (weatherRadioTalkgroup == null) View.GONE else View.VISIBLE\n        if (weatherRadioTalkgroup == null) nws.isChecked = false\n    }\n\n    private fun showFeedDialog() {\n        if (feeds.size <= 1) return\n        val labels = feeds.map { if (it.location.isBlank()) it.name else "${it.name} • ${it.location}" }.toTypedArray()\n        val checked = feeds.indexOfFirst { it.slug == selectedFeedSlug }\n        AlertDialog.Builder(this).setTitle("Select Scanner Feed").setSingleChoiceItems(labels, checked) { dialog, which ->\n            applySelectedFeed(feeds[which]); talkgroups.clear(); blockedTalkgroups.clear(); loadSavedBlockedTalkgroups()\n            scope.launch { loadTalkgroups(); if (running) restartScannerAtLiveEdge("Feed changed — returning to live calls…") }\n            dialog.dismiss()\n        }.setNegativeButton("Cancel", null).show()\n    }\n\n    private fun setupWeatherCollapse() {\n        val auto = findViewById<CheckBox>(R.id.weatherAutoExpand)\n        auto.isChecked = prefs.getBoolean("weather_auto_expand", true)\n        auto.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("weather_auto_expand", v).apply() }\n        weatherCollapsed = prefs.getBoolean("weather_collapsed", true)\n        findViewById<Button>(R.id.weatherCollapse).setOnClickListener {\n            weatherCollapsed = !weatherCollapsed\n            prefs.edit().putBoolean("weather_collapsed", weatherCollapsed).apply()\n            applyWeatherCollapsedState()\n        }\n        applyWeatherCollapsedState()\n    }\n\n    private fun applyWeatherCollapsedState() {\n        val section = findViewById<LinearLayout>(R.id.weatherSection)\n        val collapse = findViewById<Button>(R.id.weatherCollapse)\n        for (i in 0 until section.childCount) {\n            val child = section.getChildAt(i)\n            child.visibility = if (i <= 1 || !weatherCollapsed) View.VISIBLE else View.GONE\n        }\n        collapse.visibility = View.VISIBLE\n        collapse.text = if (weatherCollapsed) "EXPAND" else "COLLAPSE"\n    }\n\n    private fun updateWeatherCollapseForAlert(active: Boolean) {\n        if (!prefs.getBoolean("weather_auto_expand", true)) return\n        weatherCollapsed = if (active) false else prefs.getBoolean("weather_collapsed", true)\n        applyWeatherCollapsedState()\n    }\n\n'''
    if marker not in s: raise SystemExit('loadTalkgroups marker missing')
    s=s.replace(marker,helpers+marker,1)
# Auto expand when serious alert state is assigned, collapse when cleared where possible.
s=s.replace('            activeSeriousWeatherAlert = seriousActive\n','            activeSeriousWeatherAlert = seriousActive\n            updateWeatherCollapseForAlert(seriousActive != null)\n')
# Weather-radio repeat only exists on feeds that advertise it.
s=s.replace('seriousActive != null &&\n                prefs.getBoolean("weather_nws_repeat_enabled", true) &&','seriousActive != null &&\n                weatherRadioTalkgroup != null &&\n                prefs.getBoolean("weather_nws_repeat_enabled", true) &&')
p.write_text(s)

# Validate intended source landed.
checks={
'app/build.gradle.kts':['versionCode = 15','versionName = "0.2.13"'],
'app/src/main/res/layout/activity_main.xml':['@+id/feedButton','@+id/weatherCollapse','@+id/weatherAutoExpand'],
'app/src/main/java/net/aaronznetworking/scanner/MainActivity.kt':['/api/feeds','selectedFeedSlug','weatherRadioTalkgroup','setupWeatherCollapse()','/api/public/talkgroups?feed=','/api/call-queue?feed=']}
for f, needles in checks.items():
    t=Path(f).read_text()
    for n in needles:
        if n not in t: raise SystemExit(f'VALIDATION FAILED {f}: {n}')
print('Scannerz 0.2.13 source update validated')
