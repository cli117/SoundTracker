# SoundTracker 项目级 Agent 协作与工程规范 (AGENTS.md)

本文档为 **SoundTracker**（`com.soundtracker.app`）仓库的常驻上下文交接与工程准则。新对话中的 Agent 进入仓库后应自动遵守本规范，无需重新摸索工具链、架构边界或重复踩坑。

---

## 一、项目定位与核心协作准则

1. **产品定位**：
   - **SoundTracker** 是一款 **100% 纯客户端离线运行** 的 Android 专业音频工作站与乐器打谱应用（无任何后端服务或云端上传）。
   - 核心四大能力域：**单曲 AI 伴奏提取 / 实时移调变速 / 母带防破音放大**、**乐器打谱与印刷级矢量五线谱工坊（含 SF2 真实采样合成）**、**1~10 轨切片混剪编曲台**、**微信直享与 GitHub Release 二进制增量差分更新（`.stpatch`）**。
   - 全面适配 **Android 手机（竖屏/横屏）、折叠屏与 Android 平板（横屏/竖屏）**。
2. **执行与交付铁律**：
   - **保护未入库本地大体积资产（严禁 `git clean -fdx`）**：仓库根目录下的 `.tools/`（本地 JDK 17 与 Android NDK）、`app/src/main/assets/models/*.onnx`（AI 分离模型权重）、`app/src/main/assets/soundfonts/studio_instruments.sf2`（采样音色库）及 `app/src/main/jniLibs/*/libonnxruntime.so` 因体积超过 200 MB 已列入 `.gitignore`。**严禁删除、移动或执行会清理 ignored 文件的命令**。
   - **按指令决定是否安装真机与发布**：
     - 仅当用户要求安装到连接的手机/平板时执行 `adb install -r build/SoundTracker.apk`；若用户说明“只编译打包、不安装”，则只生成 `build/SoundTracker.apk`。
     - 未经用户明确要求，**不要擅自执行 `git commit`、`git push` 或 `./scripts/publish_release.sh` 发版**。

---

## 二、项目架构与源码模块地图

### 1. 全局入口与状态中枢
- `app/src/main/java/com/soundtracker/app/MainActivity.kt`：
  - 应用唯一 Activity；承载顶部品牌与存储占用清理胶囊、底部三大主工作台切换栏（`单曲处理` / `乐器打谱` / `多轨剪辑`）、系统 SAF 文件导入/导出 ActivityResultLauncher，以及所有状态回调绑定。
- `app/src/main/java/com/soundtracker/app/ui/StudioViewModel.kt`：
  - 全局单一数据源（Single Source of Truth：`StateFlow<StudioUiState>`），统一编排音频解码、AI 分离、实时预览、乐器打谱编辑、30+ 步 Undo/Redo 快照栈、防抖自动保存、多轨混音合成与分享导出。

### 2. 单曲处理工坊（`ui/StudioScreen.kt` + `audio/` + `dsp/`）
- `audio/NetEaseCloudMusicManager.kt`：扫描本地网易云音乐目录，端侧通过 AES-128 元数据解包与 RC4 流解密无损还原原生 FLAC / MP3，零重编码损耗。
- `audio/AudioCodecHelper.kt`：基于 Android `MediaExtractor` + `MediaCodec` + `MediaMuxer` 的全格式本地音视频解码与 M4A (AAC 256kbps) 硬件编码导出。
- `audio/RealtimePitchAudioProcessor.kt`：挂载于 Media3 `ExoPlayer` 音频管线的实时移调（半音）与 1 BPM 精度无级变速处理器（「边听边调」零等待预览）。
- `dsp/NativeAudioEngine.kt` + `app/src/main/cpp/`：JNI 桥接层（`soundtracker_jni.cpp`）与 C++17 核心 DSP 引擎：
  - `vocal_separator.cpp`：ONNX Runtime C API 驱动双模型伴奏提取（`inst_hq5_fp16weights.onnx` 全频段去人声 + `htdemucs_ft_vocals_fp16weights.onnx` 混合时频 Transformer + 双模型分频融合）。
  - `pitch_shifter.cpp`：自动 BPM 瞬态梳状滤波测速 + Rubber Band v4.0.0 (`R3 Finer` 多分辨率引擎) 离线高精度移调变速。
  - `loudness_maximizer.cpp`：ITU-R BS.1770-4 `LUFS` 响度与 4x 超采样真峰值（`dBTP`）测量 + `-0.2 dBTP` 余量 5ms 前瞻立体声联动防破音限幅器（实时与离线同源）。
  - `mp3_encoder.cpp`：静态编译 `libmp3lame` 输出 320kbps CBR 高品质 MP3；`wav_io.cpp` 负责 16-bit TPDF 三角概率密度抖动无损 WAV 读写。

