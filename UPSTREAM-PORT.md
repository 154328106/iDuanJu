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

## Second actual real-library slice (2026-11-17 PST)
Baseline of this slice: `dd37d873afe00e207f49e24ce3270f4cc1ff5540`.
- Runtime `9f9083f`: `BookChapterList.kt:258-273` non-volume empty URL -> TOC base fallback adapted in Android `NovelSources.details`, preserving existing addressed-volume behavior; Swift already had the same fallback and now shares an explicit test.
- `AnalyzeRule.kt:828-841` host result binding compatibility: the host's JSON materialization had changed upstream-style `JSON.parse(String(result))` into `[object Object]`. Scoped JSON-container string coercion now preserves both JSON string conversion and result.property access on Android/iOS, with shared object/array/empty-array cases. This is a bridge semantic adaptation, not copying a new unrestricted JS engine.
- `WebBook.kt:387-390` direct media URL contract and `AnalyzeRule` baseUrl binding: no content rule already returns chapter URL; exact `@js:baseUrl`/`<js>baseUrl;</js>` are now a no-body identity fast path only when loginCheckJs has no side effects. Browser webJs remains rejected. The app no longer downloads a full recording merely to return its URL.
- Real yck1340 #172 Ximalaya: official search/discovery and mobile TOC HTTP200; only selected unpaid/public album22838639 and its first unpaid track are claimed consumed. Private adaptation upgrades old HTTP mobile URL to verified HTTPS and corrects the source's unconditional/broken pagination using actual maxPageId. Native full15KB source import/search/detail/427-entry TOC/first m4a >=2s passed on both platforms. Discovery30 records also passed the production JVM pipeline; native discovery click-through was not separately exercised. Definition remains private, excluded from git/distribution.
- Real video replacement: new independently authored NASA Image and Video Library adapter has a genuine remote catalog/search/metadata/asset TOC, **not data: fixtures**. Actual NASA animation catalog/detail/TOC/playback passed on both platforms. Three real metadata/TOC cases, including an ID containing spaces, passed JVM after declarative URI encoding. Native playback scope is the first NISAR animation, not all NASA assets or Japanese licensed series. The URI-encoding follow-up affects only the test/source config; parser runtime stays at9f9083f and the first validated request URLs are unchanged; the later iOS-only media-title fallback is8d627e1.
- Exact native snapshot `466ca4f`: Android2/2 and iOS2/2 real-library UI flows; iOS8/8 media units. Final selected iOS text/filter/RSS/media/cache/cancel/proxy regression43/43; Android selected37:33 passed,4 explicit opt-in skips. No new dependency/license family.
- Exclusions: encrypted/CENC Fanqie path not run; Bili audio adapter contains forged-PNG Gaia activation and was not executed. No unverified-rights anime aggregator episodes were consumed. NASA is scientific animation, not a claim of yck commercial-anime compatibility. Source scripts execute only in existing restricted engines, no arbitrary spider/JAR/Python native execution.
- Actual origin evidence: Qingmo current homepage is a game page and old radio path404 on both Mac/BOX; Ximalaya public endpoints200; 5sing BOX500 versus Mac200. No site-death inference from a single failure and no CF challenge workaround. Compressed origin bodies were decoded before marker checks.

### Real-library evidence precision
- Native full private audio definition imported on both platforms; search title/creator, actual three-page427 TOC and first unpaid track played. Discovery returned30 real entries in JVM; native discovery click-through is not independently claimed. The public provider marks the selected album and track unpaid; no claim that every track or unrelated result is free/licensed for redistribution.
- The official TOC intentionally has titles1-01,1-03,1-05 etc.; these are the actual source titles, not dropped chapters.
- Native Android assertions require>=2 seconds **and playing** for both real-library tests. The generated audio-result JSON retains older weaker prose “>=0.2s”; the executable assertion and screenshot2.16s are authoritative. iOS audio requires>=2 seconds; NASA video requires advancing>=0.2s (captured frame1.91s), not a>=2s iOS-video assertion.
- iOS8d627e1 fixes empty media-player book metadata using the same nonempty detail/search fallback already used by the unchanged novel detail appearance. Optional detail rules returning only an introduction must not erase the searched title/author.
- Existing37 JVM regression cases:33passed,4explicit opt-in skips,0failures. Existing iOS43 parser/filter/POST/RSS/media/cache/proxy cases passed. Real HTTP opt-ins and native flows are separate from deterministic fixtures.
- Final targeted iOS native rerun at8d627e1 passed1/1: real audio>=2s **and** searched-title navigation-bar assertion; the smaller personal definition only trims the long discovery menu, not the actual search/detail/TOC/content rules. Earlier full15KB source import remains separate original proof. No old suites were needlessly rerun for this one-line UI fallback.
