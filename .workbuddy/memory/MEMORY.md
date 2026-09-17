# 项目长期记忆 — AMLL-DroidMate2

## 构建与目录约定（重要，避免返工）
- `app/src/main/assets/amll/`（含 `amll.bundle.js`、`index.html`、`styles.css`）是 **Gradle 构建产物**，由 `app/build.gradle.kts` 的 `buildFrontend` 任务生成：先 `deleteRecursively` 清空该目录，再从 `frontend/dist/` 复制 bundle + 复制 `frontend/styles.css` 与 `frontend/index.html`。
  - 因此 **不要直接编辑 `app/src/main/assets/amll/`** 下的文件，改动会在构建时被覆盖。
  - WebView 样式 → 改 `frontend/styles.css`；前端逻辑 → 改 `frontend/src/`；原生注入桥 → 改 `AMLLLyricsView.kt`（Kotlin，不在 bundle 内）。
- 沙箱中 `./gradlew` 跑不起来（缺 uname/sed），用 `java -jar gradle/wrapper/gradle-wrapper.jar :app:compileDebugKotlin --offline` 代替。

## 歌词组件特性
- 字重/字号调节：设置存于 `AMLLSettings`（`getAmllFontWeight`/`getAmllLyricFontSize` 等），由 `AMLLLyricsView` 在 `update` 中注入到 WebView（CSS 变量 `--amll-lp-font-weight` / `--amll-user-font-size`，并直接对 `.amll-lyric-player` 设 inline style 兜底），UI 在 `ComponentSettings.kt`。
- 核心（`@applemusic-like-lyrics`）通过 `--amll-lp-font-size` 消费字号；`--amll-lp-font-size-preset` 在该版本核心中未被消费，旧 `setLyricSizePreset` 实际无效。