### 3. 乐器打谱与五线谱工坊（`melody/` + `ui/MelodyStudioScreen.kt` + `cpp/melody_synth.cpp`）
- `melody/MelodyModels.kt`：
  - 统一高精度符号时间轴：`TICKS_PER_BEAT = 480`（四分音符），`TICKS_PER_STEP = 120`（十六分音符）。
  - 核心数据结构：`StudioInstrument`（9 种精选采样乐器）、`TimeSignature`（任意拍号）、`SwingPreset`、`TupletPreset`（二/三/五/六/七连音）、`StaffPart`（`TREBLE` 上行高音 / `BASS` 下行低音）、`DualKeyboardStaffRouting`、`MelodyScoreNote`（含显式谱表归属 `targetStaff`、连音元数据、圆滑线组 `slurGroupId`、奏法/重音/延长号）、`MelodySnapshot`（Undo/Redo 快照）与 `MelodyStudioUiState`。
- `melody/MelodyKeySignature.kt`：完整五度圈大小调（`-7♭ ~ 0 ~ +7♯`）、中途转调事件 `ScoreKeySignatureEvent`，以及小节内临时升降还原号（`♯ / ♭ / ♮`）推导状态机。
- `melody/MelodyChords.kt`：谱表上方字母和弦标记 `ScoreChordSymbol`、跟随当前生效调号（含中途转调）的顺阶三和弦/顺阶七和弦智能推荐器、通用字母和弦文本解析器（`parseChordSymbol`，支持升降根音、大小七/属七/减七/半减七/挂留/加九/扩展音与 `/Bass` 斜杠转位）及 `G2~E4` 温暖伴奏声部排布器。
- `melody/MelodyExpressions.kt`：段落力度（`pp~ff`/`sfz`/`fp`）、渐强渐弱发夹线（`cresc.`/`dim.`）、奏法（跳音/顿音/保持音/半连音/圆滑线 Legato/延长记号 `𝄐`）、速度术语及变速曲线（`rit.`/`accel.`/`a tempo`），以及**统一音频事件编译器 `MelodyExpressionCompiler`**（将谱面音符、表情变速与上方和弦伴奏统一编译为采样帧级时间表，同时驱动实时试听与离线 WAV 导出）。
- `melody/StaffScoreEngraver.kt`：
  - 三端同源五线谱排版与渲染引擎：支持**整页多行纵向滚动谱（`PAGE_SCROLL`）**、**单行横向卷轴谱（`SINGLE_STRIP`）**以及 **A4 印刷级纯矢量 PDF（`exportScoreToPdfFile`）**。
  - 支持可选双行钢琴大谱表（`Grand Staff`，含行首古典花括号联谱号 `{`）、上下行独立补齐休止符与符干方向、触屏直点切换上下行声部（`mapPageTouchToTickAndStaff` / `mapTouchToTickAndStaff`），以及多层记号垂直防碰撞排版。
- `melody/MelodyProjectRepository.kt`：版本化 `.stscore.json` 本地多项目存档管理、跨设备导入导出，以及防杀进程丢数据的实时防抖自动保存草稿（`__autosave_draft__`）。
- `melody/KeyboardLayoutPreferences.kt`：按 `(手机/平板 × 横竖屏 × 单/双排键盘)` 组合键独立持久化键盘高度、宽度占比、琴键宽度、八度滚动位置与双排折叠状态。
- `melody/MelodyRealtimePlayer.kt` + `cpp/melody_synth.cpp`：基于 `TinySoundFont` (`tsf.h`) 的低延迟双键盘槽实时发声、房间/大厅混响、节拍器、任意进度 `seekTransport` 试听与离线 `.wav` 渲染。

