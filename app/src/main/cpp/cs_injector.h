#pragma once

#include <sys/types.h>
#include <cstdint>
#include <string>

/**
 * 方案二：Root 进程注入器 (cs-injector)
 */
class CsInjector {
public:
    /**
     * 根据进程名查找系统 PID
     */
    static pid_t findPidByName(const char* proc_name);

    /**
     * 读取指定进程的模块加载基地址 (解析 /proc/<pid>/maps)
     */
    static uintptr_t getModuleBase(pid_t pid, const char* module_name);

    /**
     * 计算远程进程中指定符号的内存绝对地址
     */
    static uintptr_t getRemoteSymbolAddr(pid_t pid, const char* module_name, void* local_sym_addr);

    /**
     * 将共享库注入到目标进程
     */
    static bool injectLibrary(pid_t pid, const char* so_path);
};
