package com.dearxuan.rpgmanager.node

/**
 * 内嵌 Node 运行时的 JNI 入口。
 *
 * 原生实现在 app/src/main/cpp/node-jni.cpp，编译产物在 jniLibs/<abi>/libnodejni.so，
 * 由 tools/build-node-runtime.sh 生成。方法名必须和 C++ 里的 Java_com_dearxuan_rpgmanager_node_NodeLauncher_* 对应。
 */
class NodeLauncher {

    /**
     * 启动 Node。会阻塞到 Node 退出（正常情况就是整个应用的生命周期），所以必须在子线程调用。
     * 同一个进程只能调用一次。
     */
    external fun startNodeWithArguments(arguments: Array<String>): Int

    external fun abiTag(): String
}