### 4. 多轨切片与混剪工作台（`arranger/` + `ui/ArrangerScreen.kt` + `cpp/multi_track_mixer.cpp`）
- 支持 1~10 条弹性音轨、双指缩放时间轴（`6 ~ 120 dp/s`）、中心指针分割、左右裁剪把手、跨轨拖拽磁吸、单段音量与首尾淡入淡出、30 步撤销重做。
- 底层 `multi_track_mixer.cpp` 使用 POSIX `pread` 无锁随机读取文件切片直推单路 `AudioTrack`，杜绝多轨相位漂移与切口爆音。

### 5. 存储管理与应用内增量更新（`storage/` + `update/` + `scripts/`）
- `storage/SessionWorkspaceManager.kt` & `ShareCleanupWorker.kt`：管理工作台文件、导出目录及微信后台上传宽限期延迟清理。
- `update/AppUpdateManager.kt` & `ApkDeltaPatcher.kt`：检测 GitHub Releases（`cli117/SoundTracker`），优先匹配当前已安装 APK 的 SHA-256 下载 `STPATCH1` 二进制增量差分包（仅几 MB）在端侧流式合成完整签名 APK，支持官方直连与国内加速通道自动降级。

---

## 三、构建、真机调试与发版命令

### 1. 环境变量与标准编译命令
仓库自带本地 JDK 17（`.tools/jdk`）与 Android NDK（`.tools/android-ndk`，已在 `app/build.gradle.kts` 绑定 `ndkPath`）。每次执行 Gradle 命令前请先导出环境变量：

```bash
export JAVA_HOME="/Users/bytedance/Documents/SoundTracker/.tools/jdk"
export ANDROID_HOME="/Users/bytedance/Library/Android/sdk"

# 编译 Release 优化包（assembleRelease 会自动拷贝产物至 build/SoundTracker.apk）
./gradlew assembleRelease

# 编译检查 / 运行单元测试
./gradlew testDebugUnitTest
```

- **构建产物路径**：`build/SoundTracker.apk`（同 `app/build/outputs/apk/release/app-release.apk`，完整包约 `260 MB`）。
- **签名说明**：`app/build.gradle.kts` 中 `release` 构建显式配置了 `signingConfig = signingConfigs.getByName("debug")`，既支持 `adb install -r` 直接覆盖安装，也保证本地安装与 OTA 差分更新包签名严格一致，**请勿擅自修改签名配置**。

### 2. 真机 / 平板连接与调试命令（仅在用户要求安装或调试时执行）
```bash
export ANDROID_HOME="/Users/bytedance/Library/Android/sdk"
ADB="$ANDROID_HOME/platform-tools/adb"

# 查看已连接设备（若同时连有手机与平板，后续命令加 -s <serial>）
"$ADB" devices -l

# 覆盖安装并保留应用内数据与乐谱存档
"$ADB" install -r build/SoundTracker.apk

# 启动应用主界面
"$ADB" shell am start -n com.soundtracker.app/.MainActivity

# 查看运行崩溃与 Native DSP 日志
"$ADB" logcat -d | grep -E "AndroidRuntime|soundtracker|FATAL"
```

### 3. GitHub Release 增量差分发版流程（仅在用户明确要求 release 时执行）
1. 在 `app/build.gradle.kts` 中递增 `versionCode`（如 `111 -> 112`）与 `versionName`（如 `"1.1.1" -> "1.1.2"`）。
2. 提交并推送代码到 `main` 分支。
3. 执行一键发版脚本：
   ```bash
   ./scripts/publish_release.sh
   ```
   该脚本会自动拉取最近两个已发布版本的历史 APK 到 `build/releases_archive/`，调用 `scripts/make_apk_patch.py` 生成并自校验 `.stpatch` 二进制差分包与 `update-manifest.json`，最后通过 `gh release` 一并上传至 `cli117/SoundTracker`。

---

## 四、UI 视觉、交互与代码规范

1. **Anthropic 暖色纸感编辑台设计体系（严禁暗黑霓虹风或默认紫色）**：
   - 全局复用 `ui/theme/Theme.kt` 中的语义色常量（注意历史命名对应的实际色彩）：
     - `StudioBg` (`#F5F2EA` 暖羊皮纸背景)、`StudioSurface` (`#FAF8F3` 暖象牙卡片)、`StudioSurfaceElevated` (`#EDE9DF` 暖砂内嵌底)、`StudioBorder` (`#E0DBD0` 1dp 细石线边框)；五线谱画布纸底固定使用 `#FAF7F0`。
     - `CyanPrimary`（实际为赤陶主色 `#C96442`）、`VioletAccent`（实际为深浓咖墨色 `#1F1E1B`）、`EmeraldLossless`（鼠尾草绿 `#2D6A4F`）、`WarmUmber`（暖棕 `#7D533E`）、`AmberMastering`（暖赭黄铜 `#B56E22`）、`DangerRed`（赤陶红 `#B83A32`）。
