// JNI 桥：把 Java 的 startNodeWithArguments(String[]) 转成 node::Start(argc, argv)。
//
// 只做一件事——启动内嵌的 Node 运行时。之后的 HTTP 服务、文件读写、解密全部在 JS 里完成，
// Java 侧不需要再跟 Node 交互（进程内共享同一份 V8，反复启停不安全）。
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <string>

#include "node.h"

// libuv 要求 argv 的每个字符串在内存中连续，这里先把所有参数拷进一块缓冲区再切片。
extern "C" JNIEXPORT jint JNICALL
Java_com_dearxuan_rpgmanager_node_NodeLauncher_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

    jsize count = env->GetArrayLength(arguments);

    int total = 0;
    for (jsize i = 0; i < count; i++) {
        jstring js = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cs = env->GetStringUTFChars(js, nullptr);
        total += (int) strlen(cs) + 1;
        env->ReleaseStringUTFChars(js, cs);
        env->DeleteLocalRef(js);
    }

    char *buffer = (char *) calloc(total > 0 ? total : 1, sizeof(char));
    if (buffer == nullptr) return -1;

    char **argv = (char **) calloc(count > 0 ? count : 1, sizeof(char *));
    if (argv == nullptr) {
        free(buffer);
        return -1;
    }

    char *cursor = buffer;
    for (jsize i = 0; i < count; i++) {
        jstring js = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cs = env->GetStringUTFChars(js, nullptr);
        size_t len = strlen(cs);
        memcpy(cursor, cs, len);
        cursor[len] = '\0';
        argv[i] = cursor;
        cursor += len + 1;
        env->ReleaseStringUTFChars(js, cs);
        env->DeleteLocalRef(js);
    }

    int code = node::Start((int) count, argv);

    free(argv);
    free(buffer);
    return code;
}

// 探测用：确认 native 库确实被加载、且 ABI 对得上
extern "C" JNIEXPORT jstring JNICALL
Java_com_dearxuan_rpgmanager_node_NodeLauncher_abiTag(
        JNIEnv *env,
        jobject /* this */) {
#if defined(__aarch64__)
    const char *tag = "arm64-v8a";
#elif defined(__arm__)
    const char *tag = "armeabi-v7a";
#elif defined(__x86_64__)
    const char *tag = "x86_64";
#else
    const char *tag = "unknown";
#endif
    return env->NewStringUTF(tag);
}
