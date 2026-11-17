# i短剧 Android

原生 Kotlin + Jetpack Compose，目标与 iOS 功能对齐。计划见 [PLAN_ZH.md](PLAN_ZH.md)，进度见 [PROGRESS.md](PROGRESS.md)。

当前版本：**1.5.8-android (1508)**。

下载 [1.5.8 红果修复版](https://github.com/my-name-is-alan/iDuanJu/releases/tag/v1.5.8-hgfix-android)：直接发布已验证产物，包内版本号不变，同签名可手动覆盖安装；不会自动提示同版本更新。

本轮新增：剧星榜、小说 TXT / 基础阅读书源（导入、搜索、发现、目录、章节阅读与续读）、预加载开关、缓存上限。
修复 CMS 分类反馈、CENC 元数据解析、去广告开关持久化和缓存保存检查。

小说当前源码支持 CSS/Jsoup、JSONPath、部分 XPath / JS、GET/POST、分页、分组发现、预取、离线下载与同书换源。已用用户提供的合集 1335 检查；不是全部书源都可用，详见 [验收记录](../docs/novel-validation-20261117.md)。完整 Java/WebView 兼容、EPUB 文件导入与朗读尚未完成。上述小说改动不在旧 `hgfix` APK 内。

## 要求
- minSdk 26 / targetSdk 35
- JDK 21（字节码目标 17）

## 构建
```bash
cd android
./gradlew :app:assembleDebug :app:assembleRelease
```
APK：`app/build/outputs/apk/{debug,release}/`

GitHub Actions 使用固定分发签名；本地默认仍用调试签名。分发构建需要设置 `ANDROID_KEYSTORE_PATH` 与 `ANDROID_KEYSTORE_PASSWORD`，别名为 `iduanju`。不要提交私钥。
从旧调试签名包升级可能需要卸载重装，卸载会清除数据；先保存书源和插件配置。

## 红果取流
已同步 gateway `0613b49`：当前 Medusa 播放签名、复用健康匿名身份、同身份端点回退、最多 3 次身份恢复；筛选独立使用 551，按剧种加载面板。公开构建不写死设备 ID 或 SDK token。也可在“我的”填入自己的 `device_id,iid` 覆盖。请下载带 `hgfix` 的修复版，原始 1.5.8 APK 不包含它。

## 测试
```bash
./gradlew :core:test
./gradlew :core:test -Plive=1                          # 联网：注册+榜单
./gradlew :core:test -Plive=1 -PhgDevice=did,iid       # 联网：取流（需可用设备对）
```
