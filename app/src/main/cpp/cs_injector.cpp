#include "cs_injector.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <dirent.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <elf.h>
#include <dlfcn.h>
#include <algorithm>
#include <android/log.h>

#define TAG "【CS】【Injector】"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

pid_t CsInjector::findPidByName(const char* proc_name) {
    if (!proc_name) return -1;

    // Check if proc_name is already a numeric PID
    char* endptr = nullptr;
    long direct_pid = strtol(proc_name, &endptr, 10);
    if (*endptr == '\0' && direct_pid > 0) {
        char stat_path[64];
        snprintf(stat_path, sizeof(stat_path), "/proc/%ld/stat", direct_pid);
        if (access(stat_path, F_OK) == 0) {
            return static_cast<pid_t>(direct_pid);
        }
    }

    DIR* dir = opendir("/proc");
    if (!dir) return -1;

    struct dirent* entry;
    pid_t target_pid = -1;

    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type != DT_DIR) continue;

        char* p_end;
        pid_t pid = strtol(entry->d_name, &p_end, 10);
        if (*p_end != '\0' || pid <= 0) continue;

        // 1. Check comm
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/%d/comm", pid);
        FILE* fp = fopen(comm_path, "r");
        if (fp) {
            char comm[128] = {0};
            if (fgets(comm, sizeof(comm), fp)) {
                char* nl = strchr(comm, '\n');
                if (nl) *nl = '\0';
                if (strstr(comm, proc_name) != nullptr) {
                    target_pid = pid;
                    fclose(fp);
                    break;
                }
            }
            fclose(fp);
        }

        // 2. Check cmdline
        char cmdline_path[64];
        snprintf(cmdline_path, sizeof(cmdline_path), "/proc/%d/cmdline", pid);
        fp = fopen(cmdline_path, "r");
        if (fp) {
            char cmdline[256] = {0};
            if (fgets(cmdline, sizeof(cmdline), fp)) {
                if (strstr(cmdline, proc_name) != nullptr) {
                    target_pid = pid;
                    fclose(fp);
                    break;
                }
            }
            fclose(fp);
        }
    }
    closedir(dir);
    return target_pid;
}

uintptr_t CsInjector::getModuleBase(pid_t pid, const char* module_name) {
    char maps_path[64];
    if (pid < 0) {
        snprintf(maps_path, sizeof(maps_path), "/proc/self/maps");
    } else {
        snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    }

    FILE* fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[1024];
    uintptr_t base_addr = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, module_name) != nullptr) {
            char* endptr;
            base_addr = strtoull(line, &endptr, 16);
            break;
        }
    }
    fclose(fp);
    return base_addr;
}

static bool readRemoteMemory(pid_t pid, uintptr_t src, void* dest, size_t size) {
    struct iovec local_iov = { dest, size };
    struct iovec remote_iov = { reinterpret_cast<void*>(src), size };
    ssize_t r = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);
    if (r == static_cast<ssize_t>(size)) {
        return true;
    }
    const size_t word_size = sizeof(long);
    size_t words = (size + word_size - 1) / word_size;
    for (size_t i = 0; i < words; ++i) {
        long data = ptrace(PTRACE_PEEKDATA, pid, reinterpret_cast<void*>(src + i * word_size), nullptr);
        size_t copy_len = (size - i * word_size) < word_size ? (size - i * word_size) : word_size;
        memcpy(reinterpret_cast<char*>(dest) + i * word_size, &data, copy_len);
    }
    return true;
}

static bool writeRemoteMemory(pid_t pid, uintptr_t dest, const void* src, size_t size) {
    struct iovec local_iov;
    local_iov.iov_base = const_cast<void*>(src);
    local_iov.iov_len = size;

    struct iovec remote_iov;
    remote_iov.iov_base = reinterpret_cast<void*>(dest);
    remote_iov.iov_len = size;

    ssize_t written = process_vm_writev(pid, &local_iov, 1, &remote_iov, 1, 0);
    if (written == static_cast<ssize_t>(size)) {
        return true;
    }

    // Fallback: word-by-word PTRACE_POKEDATA
    const size_t word_size = sizeof(long);
    size_t words = (size + word_size - 1) / word_size;
    for (size_t i = 0; i < words; ++i) {
        long data = 0;
        size_t copy_len = (size - i * word_size) < word_size ? (size - i * word_size) : word_size;
        memcpy(&data, reinterpret_cast<const char*>(src) + i * word_size, copy_len);
        if (ptrace(PTRACE_POKEDATA, pid, reinterpret_cast<void*>(dest + i * word_size), reinterpret_cast<void*>(data)) < 0) {
            return false;
        }
    }
    return true;
}

