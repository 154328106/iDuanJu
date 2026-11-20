# 书源 / TVBox 格式与排行筛选兼容性实测

时间：2026-11-18 凌晨（Asia/Shanghai，UTC+8）。隔离分支 `codex/source-compat`，基于 `edb4296`；未推送、未动 main、未发 Release。不是“全源完美兼容”保证，也未重复此前全量扫描。

## 已修复

1. **Android 筛选布局**：原 FlowRow 将 flexBasis 与 flexGrow 混用，三列仅占约 60% 宽度。改为源规则驱动的 basis/grow/wrap-before 排版；原生测试断言每个三列选项宽度 > 屏宽 26%。不往无筛选的源添加通用筛选。
2. **Android 空排行**：进入排行/换源原先只显示分类，不请求首个真实 URL 分类；现在自动加载，与 iOS 对齐。
3. **双端 POST 书籍身份错误**：实测米读列表只剩一本，定位为 `firstLine(bookUrl)` 把带多行 JSON 的 POST 详情请求截断为同一个 `.../getDetail,{`，详情 body 丢失且去重误合并。新增 firstRequestURL 保留完整请求配方；普通多链接仍取首项。补充固定回归及真实男女列表唯一 URL 数量断言。
4. **双端条件联动**：父条件改变后，已保存的子选项若不在源新 chars 中，以源自己的合法 default/首项归一并重新计算分类及 URL，避免“新标签 + 旧请求”。最多重算三次。合成回归覆盖默认、联动 URL、持久化、源隔离、无筛选源。
5. **Android 过期请求**：排行/筛选/搜索使用取消及 generation 隔离，A→B→A 的旧响应不覆盖最新列表/忙状态；筛选重置页码后从 1 开始。合成延迟请求原生 UI 测试覆盖切源竞争。
6. **双端订阅防误导**：TVBox sites/spider/lives 或缺少合法 plugins/cms 数组的 JSON 明确拒绝，不再当作空订阅成功并清空旧缓存。显式空数组仍合法。订阅刷新不自动安装插件。
7. **双端黄果 AI 真404**：反复原生启动最终复现，并非仅 GET/POST 探测差异。镜像 race 把含播放器标记的“最新地址入口”页 `huangguoai.ai` 当成有效 API 站，随后分类 API/AI tab/最近上新均真实404。镜像探测现在必须验证小型 AI 分类 API 的200+JSON data.items，再记忆最终 origin；不执行发布页 JS、不解挑战。补充确定性“假根站不能赢”回归和双端原生实测。
8. **可审阅静态 M3U 转换**：`scripts/source-compat/m3u_to_idj.py`（不联网、不执行源脚本），将已审核合法 HTTPS 频道列表转换为现有 idj1 video 插件。支持 BOM/CRLF、带引号的逗号、分组、相对 URL、受限 Referer/UA；拒绝 EXT-X HLS 媒体/主列表、JAR/DRM、签名/凭证 URL、本机/IP、非标准端口等。不是新增 TVBox 引擎或动态 M3U 订阅。

iOS 小说视觉未改，只增加测试用 accessibilityIdentifier；DEBUG 本地插件导入仍走既有校验/沙箱。

## 实际支持与本次验证边界