2. **恒定高度零跳动 & 手机/平板全布局紧凑适配**：
   - 工具栏、控制坞与参数面板在切换状态（如播放/停止、选中音符/未选中、开启连音/连线）时应保持恒定高度，避免五线谱或时间轴上下跳动导致误触。
   - **Compose 顶栏/工具栏防挤压规范**：当同一 `Row` 左侧为自适应标题（`Modifier.weight(1f)`）、右侧为可横滑胶囊组时，右侧容器**必须先加 `.widthIn(max = ...)` 再加 `.horizontalScroll(rememberScrollState())`**（Modifier 顺序不可颠倒），否则 `horizontalScroll` 的无限固有宽度会在窄屏手机上把左侧 `weight(1f)` 标题挤压为 `0px`。
   - 打谱模块需同时兼顾：`平板横屏（宽屏双栏/单行工具坞）`、`平板竖屏`、`手机竖屏`、`手机横屏（短高度紧凑模式 isShortLandscape）` 以及 `单排/双排钢琴键盘` 与 `全屏看谱（收起键盘）` 模式。
3. **乐器打谱模块「四端同源 · 全链路闭环」规范**：
   凡在「乐器打谱」模块新增或修改任何记谱特性，**必须一次性打通以下 5 个环节**：
   1. **屏幕五线谱渲染与点按交互**：`StaffScoreEngraver` 的 `PAGE_SCROLL`（整页谱）与 `SINGLE_STRIP`（单行谱）均正确绘制并生成 `StaffHitTarget` 支持点选修改/删除；
   2. **矢量 PDF 导出**：`StaffScoreEngraver.exportScoreToPdfFile` 同步矢量绘制（含跨行断开续画、行首调号与提示调号）；
   3. **真实音频生效（音画一致）**：接入 `MelodyExpressionCompiler`，确保实时试听（含任意位置 Seek 跳转）与「生成音轨」离线导出 `.wav` 听感完全一致；
   4. **Undo / Redo 支持**：编辑前调用 `pushMelodyUndoSnapshot()`，并将新增状态纳入 `MelodySnapshot`、`captureCurrentSnapshot` 与 `applySnapshotToState`；
   5. **版本化存档与实时自动保存**：在 `MelodyProjectRepository.kt` 中做好 JSON 序列化、向后兼容反序列化（`opt*` 默认值兜底），并将字段加入 `computeContentFingerprint` 以触发自动保存。
4. **Native C++ / CMake 规范**：
   - `app/src/main/cpp/CMakeLists.txt` 必须保留 `-Wl,-z,max-page-size=16384` 以兼容 Android 15/16+ 的 16KB 内存页对齐要求。
   - `rubberband` 静态库必须保留 `-fno-fast-math` 编译选项，防止破坏相位声码器与 Kaiser 重采样数值精度。
   - `app/build.gradle.kts` 中 `androidResources.noCompress += listOf("onnx", "sf2")` 不可移除，确保 Native 层可通过文件描述符零拷贝加载大模型与音色库。

---

## 五、历史已踩过的坑与防回归清单（必读）

1. **【延音踏板坑】管弦/持续音色开启延音后声音无限挂死（Forever Sustain） & 切乐器重置延音**：
   - **根因**：SF2 中温暖弦乐、长笛、萨克斯、温暖电钢等音色含有无限采样循环点（Loop），不同于钢琴/吉他/竖琴/八音盒/马林巴的自然衰减包络；若松键后不主动调度释放，声音会永远不消失。
   - **防回归**：`melody_synth.cpp` 在延音开启时必须严格按该键盘槽的 `sustainDurationSec`（支持 `0.030s ~ 10.000s` 自定义文本输入）到期后平滑释放包络；此外，**切换乐器音色时绝不可重置 `sustainPedal` 开关状态**。
