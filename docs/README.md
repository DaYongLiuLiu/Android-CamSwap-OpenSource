# CamSwap 项目文档中心

本文档库归档了 CamSwap 的设计方案、底层架构调研、版本升级规划与问题排查记录。

## 目录结构

```
docs/
├── README.md                               # 本文档索引
├── plans/                                  # 架构方案与升级路线
│   ├── CamSwap_Upgrade_Plan.md             # 架构演进与全功能升级方案
│   ├── CameraServer_Hook_Implementation_Plan.md # CameraServer 底层 Native Hook 实施方案
│   ├── CamSwap_vs_NoKeyCam_Comparison.md   # CamSwap 与 NoKeyCam 核心机制技术对比
│   └── FUTURE_PLANS.md                     # 后续迭代规划与功能预研
├── issues/                                 # 故障与问题追踪
│   └── ISSUES.md                           # 兼容性、风控检测与已知问题分析
└── logs/                                   # 运行与调试样本日志
    └── sample_lsposed_run.log              # LSPosed 运行时调用与状态日志样本
```

---

## 各模块文档说明

### 1. 方案规划 (docs/plans/)
- **[CamSwap_Upgrade_Plan.md](plans/CamSwap_Upgrade_Plan.md)**: 包含了多分辨率自适应、RGB/YUV 流式转换加速、抗反光与防检测活体联动的完整升级计划。
- **[CameraServer_Hook_Implementation_Plan.md](plans/CameraServer_Hook_Implementation_Plan.md)**: 详述了方案二（通过 Root 注入 cameraserver 进行底层 GraphicBuffer 替换）的实现架构与共享内存设计。
- **[CamSwap_vs_NoKeyCam_Comparison.md](plans/CamSwap_vs_NoKeyCam_Comparison.md)**: 从 Hook 拦截层级、生命周期管理、视频渲染性能等多维度对比了 CamSwap 与 NoKeyCam 的异同。
- **[FUTURE_PLANS.md](plans/FUTURE_PLANS.md)**: 记录了自动化测试、跨架构适配、更多格式支持的未来演进思路。

### 2. 问题分析 (docs/issues/)
- **[ISSUES.md](issues/ISSUES.md)**: 汇总了在各类社交软件（如微信、WhatsApp、钉钉、Line等）下的已知兼容性表现、崩溃排查与规避策略。

### 3. 日志样本 (docs/logs/)
- **[sample_lsposed_run.log](logs/sample_lsposed_run.log)**: 提供了标准运行环境下 Camera2 / YuvDecoder / GL 渲染的运行日志样本，供联调与性能分析参考。