| 格式/样本 | 能力与验证级别 |
|---|---|
| Legado type 0 文本 | 支持现有规则引擎。古典文学：双端原生目录 15 本 →《春秋公羊传》详情 → TOC 12 章 → 正文 4117 字，且原生阅读页截图。该源没有 searchUrl，不冒充搜索通过。 |
| 1335 七猫、米读 | 双端真实请求 + 原生筛选 UI；七猫完结/50–100万/评分、男→女、恢复源自带默认；米读男→女。仅公开目录列表，不碰付费正文。 |
| 静态合法 M3U → idj1 | Mux 官方 hls.js Big Buck Bunny 测试流。iOS 安装/首页/搜索/详情/解析/200 HLS/重载持久化；原生播放器时间推进。Android 插件详情和真实 ExoPlayer H.264/AAC 播放截图。 |
| 原生 idj1 插件订阅 | iOS 原生存储/Android 原生 SharedPreferences 的受控传输测试：v1→v2 刷新、相对 manifest URL、错误 TVBox 更新保留 v2、重载持久化、不自动安装。**这是受控 fixture，不是已找到可用的公开远程 idj 订阅服务。** |
| MacCMS JSON VOD | 现有内建 Provider 支持；本轮只核对代码，不宣称新公共 VOD 源端到端通过。XML 仍不支持。 |
| TVBox JSON sites/type1 | 导入入口只映射 `sites` 中 `type=1` 且可验证的公网 HTTPS JSON API 到内置 MacCMS；逐站请求分类接口，最多 50 个。CatVod JS、JAR、Python spider、解析器和直播规则仍不执行。 |
| Legado type1 有声/type4 视频、RSS 订阅规则 | 并非现有文本引擎支持。iOS 保留不支持标记，Android 文本导入排除非 0 类型；不能称已导入就可听/可看。 |
| yck RSS 895 知乎日报 | 上游公开 API 可达，规则是 sourceUrl/ruleArticles/sortUrl 的 Legado RSS，不是 idj1 插件订阅；应用无此阅读器运行时，明确未适配。 |
| 商业动漫/未知版权采集 | 未挑选、未播放。合法动画测试流不等于商业动漫站兼容保证。漫画由另一 worker 负责，本分支未重复研究。 |

### 原始仓库取样

- 列表 https://www.yckceo.com/yuedu/shuyuans/index.html 及订阅源/合集页面已实际读取。
- 1335：https://www.yckceo.com/yuedu/shuyuans/json/id/1335.json ，164 条（159 文本、2 有声、3 视频）；SHA256 `874efe33cbe38b7dcf830704b5bd0614b9485fe1974a20d9c09030ebec07df30`。测试取不变更规则的 index7 七猫、132 米读子集。
- 1340：https://www.yckceo.com/yuedu/shuyuans/json/id/1340.json ，601 条（509 type0、26 type1、50 type2、4 type3、12 type4）；SHA256 `2c484eddba2fc2671b77afd641be85b516c182e001a04c888abd714a03c89340`。古典文学为 index352。未把整包未知/混合内容提交进仓库。
- RSS895：https://www.yckceo.com/yuedu/rss/json/id/895.json 。知乎日报 https://news-at.zhihu.com/api/4/stories/before/ 。有声样本哔哩听书（1335 index11 / 1340 index153）属于 type1，不能用文本导入实现音频。
- 合法测试流 https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 ；测试目录 https://test-streams.mux.dev/ 。另检查 LibriVox 公共站可达性，不冒称已实现 Legado 音频引擎。

### 真实筛选请求断言

- 七猫 GET `/api/v4/tag/index`：`gender=1,book_preference=1,over=1,words=2,sort=2,page=1,tag_id=570`；切女频 gender=2；源内置默认 over=-99、words=-99、sort=0。三轮均非空列表（10 项），iOS 网络证据状态 200。
- 米读 POST `/fiction/scene/getCategoryV4`：app=midu，id 281→282；POST `/fiction/search/channel`：second_channel 3→17、page=0（源将界面第 1 页映射 API 第 0 页）。男女轮均 10 项。
- `ios-real-filter-requests.json`、`source-android-real-filter-requests.json` 保存白名单参数，不保存 Cookie/Authorization。Android 原生 UI 另断言最终请求与结果可见，不只检查选项文字存在。
- iOS 原生截图：`ios-real-qimao-filter-combination.png`、`ios-real-midu-female-filter.png`；Android：`source-android-qimao-filter-combination.png`、`source-android-midu-female-filter.png`。

## 403/503/404 的证据，不猜“跑路”

`diagnose.py` 为普通 GET、允许常规重定向、TLS 校验开启；不带 Cookie/Authorization、不解挑战、不改 DNS/代理。记录 status/content-type/final URL/title、白名单响应头、正文标记和短片段。Mac + box 交叉比较。DNS 两边均返回 198.18.* 合成代理地址，**不能用这些答案证明权威 NXDOMAIN 或域名死亡**。

