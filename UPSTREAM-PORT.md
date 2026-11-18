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
- Existing OkHttp Apache2, Jsoup MIT, Rhino MPL2 are reused, no new JS/native source engine.
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

## Evidence and limits (updated as runs complete)
- Android shared RSS/media/parser/filter JVM suites + full debug/instrumentation build passed; actual 5554 isolated QA app import→catalog→detail→TOC→RSS reader/audio/video public timeline advancing: 3/3 passed before final transport-only follow-ups.
- iOS shared RSS/media 4 new unit tests + existing parser/filter 18 tests passed. Actual dedicated simulator three native UI flows passed, including source import into empty state and restart persistence. Guarded transport follow-up receives its own rerun; see final evidence summary.
- NASA official public RSS actual HTTP directory/detail/body consumed on JVM; not merely import-only.
- Personal yck RSS895 list and next-page update reached 200 on Mac. Its original daily.zhihu.com article URL currently returns a JavaScript shell (14 cleaned chars, generic Zhihu title), not a usable article; not declared reader success. The personal-only adaptation uses the same official free article API through ruleContent and rssArticle context; no private collection exported. BOX news-at host currently TLS EOF, not proved Cloudflare or site death. Final live retest recorded separately.
- `shared/legado-port` fixtures are self-owned catalog/article responses; audio is MDN CC0 T-Rex and video official Mux Big Buck Bunny. Controlled catalog + public media playback is **not** a real yck media-source full-flow claim.
- Type2 manga and type3 download-only sources, browser-sniffed `webJs`, TVBox/CatVod/JAR/Python and DRM/paywall/auth/challenge bypass are not enabled by this port. No arbitrary spider execution.
- This is an implemented, tested vertical port, not all upstream API/Room/browser feature parity or a perfect compatibility guarantee. RSS cache is bounded200; RSS/novel identical source URL collisions and broader APIs still need dedicated follow-up before claiming full upstream parity.
