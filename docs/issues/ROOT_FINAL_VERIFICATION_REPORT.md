# 《Root 权限主动握手与授权全量验证报告》

---

## 一、 核心改动确认

1. **交互式 `su` 管道流**：
   - 彻底修复非交互式命令可能导致的 Root 管理器（Magisk / KernelSU / APatch）弹窗被忽略或静默失败的问题。
   - 在用户点击“Root 模式”时主动拉起 `su` 握手。
2. **多语言与提示文案规范**：
   - 补充 `requesting_root_permission`、`root_mode_activated`、`root_inject_failed_tip` 等中英文全套提示文案。
3. **编译构建与测试验证**：
   - `gradlew.bat assembleDebug` -> **BUILD SUCCESSFUL** (ExitCode: 0)
   - `gradlew.bat :app:testDebugUnitTest` -> **BUILD SUCCESSFUL** (ExitCode: 0)
