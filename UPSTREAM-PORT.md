# Actual Legado-E upstream port

Upstream: https://github.com/Luoyacheng/legado-E
Immutable comparison SHA: `8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c`.
Host baseline: `8f6093b13e3887dbfd9b80411c032818d6ceec9d` (all overnight fixes retained).
All changes authored in isolated BOX checkout `/workspace/iduanju-legado-port`.
No push / main merge / tag / public release.

## License and copied code
Both roots contain GNU GPL version3 LICENSE. The copied RSS paths have no additional
per-file exception or copyright header; preserve attribution to Legado contributors
and the upstream project, its fixed revision and original path in each derived file.
`third_party/legado-E/LICENSE` is the complete unmodified upstream license.
Unrelated third-party `modules/web` and assets have separate notices; they are not copied.

| Upstream path under app/src/main/java/io/legado/app | Host path | Actual treatment |
|---|---|---|
| model/rss/RssParserDefault.kt | android/core/.../novel/rss/RssParserDefault.kt | Actual source copy: package/data projection, remove Android Debug logger, reject DTD/entities. Parser loop/constants/image helper retained. |
| model/rss/RssParserByRule.kt | android/core/.../novel/LegadoRss.kt | Adapt list/field order, leading reverse, absent description distinction, PAGE/next-URL semantics to existing restricted analyzer. No Room or coroutine UI dependencies copied. |
| model/rss/Rss.kt | Android LegadoRss + STV/Novel/Legado/LegadoRSS.swift | Existing guarded HTTP/request/options/loginCheckJs/final-URL pipeline, then source-aware parser/content dispatch. |
| help/source/RssSourceExtensions.kt | same Android/Swift RSS adapters | Port JS sortUrl, `&&`/newline `name::url`, fallback sourceUrl. |
| model/rss/RssParserDefault.kt | STV/Novel/Legado/LegadoRSS.swift | Swift equivalent XML delegate; same title/link/date/description/content/image extraction, with DTD disabled. Shared fixture cases must match Kotlin. |

## Dependencies
- Android already supplies XmlPullParser; add compileOnly `xmlpull:xmlpull:1.1.3.1` (public-domain API).
- JVM tests use `net.sf.kxml:kxml2:2.3.0`; POM calls it BSD-style, published KXmlParser source contains the MIT-style notice now preserved verbatim in `KXML2-LICENSE.txt` (Stefan Haustein2002/2003). It is test-only, not bundled as an Android runtime.
- Both POMs retained under third_party/legado-E. No downloaded source JAR/spider is executed.
- Existing OkHttp Apache2, Jsoup MIT, Rhino MPL2 are reused. Added `media3-datasource-okhttp:1.5.1` is a transport module of the already-used Media3 player, Apache-2.0 as verified in its exact official published POM; the POM and full Apache license are retained here. No new JS/native source engine.
- iOS uses existing Foundation XML parser and existing sandbox engine/network controls.

## Implemented RSS consumption slice
RSS source import is a real source-family dispatch, not deleting an error message:
sort/category -> fetch -> default XML/custom rules -> cached article detail -> single
article TOC -> body extraction -> existing native reader. Update merges article cache,
preserves read state and persists it using the existing source state store. UI text-novel
layout is untouched. Next-page semantics and 200-entry cache bound are explicit.

Shared self-owned cases: `shared/legado-port/rss-cases.json`; JVM `LegadoRssPortTest`;
iOS equivalent tests are added alongside the same cases. Controlled fixture tests and
public-network tests are recorded separately. Public optional example: official NASA RSS;
no private yck source export or prohibited source collection is committed.

## Audio/video and script API port
- `constant/BookSourceType.kt` is actually copied into `LegadoSourceType.kt` and used by production source dispatch (Android annotation removed, constants retained).
- `model/webBook/BookContent.kt` lines226–235 raw/unescaped media-result branch adapted to Android `LegadoMedia.kt` and Swift `LegadoMedia.swift`: preserve URL ampersands/options, do not HTML-clean or cache media as chapter text.
- `model/analyzeRule/AnalyzeRule.kt` lines828–843 bindings adapted in NovelScript/LegadoRule/LegadoCore. Nullable `chapter`, `title`, `nextChapterUrl`, `rssArticle`; public Kotlin-style chapter/article getters, chapter index and scoped state restoration. Arbitrary JVM reflection is still forbidden.
- Common catalog/detail/TOC pipeline now dispatches type1 audio/type4 video into real Media3/AVPlayer views. Existing text filters/POST/prefetch pipelines retained. iOS text-novel visual style unchanged; media adds a separate sheet. iOS media shelf/download is honestly disabled (Android shelf playback works).
- Android player uses a separate guarded OkHttp DNS/interceptor boundary. iOS player reuses existing LocalHLSProxy with an **opt-in route only**, an ephemeral isolated PluginNet cookie jar/domain policy, private-address/redirect checks on every manifest/key/segment/range and no unrelated ad/header/decryption transformations. iOS resolves same-host public media; undeclared cross-CDN resources fail closed. Credentialed iOS playback and unsupported steering/byte-range HLS extensions fail honestly instead of forwarding account cookies unsafely. Existing non-Legado playback routes are not changed in behavior.
- Embeddable GPL/notice files included under STV/Resources/Licenses and Android copied license assets. Existing Media3 Apache2, Apple AVFoundation SDK and in-house proxy/PluginNet reused; no additional engine dependency.