uintptr_t CsInjector::getRemoteSymbolAddr(pid_t pid, const char* module_name, void* local_sym_addr) {
    if (!local_sym_addr) return 0;

    // Use dladdr to find the exact local module and its base address
    Dl_info info;
    if (dladdr(local_sym_addr, &info) && info.dli_fname && info.dli_fbase) {
        const char* slash = strrchr(info.dli_fname, '/');
        const char* short_name = slash ? (slash + 1) : info.dli_fname;

        uintptr_t remote_base = getModuleBase(pid, short_name);
        if (remote_base == 0) {
            remote_base = getModuleBase(pid, info.dli_fname);
        }
        if (remote_base != 0) {
            uintptr_t offset = reinterpret_cast<uintptr_t>(local_sym_addr) - reinterpret_cast<uintptr_t>(info.dli_fbase);
            LOGI("Exact symbol resolved via dladdr: module=%s, offset=0x%lx, remote_base=0x%lx", short_name, (unsigned long)offset, (unsigned long)remote_base);
            return remote_base + offset;
        }
    }

    // Fallback by provided module_name
    uintptr_t local_base = getModuleBase(-1, module_name);
    uintptr_t remote_base = getModuleBase(pid, module_name);
    if (local_base == 0 || remote_base == 0) {
        return 0;
    }
    uintptr_t offset = reinterpret_cast<uintptr_t>(local_sym_addr) - local_base;
    return remote_base + offset;
}

bool CsInjector::injectLibrary(pid_t pid, const char* so_path) {
    if (pid <= 0 || !so_path || strlen(so_path) == 0) return false;

    // 1. Ptrace 附加到目标进程
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0) {
        LOGE("ptrace attach to PID %d failed: %s", pid, strerror(errno));
        printf("[CS Injector] Error: ptrace attach failed: %s\n", strerror(errno));
        return false;
    }

    int status = 0;
    waitpid(pid, &status, WUNTRACED);

    LOGI("Successfully attached to PID %d, preparing injection of %s", pid, so_path);
    printf("[CS Injector] Attached to PID %d, preparing injection of %s\n", pid, so_path);

    // 2. 动态解析 __loader_dlopen 或 dlopen
    void* local_dlopen = dlsym(RTLD_DEFAULT, "__loader_dlopen");
    if (!local_dlopen) {
        local_dlopen = dlsym(RTLD_DEFAULT, "dlopen");
    }
    if (!local_dlopen) {
        local_dlopen = reinterpret_cast<void*>(dlopen);
    }

    uintptr_t remote_dlopen = getRemoteSymbolAddr(pid, "linker64", local_dlopen);
    if (remote_dlopen == 0) {
        remote_dlopen = getRemoteSymbolAddr(pid, "linker", local_dlopen);
    }
    if (remote_dlopen == 0) {
        remote_dlopen = getRemoteSymbolAddr(pid, "libdl.so", local_dlopen);
    }
    if (remote_dlopen == 0) {
        remote_dlopen = getRemoteSymbolAddr(pid, "libc.so", local_dlopen);
    }

    if (remote_dlopen == 0) {
        LOGE("Failed to find remote dlopen in PID %d", pid);
        printf("[CS Injector] Error: Failed to find remote dlopen symbol in PID %d\n", pid);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    LOGI("Remote dlopen address: 0x%lx", (unsigned long)remote_dlopen);
    printf("[CS Injector] Remote dlopen address: 0x%lx\n", (unsigned long)remote_dlopen);

    // 获取合法系统模块代码区作为调用者 (Bionic namespace bypass)
    uintptr_t remote_caller = getModuleBase(pid, "libcameraservice.so");
    if (remote_caller == 0) {
        remote_caller = getModuleBase(pid, "libgui.so");
    }
    if (remote_caller == 0) {
        remote_caller = getModuleBase(pid, "libui.so");
    }
    if (remote_caller == 0) {
        remote_caller = getModuleBase(pid, "libcamera_metadata.so");
    }
    if (remote_caller == 0) {
        remote_caller = getModuleBase(pid, "libc.so");
    }
    if (remote_caller != 0) {
        remote_caller += 0x1000;
    }

    size_t path_len = strlen(so_path) + 1;
    bool call_success = false;

#if defined(__aarch64__)
    struct user_pt_regs regs, orig_regs;
    struct iovec io = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io) < 0) {
        LOGE("PTRACE_GETREGSET failed");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }
    orig_regs = regs;

    // Allocate string buffer on remote stack (16-byte aligned)
    uintptr_t remote_str = (regs.sp - 512) & ~0xFull;
    if (!writeRemoteMemory(pid, remote_str, so_path, path_len)) {
        LOGE("Failed to write so_path to remote memory");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    regs.regs[0] = remote_str;             // arg0: filename
    regs.regs[1] = RTLD_NOW | RTLD_GLOBAL; // arg1: flags
    regs.regs[2] = remote_caller;          // arg2: caller_addr (__loader_dlopen 3rd arg for namespace bypass)
    regs.regs[30] = 0;                     // LR=0 -> triggers SIGSEGV on ret, captured cleanly by waitpid
    regs.pc = remote_dlopen;
    regs.sp = remote_str - 64;

    io.iov_base = &regs;
    io.iov_len = sizeof(regs);
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &io);

    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) >= 0) {
        waitpid(pid, &status, WUNTRACED);
        // Read return register x0
        io.iov_base = &regs;
        io.iov_len = sizeof(regs);
        ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io);
        uintptr_t handle = regs.regs[0];
        LOGI("Remote dlopen returned handle: 0x%lx (status=0x%x)", (unsigned long)handle, status);
        printf("[CS Injector] Remote dlopen returned handle: 0x%lx (status=0x%x)\n", (unsigned long)handle, status);
        call_success = (handle != 0);
    }

    // Restore registers
    io.iov_base = &orig_regs;
    io.iov_len = sizeof(orig_regs);
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &io);

