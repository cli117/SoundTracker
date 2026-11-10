# SoundTracker — 纯本地母带级伴奏 · 移调变速工坊 (Android)

**SoundTracker** 是一款 100% 纯客户端离线运行的 Android 音频处理应用，无需任何服务端或联网上传，在手机本地完成录音室级人声伴奏分离、自动 BPM 识别、1 BPM 精度变速与无电音移调、尽量无损的母带音量放大，以及可视化本地存储资源管理。

## 核心功能与技术架构

1. **双核 AI 母带级去人声提取伴奏 (`UVR-MDX-NET Inst-HQ5` + `Meta HTDemucs-FT v4`)**
   - **旗舰直接伴奏重构 (`UVR-MDX-NET Inst_HQ_5`)**：`dim_f = 3072` 全频段直达 `22.05 kHz` 神经网络直接预测伴奏频谱，支持双向对称消噪（`enable_denoise`），彻底消除老一代模型的人声残影与过电伪影。
   - **四轨混合 Transformer (`Meta HTDemucs-FT v4`)**：时域 + 频域混合架构同步分离 `Drums + Bass + Other` 合成高自然度伴奏，并与 `Vocals` 互补验证。
   - **双核交叉融合 (`UVR Ensemble Min-Spec Crossover Fusion`)**：结合 `Inst-HQ5` 的超高频细节与 `HTDemucs-FT` 的时域木吉他在最小能量抑制原则下融合成母带级伴奏。
   - 支持一键 **A/B 原曲对比试听**，实时无缝对比原曲与处理后成品。

2. **自动 BPM 识别 + Rubber Band v4.0 R3 移调与 1 BPM 变速工坊**
   - **C++ 20 频带对数谱通量自动测速**：基于 `1024` 点 STFT 20 频带对数谱通量（Log-Spectral Flux）瞬态包络提取 + 多周期脉冲梳状自相关（`1x, 2x, 3x, 4x`）与半倍/双倍速消歧，导入音轨时自动识别原曲 BPM。
   - **Rubber Band Library v4.0.0 `R3 (OptionEngineFiner)` 双趟离线引擎**：多分辨率多窗长 STFT（`1024 / 2048 / 4096`）瞬态导引合成，严格锁定复音泛音整数倍频率比，彻底消除移调电音感。
   - **单趟一次完成**：支持「变速不变调」、「移调不变速」或「移调 + 变速单遍一次合成」，提供直观的 `[ − ] + 滑杆 + [ + ]` 交互，支持以 **1 BPM** 和 **1 半音** 为最小粒度精准调节。

3. **尽量无损的母带级音量放大 (`Pure Lossless + 5ms True-Peak Limiter`)**
   - 实时计算 **ITU-R BS.1770-4 综合响度 (LUFS)** 与 **4x 超采样真峰值 (dBTP)**。
   - **纯线性无损区**：目标增益在 `-0.2 dBTP` 真峰值余量内执行 100% 纯浮点线性比例放大，零失真。
   - **母带前瞻防破音限幅区**：超出余量时启动 **5ms 前瞻立体声联动真峰值限幅器 (Lookahead True-Peak Limiter)**，防止硬削波破音。

4. **重命名、微信直享与交互式存储资源管理器**
   - **自由重命名与多格式导出**：支持 **MP3 (320kbps LAME)**、**M4A (256kbps AAC)** 与 **WAV (16-bit TPDF 抖动无损)**，支持一键分享到微信或另存为手机本地音乐目录。
   - **可视化存储资源管理器**：右上角「扫把 + 存储占用大小」按钮点击后展开完整存储资源列表，展示每一项音轨/缓存/模型的精确大小，支持单条删除、多选勾选批量删除以及一键全清。
   - **Claude Editorial 暖色纸感设计**：采用暖羊皮纸与赤陶色（Warm Parchment & Terracotta）视觉体系与衬线/等宽混排排版。

## 编译与安装

```bash
./gradlew assembleRelease
adb install -r build/SoundTracker.apk
```