| 站/实际失败端点 | 当前观察及分类 |
|---|---|
| `https://m.bookmaopu.com/` 和 `/1.html?page=1` | 跳转 `m.aofengzw.com` 后 403；正文 “Sorry, you have been blocked” / “Why have I been blocked” / Attention Required。Mac、box 均为 CF **硬阻断**，不是只凭 cf-ray，也不是已证明可以交互解开的挑战。 |
| `http://www.jiuxs.la/` 和 `/shu/1-default-0-0-0-0-2-0-1.html` | Mac HTTP 空回复 curl52，HTTPS 握手 curl35；box HTTP500 空体。无 CF 挑战证据；不能据此认定永久关闭。 |
| `6lk.la` 根站/原规则发现端点 | Mac HTTP 空回复、HTTPS 握手失败；无 CF 证据，网络/TLS 故障类。完整地址见 followup JSON。 |
| `https://www.quanwenyuedu.io/` 和 `/c/1-1.html` | Mac curl60 证书过期；box 超时。没有关闭证书校验，不能称网站没了。 |
| 书耽 `https://app.shubl.com/` | 根站200，但导入规则 discovery URL 实际写成 `https://www.baidu.com/category_type=1&order=week_click&page=1`，404；属于**规则错误**而非书耽站死亡。未擅改成猜测 API。 |
| `https://www.librivox.org/` | 526 上游 SSL 错误；裸域 `https://librivox.org/` 200。不是 CF 交互挑战，也不是整个站关闭。 |
| `https://www.bilibili.com/` | 200 但 title 为“验证码_哔哩哔哩”；站点自身风控页，不是可正常解析的内容，也无 CF 挑战证据。 |

本轮限定样本没有复现 HTTP503，因此不为历史503编造原因；单次503永远不足以认定关站。完整原始证据位于 `mac-diagnostics.json/mac-followup.json/box-diagnostics.json` 等。

### 黄果 AI 404 单独核对

- 实际代码 `STV/Providers/Huangguo/HuangguoClient.swift` 中 AI 镜像与 `huangguo.video` 不是同一入口。
- AI 镜像 `https://00b4b.adihsppe.cc/`、`https://bsl8m.adihsppe.cc/` 当前重定向到 `https://bef73c.wprptkjjz.cc`（精确镜像以代码和 JSON 为准）；AI root、recommend→newest、`/api/videos/category/ai-duanju`、`/ai-duanju`、`/ranks/hot`、`/go-home` 在 Mac 均 200；box 根站也可达。
- 样本 `/video/12/`、`/detail/12/` 均 200。`GET /api/videos/12/episodes/1/playback` 返回404，但生产代码使用 **POST `{}`**：Mac、box 相同正确方法均200 JSON status1；因此不能把探测 GET404 误报成 app 路由失效。
- iOS 生产 AI 原生 pipeline 测试通过：ai-duanju 列表24项→ai:12 详情21集→原生 POST 解析→实际 CDN HLS200（8142字节）。只取清单，不下载影片，不保存带查询凭证的流地址。
- `huangguo.video` 根站和 `/videos` 是403硬阻断；`huangguoai.com` Mac 握手失败而 box200，属于入口/网络差异。
- **随后在 02:55–02:58 Asia/Shanghai 的重复原生启动复现了真404**：模拟器 `stv.huangguo.aiBase` 实际选中 `https://huangguoai.ai`。该根站200、title“黄果短剧 · 官方最新地址入口”，但 `/api/videos/category/ai-duanju?sort=hot&page=1&size=24`、`/ai-duanju/`、`/newest/1/` 都404、text/html、nginx“404 Not Found”，Mac/box 一致。虽然有 server=cloudflare/cf-ray，正文无挑战标记，**这是发布页/错误镜像选择，不是已证明的 CF 挑战或整站死亡**。
- 修复 `HuangguoClient.probe`（Swift + Kotlin）：根站标记不能单独获胜，进一步请求分类 size=1，必须200且 JSON `data.items` 为数组；持久化 API 最终 origin。坏缓存仍纳入候选，但不再凭主页胜出。原生 iOS 再验证列表→详情→POST解析→CDN HLS；Android 用故意种入的坏 base 验证重新选择可用 origin 和24项列表。
- 证据：`huangguo-ai-reproduced404-mac.json`、`huangguo-ai-reproduced404-box.json`、`source-android-ai-mirror.json`；该修复不保证未来每条媒体永久可用。

## 测试、文件、复现