2. **【看谱与试听效率坑】单行长条难以全局查错 & 试听只能从第 1 小节重头播**：
   - **防回归**：
     - 保持默认提供 `整页谱 (PAGE_SCROLL)` 多行上下滚动视图与 `全屏看谱` 一键收起琴键开关，让用户一眼扫视整页核对错音；
     - 试听默认从当前光标 `cursorTick` 起播，且**播放过程中点击谱面任意小节、拍位、音符或记号，必须立即通过 `seekTransport` 跳转到该位置继续试听**（另保留独立的 `|◀` 从头播放按钮）。
3. **【琴键防误触 vs 刮奏冲突坑】快弹误挂黑键/误拖八度条，但不能牺牲刮奏（Glissando）**：
   - **防回归**：严禁通过禁用横滑手势来防误触！必须维护 `PianoTouchGuardConfig` 的四重机制：① 黑键底部圆角内缩命中区、② 刮奏同层导轨锁定（从白键起滑全程只走白键、从黑键起滑可弹黑键五声刮奏）、③ 实时弹录模式自动过滤极短擦碰邻键鬼音、④ 弹录进行中或手指按住琴键时自动锁定键盘顶部的八度滚动条与高度调节条。
4. **【双行钢琴大谱表坑】禁靠固定音高（如 `midiNote < 56`）一刀切导致串行或自动开关大谱表**：
   - **防回归**：
     - 谱面是否渲染高低音大谱表（含花括号联谱号 `{`）**唯一取决于 `melody.grandStaffEnabled`**（即使当前没有任何低音音符，开启后也必须完整渲染上下两行五线谱；仅在读取无该字段的旧版存档且含低音音符时自动兼容开启）。
     - 开启双行模式后，音符与休止符画在哪一行由 `note.resolvedStaffPart(true)` 显式决定；步进覆盖、同拍叠音、退格删除、同音延音连线（Tie）与异音圆滑线（Slur）**必须按 `StaffPart`（上行 `TREBLE` / 下行 `BASS`）严格隔离**，先写完上行再点回前面小节写下行时绝不可顶掉或切断上行旋律。
5. **【Compose 谱面缓存不刷新坑】`InteractiveStaffScoreCanvas` 的 `remember` Key 遗漏**：
   - **防回归**：`MelodyStudioScreen.kt` 中计算整页谱排版的 `pageLayout = remember(...)` 必须完整列出所有影响谱面几何的字段（`grandStaffEnabled`, `timeSignature`, `totalBars`, `notes`, `chordSymbols`, `showChordSymbols`, `dynamicMarks`, `hairpins`, `tempoEvents`, `keySignatureEvents`）。后续若新增任何谱面元素，务必加入该 `remember` 依赖列表。
6. **【五线谱多层记号重叠坑】和弦标记、速度记号、圆滑线、变调框互相打架**：
   - **防回归**：`StaffScoreEngraver.kt` 上方区域必须维持严格的自下而上垂直分层避让：五线谱顶线与高加线音符 → 跳音/重音/连音括号/圆滑线弧顶/延长号 `𝄐` → 小节号与中途变调胶囊 → **谱表上方字母和弦标记（`renderSystemChordSymbols`）** → **速度术语与变速标记（`renderSystemTempoEvents`，根据和弦层占高动态上移 `extraTopClearanceY`）**；下方区域则为低加线音符/圆滑线 → 力度记号（`pp~ff`）与渐强渐弱发夹线。
7. **【音符工具栏宽度与主界面防堆砌坑】步进时值按钮禁止堆砌冗余汉字 & 低频设置统一进二级面板**：
   - **防回归**：
     - 步进打谱栏中的 8 种音符时值按钮仅渲染清晰的五线谱音符矢量图标（全/二分/四分/八分/十六分及附点图标）并保持易点击宽度，**不要在每个音符图标旁重复写“四分音符/附点八分音符”等汉字**以免窄屏频繁横滑；文字标签仅保留给 `休止符`、`延音连线⌒`、`乐句连线⌢`、`记号·表情`、`+和弦标记`、`同拍叠音` 等高频功能胶囊。
     - 乐器打谱主界面保持「单行统一顶栏 + 五线谱画布 + 拇指控制坞 + 极简琴键顶条」四层清爽结构；低频设置（谱面视图切换、大谱表开关、和弦显示/发声开关、混响、延音秒数、Swing、循环、双排键盘与路由、键宽重置、防误触、示例曲与清空）统一收纳在顶栏 `⚙️ 设置`（`MelodyStudioSettingsDialog`）二级面板中，后续新增低频偏好项也应优先放入该面板而非平铺到主界面。
