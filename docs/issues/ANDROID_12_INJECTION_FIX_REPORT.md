# 《Android 12 Ptrace 远程调用返回陷阱与状态判定优化报告》

---

## 一、 Android 12 下显示“注入未就绪”的根本原因

1. **只读代码段断点写入失败导致函数无法返回捕获**：
   - 原 `cs_injector.cpp` 尝试将远程返回地址 `LR` 指向 `remote_caller`（系统库代码段），并尝试往只读代码段写入 `BRK #0` 内存断点。
   - 在 Android 12 的安全保护下，代码段属于 `r-xp`（只读），写入断点直接被内核拒绝（EPERM/EFAULT），而 `LR` 仍然指向该代码段。
   - 当远程 `dlopen` 执行完毕 `ret` 时，CPU 跳入正常系统代码中继续漫游执行，没有触发断点，导致 `waitpid` 无法正常接收到停止信号，寄存器 `x0` 返回值丢失，判定为注入失败！
2. **`isHookInjected` 进程 Maps 扫描命令兼容性**：
   - 原代码使用 `/proc/*/maps` 通配符展开，在 Android 12 某些精简版 Toybox 环境下可能因内核线程或无权访问进程导致 grep 返回失败。

---

## 二、 核心修复

1. **统一改用标准 `LR = 0` 硬件信号陷阱**：
   - 移除所有对只读代码段的写入操作；
   - 将返回寄存器 `LR`（ARM64 `regs[30]` / ARM32 `ARM_lr` / x86 `ret_addr`）统一设为 `0`；
   - 远程 `dlopen` 完成返回时，必然硬件触发 `SIGSEGV` 停止信号，由 `waitpid` 立即捕获并精确读取 `x0` 返回句柄，再通过 `orig_regs` 完美恢复现场！
2. **重构 `isHookInjected` 多层次检测**：
   - 优先通过 `pidof cameraserver` 精准读取 `/proc/$pid/maps`；
   - 增加 `/proc/[0-9]*/maps` 多进程兜底。

---

## 三、 构建与测试

- `gradlew.bat assembleDebug` 构建通过。
- `gradlew.bat :app:testDebugUnitTest` 单元测试全部通过。
