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

## Implemented slice (RSS, in progress validation)
RSS source import is a real source-family dispatch, not deleting an error message:
sort/category -> fetch -> default XML/custom rules -> cached article detail -> single
article TOC -> body extraction -> existing native reader. Update merges article cache,
preserves read state and persists it using the existing source state store. UI text-novel
layout is untouched. Next-page semantics and 200-entry cache bound are explicit.

Shared self-owned cases: `shared/legado-port/rss-cases.json`; JVM `LegadoRssPortTest`;
iOS equivalent tests are added alongside the same cases. Controlled fixture tests and
public-network tests are recorded separately. Public optional example: official NASA RSS;
no private yck source export or prohibited source collection is committed.

## Next contracts (not completed by import alone)
- Audio/video source type1/4 media result must bypass HTML cleanup, then preserve request
  headers/options and connect to actual native playback; catalog/detail/TOC remain common.
- Broader parser/API gaps discovered by source tracing must receive deterministic fixtures.
- Native Android/iOS UI and public yck RSS checks are still pending at this point; no claim
  of native/public end-to-end success until real logs/screenshots exist.
- TVBox/CatVod/JAR/Python are not Legado and are not silently enabled.
- Existing private-network/credentials/CF/paywall/DRM boundaries remain in force.