#elif defined(__arm__)
    struct pt_regs regs, orig_regs;
    if (ptrace(PTRACE_GETREGS, pid, nullptr, &regs) < 0) {
        LOGE("PTRACE_GETREGS failed");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }
    orig_regs = regs;

    uintptr_t remote_str = (regs.ARM_sp - 512) & ~0x7ull;
    if (!writeRemoteMemory(pid, remote_str, so_path, path_len)) {
        LOGE("Failed to write so_path to remote memory");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    regs.ARM_r0 = remote_str;
    regs.ARM_r1 = RTLD_NOW | RTLD_GLOBAL;
    regs.ARM_r2 = remote_caller;
    regs.ARM_lr = 0;
    regs.ARM_pc = remote_dlopen;
    if (regs.ARM_pc & 1) {
        regs.ARM_pc &= (~1u);
        regs.ARM_cpsr |= 0x20;
    }
    regs.ARM_sp = remote_str - 32;

    ptrace(PTRACE_SETREGS, pid, nullptr, &regs);
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) >= 0) {
        waitpid(pid, &status, WUNTRACED);
        ptrace(PTRACE_GETREGS, pid, nullptr, &regs);
        uintptr_t handle = regs.ARM_r0;
        LOGI("Remote dlopen returned handle: 0x%lx", (unsigned long)handle);
        printf("[CS Injector] Remote dlopen returned handle: 0x%lx\n", (unsigned long)handle);
        call_success = (handle != 0);
    }

    ptrace(PTRACE_SETREGS, pid, nullptr, &orig_regs);

#elif defined(__x86_64__)
    struct user_regs_struct regs, orig_regs;
    if (ptrace(PTRACE_GETREGS, pid, nullptr, &regs) < 0) {
        LOGE("PTRACE_GETREGS failed");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }
    orig_regs = regs;

    uintptr_t remote_str = (regs.rsp - 512) & ~0xFull;
    if (!writeRemoteMemory(pid, remote_str, so_path, path_len)) {
        LOGE("Failed to write so_path to remote memory");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    uintptr_t ret_addr = 0;
    uintptr_t remote_sp = remote_str - 16;
    writeRemoteMemory(pid, remote_sp, &ret_addr, sizeof(ret_addr));

    regs.rdi = remote_str;
    regs.rsi = RTLD_NOW | RTLD_GLOBAL;
    regs.rdx = remote_caller;
    regs.rip = remote_dlopen;
    regs.rsp = remote_sp;

    ptrace(PTRACE_SETREGS, pid, nullptr, &regs);
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) >= 0) {
        waitpid(pid, &status, WUNTRACED);
        ptrace(PTRACE_GETREGS, pid, nullptr, &regs);
        uintptr_t handle = regs.rax;
        LOGI("Remote dlopen returned handle: 0x%lx", (unsigned long)handle);
        printf("[CS Injector] Remote dlopen returned handle: 0x%lx\n", (unsigned long)handle);
        call_success = (handle != 0);
    }

    ptrace(PTRACE_SETREGS, pid, nullptr, &orig_regs);
#endif

    // 3. 恢复并脱离调试
    ptrace(PTRACE_DETACH, pid, nullptr, (void*)0);
    LOGI("Detached from PID %d, injection result: %s", pid, call_success ? "SUCCESS" : "FAILED");
    printf("[CS Injector] Detached from PID %d, injection %s\n", pid, call_success ? "SUCCEEDED" : "FAILED");
    return call_success;
}

#ifndef CS_INJECTOR_NO_MAIN
int main(int argc, char* argv[]) {
    const char* target_process = "cameraserver";
    const char* so_path = "/data/local/tmp/libcs_camserver.so";

    if (argc >= 2) {
        target_process = argv[1];
    }
    if (argc >= 3) {
        so_path = argv[2];
    }

    printf("[CS Injector] Locating process '%s'...\n", target_process);
    pid_t pid = CsInjector::findPidByName(target_process);

    if (pid <= 0) {
        printf("[CS Injector] Error: Process '%s' not found.\n", target_process);
        return 1;
    }

    printf("[CS Injector] Target PID: %d\n", pid);
    bool ok = CsInjector::injectLibrary(pid, so_path);
    if (!ok && strcmp(so_path, "/data/local/tmp/libcs_camserver.so") == 0) {
        printf("[CS Injector] First attempt failed, trying fallback path: /data/libcs_camserver.so\n");
        ok = CsInjector::injectLibrary(pid, "/data/libcs_camserver.so");
    }
    printf("[CS Injector] Injection %s\n", ok ? "SUCCEEDED" : "FAILED");
    return ok ? 0 : 1;
}
#endif
