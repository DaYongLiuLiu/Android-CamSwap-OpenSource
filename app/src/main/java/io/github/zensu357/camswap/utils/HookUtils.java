package io.github.zensu357.camswap.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 统一的 Hook 反射与方法解析工具类。
 */
public final class HookUtils {

    private HookUtils() {
    }

    /**
     * 根据类名和参数列表在指定 ClassLoader 中递归查找方法（支持父类继承链）
     */
    public static Method resolveMethod(ClassLoader classLoader, String className,
                                       String methodName, Class<?>... parameterTypes) throws Exception {
        Class<?> clazz = Class.forName(className, false, classLoader);
        return resolveMethod(clazz, methodName, parameterTypes);
    }

    /**
     * 在指定类中递归查找方法（支持父类继承链），并自动 setAccessible(true)
     */
    public static Method resolveMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException((clazz != null ? clazz.getName() : "null") + "#" + methodName);
    }

    /**
     * 在指定类中查找构造函数，并自动 setAccessible(true)
     */
    public static Constructor<?> resolveConstructor(Class<?> clazz, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    /**
     * 将 LibXposed interceptor 的 List<Object> 参数转换为 Object[] 数组
     */
    public static Object[] toArgs(List<Object> args) {
        if (args == null) {
            return new Object[0];
        }
        return args.toArray(new Object[0]);
    }
}
