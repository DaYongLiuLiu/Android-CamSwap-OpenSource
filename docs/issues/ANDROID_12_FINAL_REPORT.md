# 《Android 12 注入陷阱与全量测试通过报告》

---

## 一、 修复总结

1. **解决只读内存写入导致的 Ptrace 挂起**：
   - 彻底移除了往目标进程系统库代码段写入 `BRK #0` 断点的操作；
   - 采用纯净的硬件陷阱 `LR = 0`，在远程 `dlopen` 返回时由 `waitpid` 捕获 `SIGSEGV`，精准读取句柄 `x0` 并恢复现场。
2. **优化注入状态检测**：
   - 优先通过 `pidof cameraserver` 直接检查 `/proc/$pid/maps`。
3. **构建与验证**：
   - `gradlew.bat assembleDebug` -> BUILD SUCCESSFUL (ExitCode: 0)
   - `gradlew.bat :app:testDebugUnitTest` -> BUILD SUCCESSFUL (ExitCode: 0)