## Evidence and limits (actual native acceptance, 2026-11-17 PST)
- Final selected Android JVM suites: **32 selected, 28 passed, 4 explicit opt-in live skips, 0 failures**. Both debug APK and AndroidTest APK assemble successfully. Existing text filter/POST/state/cache/prefetch/cancellation tests remain green.
- Final Android native run **8/8 passed in one run**: real NASA and personal yck RSS import/catalog/detail/body/update-read-state; self-owned RSS; controlled catalog audio/video; the two standalone importable media configs; pre-TCP IP/port and same-origin redirect guards. A first final run failed only the two new tests because identical source/book names selected the source-picker chip; test-only commit `06b8c61` fixes that selector, then all eight passed. The native player readability fix is included and inspected in screenshots. Test-only `cfa08c9` subsequently strengthened video acceptance to >=2 seconds **and playing**; the standalone public video case passed the targeted rerun. Its compositor screenshot shows1.82s playing (frame lag), not the earlier0.00s buffering transition; the >=2s claim comes from the actual native assertion, not relabeling the screenshot.
- Final iOS coherent selected unit regression **40/40 passed**, covering existing parser/filter/POST, RSS (including real personal-source body/page2), media/negative import, cache/index/prefetch/cancellation and existing proxy behavior. Corrected actual-body UI assertions separately passed **3/3 RSS flows**; latest standalone importable public audio/video UI flows **2/2 passed**, with playback screenshots at0.98s and2.17s respectively. Earlier fixture-catalog media UI flows also passed, but are not double-counted as new sources.
- Rejected false positives remain documented: old iOS reader existence-only tests showed loading and were replaced with actual-body assertions; an added cache test originally omitted an existing chapter-title prefix from its expected string (fixed in `e2e0dd6`, final40 is green). No loading screenshot or unresolved failure is being presented as final successful reading.
- NASA official public RSS actual HTTP directory/detail/body consumed on JVM; not merely import-only.
- Personal yck RSS895 list and next-page update reached 200 on Mac. Its original daily.zhihu.com article URL currently returns a JavaScript shell (14 cleaned chars, generic Zhihu title), not a usable article; not declared reader success. The personal-only adaptation uses the same official free article API through ruleContent and rssArticle context; no private collection exported. BOX news-at host currently TLS EOF, not proved Cloudflare or site death. Final live retest recorded separately.
- `shared/legado-port` fixtures are self-owned catalog/article responses; audio is MDN CC0 T-Rex and video official Mux Big Buck Bunny. `audio-static-public` and `video-static-public` additionally embed self-owned data: catalog/detail/TOC and need **no fixture server**; the actual public media is fetched by the native player. NASA is a real public feed. Controlled/self-owned catalog + public media playback is **not** a real yck audiobook/anime-source full-flow claim.
- Import explicitly rejects (Android) or marks unsupported/disabled with a reason (iOS) media sources requiring browser `webJs`; they cannot silently become usable sources. Type2 manga and type3 download-only sources, browser-sniffed `webJs`, TVBox/CatVod/JAR/Python and DRM/paywall/auth/challenge bypass are not enabled by this port. No arbitrary spider execution.
- This is an implemented, tested vertical port, not all upstream API/Room/browser feature parity or a perfect compatibility guarantee. RSS cache is bounded200; RSS/novel identical source URL collisions and broader APIs (including upstream AnalyzeUrl HEAD URL-option parity, not used by the delivered configs) still need dedicated follow-up before claiming full upstream parity.

## Reproduce / distribution scope
- Android: `JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./android/gradlew -p android :core:test --tests app.iduanju.core.LegadoRssPortTest --tests app.iduanju.core.LegadoMediaPortTest --tests app.iduanju.core.NovelSourcesTest --tests app.iduanju.core.NovelExploreFilterTest --tests app.iduanju.core.NovelCacheTest --tests app.iduanju.core.NovelPrefetchTest :app:assembleDebug :app:assembleDebugAndroidTest -PidjIsolatedQa=true --no-daemon`.
- Native test sources: `android/app/src/androidTest/java/app/iduanju/LegadoPortNativeTest.kt`, `STVUITests/LegadoPortUITests.swift`; shared self-owned fixtures under `shared/legado-port`; the personal-source opt-in never ships in fixtures.
- Source bundle/patch are incremental against the retained overnight `8f6093b` baseline, **not** against `edb4296`. The owner/parent already holds that original corresponding source/bundle. No private yck source definitions or old bundled third-party plugin assets are re-exported by the port delta.
- Debug QA APK has a distinct app ID; simulator `.app` is not an IPA/device archive. Neither implies a safe signed upgrade over a user's installed release. Do not uninstall or erase their data.
