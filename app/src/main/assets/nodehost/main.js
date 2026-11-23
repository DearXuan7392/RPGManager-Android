'use strict';
/**
 * 内嵌 Node 运行时入口（相当于桌面端的 nw.exe）。
 *
 * Java 侧只负责：把本文件解压到应用目录 → 调 node::Start(["node", main.js, <configJson>])
 * → 轮询 /__rpg/ping 确认就绪。之后游戏的资源加载、解密、存档读写都由这里完成。
 *
 * configJson: { gamesRoot, port, logFile, tmpDir }
 */

const fs = require('fs');
const path = require('path');

const cfg = (() => {
    try {
        return JSON.parse(process.argv[2] || '{}');
    } catch (e) {
        return {};
    }
})();

// 安卓应用的 stdout/stderr 会被系统丢掉，日志一律落盘，出问题时可从应用目录取回。
const logFile = cfg.logFile || path.join(__dirname, 'node.log');

function fmt(v) {
    if (typeof v === 'string') return v;
    if (v instanceof Error) return v.stack || v.message;
    try {
        return JSON.stringify(v);
    } catch (e) {
        return String(v);
    }
}

function write(level, args) {
    const line = '[' + new Date().toISOString() + '][' + level + '] ' +
        Array.prototype.map.call(args, fmt).join(' ') + '\n';
    try {
        fs.appendFileSync(logFile, line);
    } catch (e) { }
}

try {
    // 每次启动覆盖，避免日志无限增长
    fs.writeFileSync(logFile, '');
} catch (e) { }

console.log = function () { write('I', arguments); };
console.info = console.log;
console.warn = function () { write('W', arguments); };
console.error = function () { write('E', arguments); };
console.debug = function () { write('D', arguments); };

process.on('uncaughtException', (e) => {
    write('E', ['未捕获异常: ' + (e && e.stack || e)]);
});
process.on('unhandledRejection', (e) => {
    write('E', ['未处理的 Promise 拒绝: ' + (e && e.stack || e)]);
});

if (cfg.tmpDir) {
    try { process.env.TMPDIR = cfg.tmpDir; } catch (e) { }
}

write('I', ['Node ' + process.versions.node + ' 启动，参数 ' + JSON.stringify(cfg)]);

const server = require('./server');

server.start(cfg).then(() => {
    write('I', ['服务就绪']);
}).catch((e) => {
    write('E', ['服务启动失败: ' + (e && e.stack || e)]);
    process.exitCode = 1;
});