最终结果以随附 `acceptance-summary.json` 与日志为准：iOS 9 个原生 app 测试 + 3 个原生 UI 测试全过；Android 原生 6 个（经典阅读、筛选 UI+请求、两源多组合请求、A-B-A竞争、订阅刷新持久化、AI镜像恢复）；JVM 选定套件含排行、插件及黄果/CF回归，精确通过/跳过数见 acceptance-summary.json；离线 M3U 3个全过。Android HLS 播放另有真实播放器截图和解码日志，非伪造 UI。

```sh
python3 scripts/source-compat/prepare_rank_fixture.py /path/to/downloaded/1335.json
python3 -m unittest discover -s scripts/source-compat -p 'test_*.py'
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
 ./android/gradlew -p android :core:test --tests '*NovelSourcesTest' --tests '*PluginSubscriptionTest' --tests '*PluginHostTest' --tests '*HuangguoTest' --tests '*HuangguoCFTest'
# Xcode 项目用既有 xcodegen 生成；只使用自己的模拟器
SIMULATOR_ID=<own-uuid> STV_COMPAT_AI=1 SOURCE_PACKAGES=/tmp/stv-dd/SourcePackages \
 bash scripts/source-compat/run_native_ios.sh
# Android 先 assembleDebug + assembleDebugAndroidTest，并安装这两个 apk 到空闲/专属模拟器
adb -s <own-device> shell am instrument -w -r -e sourceCompatLive 1 -e class app.iduanju.SourceCompatibilityTest \
 app.iduanju.test/androidx.test.runner.AndroidJUnitRunner
```

- 可导入配置：`examples/source-compat/`（经典文本 + 合法 M3U + idjplugin + 导入说明）。
- 非付费排行取样：`android/app/src/androidTest/assets/source-compat/filter-sources.json`，规则原样保留，由 prepare_rank_fixture.py 离线生成且 gitignored，不随提交/交付包分发，仅作为本地受控回归测试。米读原作者备注禁止分享/二创，许可证未核清：不要公开发布此测试子集，不发布为“全源可用合集”。
- 代码/回归：`STV/Novel/Legado/LegadoClient.swift`、`android/core/.../NovelSources.kt`、`android/app/.../NovelExploreControls.kt`、`NovelScreen.kt`；双端 PluginStore/PluginSubscription；`STVTests/{LegadoTests,PluginTests}.swift`、`STVUITests/{NovelUITests,PluginUITests}.swift`、`android/app/src/androidTest/.../SourceCompatibilityTest.kt`。
- 原生证据：Mac `artifacts/source-compat/`，复制到 box `/workspace/iduanju/source-compat/`。原始混合源合集/大日志不进 git。
- iOS 专用 simulator `2865B4BE-2850-4BDA-B63F-5FC77E79E9B2`；Android 使用空闲 emulator-5554，未重启其他 worker 的5556。主工作树/漫画工作树未修改。

## legado-E 对照与许可证

只读对照 https://github.com/Luoyacheng/legado-E ，master 快照 `3383b57ca448c3f59b830cf98935d7d7dcdf4e66`。精确文件：
- `app/src/main/java/io/legado/app/ui/main/explore/ExploreAdapter.kt`
- `app/src/main/java/io/legado/app/ui/book/explore/ExploreShowAdapter.kt`
- `app/src/main/java/io/legado/app/data/entities/rule/ExploreRule.kt`
- `app/src/main/java/io/legado/app/data/entities/rule/ExploreKind.kt`

保留源定义顺序、flex风格、无 URL 标题不可当可点击分类；没有“所有书源套统一筛选”。其 ExploreKind 是 title/url/style 旧结构，不能据此声称全部扩展 select/toggle 控件已经 upstream 同构支持。许可证 GPLv3，见该仓库 LICENSE。本次未复制其源码，修复为本项目独立实现的行为对照；若后续移植需另行遵循 GPL 归属及分发义务。

## 明确未完成/不承诺

没有新增有声/RSS/CatVod/JAR/Python 执行引擎，没有解 CF/登录/付费/DRM；TVBox 只做 type=1 API 到 MacCMS 的受限映射，没有全601源扫描，没有商业动漫源保证。真实上游请求有偶发较长延迟，Android 原生测试保留至多120秒的控件/结果等待，不把等待中的空白页算通过；列表可达不等于每本每章都可读；一次播放通过不等于全球网络永久兼容。受控订阅存储测试不等于已验证公网订阅服务。后续集成须由 parent review/cherry-pick，不能直接发公开 Release。
