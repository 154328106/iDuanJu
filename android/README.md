# i短剧 Android

原生 Kotlin + Jetpack Compose，目标与 iOS 功能对齐。计划见 [PLAN_ZH.md](PLAN_ZH.md)，进度见 [PROGRESS.md](PROGRESS.md)。

当前版本：**1.5.8-android (1508)**。

本轮新增：剧星榜、小说 TXT / 基础阅读书源（导入、搜索、发现、目录、章节阅读与续读）、预加载开关、缓存上限。
修复 CMS 分类反馈、CENC 元数据解析、去广告开关持久化和缓存保存检查。

小说规则目前支持 CSS/Jsoup、简单 JSONPath、GET/POST 和分页；JS/XPath、EPUB 与朗读尚未移植。完整差异见根目录 README。

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
当前源码已同步 gateway `0613b49`：当前 Medusa 播放签名、复用健康匿名身份、同身份端点回退、最多 3 次身份恢复；筛选独立使用 551，按剧种加载面板。公开构建不写死设备 ID 或 SDK token。也可在“我的”填入自己的 `device_id,iid` 覆盖。此修复晚于已发布的 1.5.8，旧 APK 不包含它。

## 测试
```bash
./gradlew :core:test
./gradlew :core:test -Plive=1                          # 联网：注册+榜单
./gradlew :core:test -Plive=1 -PhgDevice=did,iid       # 联网：取流（需可用设备对）
```
