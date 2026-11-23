'use strict';
/**
 * 内嵌 Node 端的 HTTP 服务。
 *
 * 职责等同于桌面端的「nw.exe 加载 file:///games/<游戏>/index.html」：
 *   GET  /g/<相对路径>      以 https 无关的本机 http 形式提供游戏资源（含按需解密、Range）
 *   GET  /__rpg/page/*.js   注入到游戏页面的脚本（nw/require 模拟层、作弊面板）
 *   POST /__rpg/fs          页面里的 require('fs') 落到这里，真正由 Node 读写磁盘
 *   GET  /__rpg/ping        就绪探测
 *
 * 这样 WebView 只是「显示层」，文件、存档、解密全部由内嵌的 Node 运行时完成。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const core = require('./core');
const crypto = core;
const mimeOf = core.mimeOf;
const altExtensions = core.altExtensions;

const PAGE_ALIASES = {
    '/__rpg/page/shim.js': 'shim.js'
};

let cachedCheatScript = null;
/** 作弊载荷优先取 Core；旧版解包资源没有新 API 时回退到 page/ 下的旧文件 */
function cheatScriptFile() {
    if (cachedCheatScript) return cachedCheatScript;
    try {
        const p = core.cheatScriptPath('android');
        if (statOrNull(p)) {
            cachedCheatScript = p;
            return p;
        }
    } catch (e) { }
    const legacy = path.join(pageDir, 'cheat.js');
    cachedCheatScript = statOrNull(legacy)
        ? legacy
        : path.join(__dirname, 'core', 'cheat', 'android.js');
    return cachedCheatScript;
}

let cfg = null;
let gamesRoot = null;
let pageDir = null;
let log = console.log;
/** 是否显示游戏内的作弊悬浮球（关掉也仍然注入 cheat.js，虚拟按键依赖它） */
let cheatUiEnabled = true;

function start(config) {
    cfg = config;
    // 根目录由 Java 侧启动后通过 /__rpg/root 下发（用户可能换文件夹），这里允许为空
    gamesRoot = cfg.gamesRoot ? path.resolve(cfg.gamesRoot) : '';
    if (typeof cfg.cheatUi === 'boolean') cheatUiEnabled = cfg.cheatUi;
    pageDir = path.join(__dirname, 'page');
    log = cfg.log || console.log;

    const server = http.createServer((req, res) => {
        try {
            handle(req, res);
        } catch (e) {
            log('[服务] 请求处理异常 ' + req.url + ': ' + (e && e.stack || e));
            try { sendText(res, 500, 'Internal Error'); } catch (ignored) { }
        }
    });

    server.on('clientError', (err, socket) => {
        try { socket.destroy(); } catch (ignored) { }
    });

    server.keepAliveTimeout = 5000;

    return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(cfg.port, '127.0.0.1', () => {
            log('[服务] 已监听 http://127.0.0.1:' + cfg.port + '  游戏根目录: ' + gamesRoot);
            resolve(server);
        });
    });
}

function handle(req, res) {
    const url = req.url || '/';
    const q = url.indexOf('?');
    const rawPath = q < 0 ? url : url.slice(0, q);
    const pathname = safeDecode(rawPath);

    if (pathname === '/__rpg/ping') {
        sendText(res, 200, 'ok', 'text/plain; charset=utf-8');
        return;
    }
    if (pathname === '/__rpg/ver') {
        sendJson(res, {
            node: process.versions.node,
            v8: process.versions.v8,
            pid: process.pid,
            gamesRoot: gamesRoot
        });
        return;
    }
    if (pathname === '/__rpg/page/cheat.js') {
        servePageScript(res, cheatScriptFile());
        return;
    }
    if (PAGE_ALIASES[pathname]) {
        servePageScript(res, PAGE_ALIASES[pathname]);
        return;
    }
    if (pathname === '/__rpg/fs') {
        handleFsRpc(req, res);
        return;
    }
    if (pathname === '/__rpg/flags') {
        // 游戏内开关（目前只有作弊界面），随时可以在设置里改
        handleFlagsChange(req, res);
        return;
    }
    if (pathname === '/__rpg/root') {
        // 用户随时可以在菜单里换游戏目录，这里直接改服务的根，不重启进程
        handleRootChange(req, res);
        return;
    }
    if (!pathname.startsWith('/g/')) {
        sendText(res, 404, 'Not Found');
        return;
    }

    let rel = pathname.slice(3);
    if (rel === '' || rel.endsWith('/')) rel += 'index.html';
    serveGameFile(req, res, rel);
}

// =====================================================================
// 游戏资源
// =====================================================================

/** 游戏资源基本不变，让 WebView 直接吃 HTTP 缓存；HTML/注入脚本只做 ETag 校验。
 *  1 小时的窗口是有意留的：用户替换/更新游戏文件后，最多一小时就能看到新内容，
 *  不用清应用数据。想更激进可以改成 86400。 */
const ASSET_MAX_AGE = 'public, max-age=3600';

// 注入片段变了就把这个数 +1：HTML 的 ETag 里带上它，旧缓存才会失效
const INJECT_VERSION = 2;

function etagOf(stat, salt) {
    return 'W/"' + (salt ? salt + '-' : '') +
        stat.size.toString(16) + '-' + Math.round(stat.mtimeMs).toString(16) + '"';
}

function isNotModified(req, etag) {
    if (req.headers['range']) return false;        // Range（音频拖动）必须给正文
    const inm = req.headers['if-none-match'];
    if (!inm) return false;
    return inm.split(',').some(v => {
        const t = v.trim();
        return t === etag || t === '*';
    });
}

function sendNotModified(res, etag, cacheControl) {
    res.writeHead(304, {
        'ETag': etag,
        'Cache-Control': cacheControl || ASSET_MAX_AGE
    });
    res.end();
}

function serveGameFile(req, res, relPath) {
    if (!gamesRoot) {
        sendText(res, 503, '尚未选择游戏文件夹');
        return;
    }
    const head = req.method === 'HEAD';
    const found = resolveWithFallback(gamesRoot, relPath);
    if (!found) {
        log('[服务] 404 ' + relPath);
        sendText(res, 404, 'Not Found');
        return;
    }

    const full = found.full;
    const stat = found.stat;
    if (!stat) {
        sendText(res, 404, 'Not Found');
        return;
    }

    if (stat.isDirectory()) {
        serveGameFile(req, res, relPath.replace(/\/?$/, '/index.html'));
        return;
    }

    if (/\.html?$/i.test(full)) {
        serveHtml(req, res, full, stat, head);
        return;
    }

    // 请求的是明文名、磁盘上只有加密文件 → 服务端解密后下发
    if (!crypto.isEncryptedPath(relPath) && crypto.isEncryptedPath(found.name)) {
        serveDecrypted(req, res, full, stat, head);
        return;
    }

    streamFile(req, res, full, stat, mimeOf(full), head);
}

/** 精确命中优先；否则按扩展名互补（.m4a ↔ .ogg ↔ .rpgmvm 之类）。
 *  命中的 stat 一起带回去，省掉调用方再 stat 一次。 */
function resolveWithFallback(root, relPath) {
    const direct = safeJoin(root, relPath);
    if (direct) {
        const stat = statOrNull(direct);
        // 目录也直接返回，交给 serveGameFile 去补 index.html
        if (stat) return { full: direct, name: path.basename(direct), stat };
    }

    const slash = relPath.lastIndexOf('/');
    const dot = relPath.lastIndexOf('.');
    if (dot <= slash) return null;
    const base = relPath.slice(0, dot);
    const ext = relPath.slice(dot + 1).toLowerCase();
    for (const alt of altExtensions(ext)) {
        const candidate = safeJoin(root, base + '.' + alt);
        if (!candidate) continue;
        const stat = statOrNull(candidate);
        if (stat && stat.isFile()) {
            return { full: candidate, name: path.basename(candidate), stat };
        }
    }
    return null;
}

function statOrNull(p) {
    try {
        return fs.statSync(p);
    } catch (e) {
        return null;
    }
}

function safeJoin(root, relPath) {
    if (typeof relPath !== 'string') return null;
    const p = path.resolve(root, relPath.replace(/^\/+/, ''));
    if (p === root) return null;
    if (!p.startsWith(root + path.sep)) return null;
    return p;
}

function serveHtml(req, res, full, stat, head) {
    const etag = etagOf(stat, 'h' + INJECT_VERSION + (cheatUiEnabled ? 'c1' : 'c0'));
    if (isNotModified(req, etag)) {
        sendNotModified(res, etag, 'no-cache');
        return;
    }
    // 异步读：同步读大文件会把 Node 事件循环卡住，连带拖慢页面的同步 fs 调用
    fs.promises.readFile(full).then(raw => {
        const html = injectHtml(decodeHtml(raw));
        const body = Buffer.from(html, 'utf8');
        res.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Content-Length': body.length,
            'Cache-Control': 'no-cache',
            'ETag': etag
        });
        if (!head) res.end(body); else res.end();
    }).catch(e => {
        log('[服务] 读取 HTML 失败 ' + full + ': ' + e);
        try { sendText(res, 500, 'Read Error'); } catch (ignored) { }
    });
}

/** 少数国产魔改游戏的 index.html 是 GBK：先严格按 UTF-8 解，失败再退回 GBK */
function decodeHtml(raw) {
    try {
        return new TextDecoder('utf-8', { fatal: true }).decode(raw);
    } catch (e) {
        for (const cs of ['gbk', 'gb18030', 'big5']) {
            try {
                return new TextDecoder(cs).decode(raw);
            } catch (ignored) { }
        }
        return raw.toString('utf8');
    }
}

/** HTML 注入规则来自 RPGManager-Core；本文件只提供宿主页的脚本地址和开关标记 */
function injectHtml(html) {
    return core.injectHtml(html, {
        scripts: [
            { src: '/__rpg/page/shim.js', attrs: 'data-rpg="shim"' },
            { src: '/__rpg/page/cheat.js', attrs: 'data-rpg="cheat"' }
        ],
        // 关闭作弊界面时只塞一个标记，cheat.js 仍要加载（window.__rpgKey 由它提供）
        flag: cheatUiEnabled ? null : 'window.__RPG_CHEAT_UI__=false;'
    });
}

/**
 * 找某个资源所属游戏的 encryptionKey：
 * 从文件所在目录逐层往上找 data/System.json（游戏可能多套一层 www，所以不能只看根目录）。
 */
const keyCache = new Map();

function encryptionKeyForFile(full) {
    let dir = path.dirname(full);
    for (let depth = 0; depth < 4; depth++) {
        if (keyCache.has(dir)) return keyCache.get(dir);
        const systemJson = path.join(dir, 'data', 'System.json');
        if (fs.existsSync(systemJson)) {
            let key = null;
            try {
                const json = JSON.parse(fs.readFileSync(systemJson, 'utf8').replace(/^\uFEFF/, ''));
                if (json && typeof json.encryptionKey === 'string' && json.encryptionKey.length === 32) {
                    key = json.encryptionKey;
                }
            } catch (e) {
                log('[服务] 读取 System.json 失败: ' + systemJson + ' -> ' + e);
            }
            keyCache.set(dir, key);
            return key;
        }
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    return null;
}

// 解密结果缓存：同一张加密图/同一段加密音频被反复请求时，不必重复读盘 + 解密
const decryptCache = new Map();          // full -> { mtimeMs, size, mime, buf }
let decryptCacheBytes = 0;
const DECRYPT_CACHE_LIMIT = 64 * 1024 * 1024;

function cacheDecrypted(full, stat, mime, buf) {
    if (buf.length > DECRYPT_CACHE_LIMIT / 2) return;
    const old = decryptCache.get(full);
    if (old) {
        decryptCacheBytes -= old.buf.length;
        decryptCache.delete(full);
    }
    decryptCache.set(full, { mtimeMs: stat.mtimeMs, size: stat.size, mime, buf });
    decryptCacheBytes += buf.length;
    while (decryptCacheBytes > DECRYPT_CACHE_LIMIT && decryptCache.size > 1) {
        const oldestKey = decryptCache.keys().next().value;
        const entry = decryptCache.get(oldestKey);
        decryptCache.delete(oldestKey);
        decryptCacheBytes -= entry.buf.length;
    }
}

function errText(e) {
    if (!e) return '';
    return (e.code ? e.code + ' ' : '') + (e.message || String(e));
}

/** 异步读取失败时再同步读一次，并把错误码写进日志，便于定位权限/路径问题 */
function readFileWithFallback(full) {
    return fs.promises.readFile(full).catch(e => {
        log('[服务] 异步读取失败 ' + full + ': ' + errText(e));
        return fs.readFileSync(full);
    });
}

function serveDecrypted(req, res, full, stat, head) {
    const etag = etagOf(stat);
    if (isNotModified(req, etag)) {
        sendNotModified(res, etag);
        return;
    }

    const cached = decryptCache.get(full);
    if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
        res.writeHead(200, {
            'Content-Type': cached.mime,
            'Content-Length': cached.buf.length,
            'Cache-Control': ASSET_MAX_AGE,
            'ETag': etag
        });
        if (!head) res.end(cached.buf); else res.end();
        return;
    }

    readFileWithFallback(full).then(data => {
        const ext = path.extname(full).toLowerCase();
        const isAudio = crypto.ENCRYPTED_AUDIO_EXTS.indexOf(ext) >= 0;
        const key = encryptionKeyForFile(full);
        let out;
        try {
            out = isAudio ? crypto.decryptAudio(data, key) : crypto.decrypt(data, key);
        } catch (e) {
            log('[服务] 解密失败 ' + full + ': ' + e);
            out = data;
        }
        const mime = crypto.sniffMime(out) || mimeOf(full);
        cacheDecrypted(full, stat, mime, out);
        res.writeHead(200, {
            'Content-Type': mime,
            'Content-Length': out.length,
            'Cache-Control': ASSET_MAX_AGE,
            'ETag': etag
        });
        if (!head) res.end(out); else res.end();
    }).catch(e => {
        log('[服务] 读取加密资源失败 ' + full + ': ' + errText(e));
        try { sendText(res, 500, 'Read Error'); } catch (ignored) { }
    });
}

function streamFile(req, res, full, stat, mime, head) {
    const etag = etagOf(stat);
    if (isNotModified(req, etag)) {
        sendNotModified(res, etag);
        return;
    }
    const total = stat.size;
    const range = req.headers['range'];
    let start = 0;
    let end = total - 1;
    let partial = false;

    if (range && range.startsWith('bytes=')) {
        const spec = range.slice(6).split(',')[0];
        const dash = spec.indexOf('-');
        if (dash >= 0) {
            const startStr = spec.slice(0, dash).trim();
            const endStr = spec.slice(dash + 1).trim();
            if (startStr) {
                start = parseInt(startStr, 10);
                if (endStr) end = parseInt(endStr, 10);
            } else if (endStr) {
                start = Math.max(0, total - parseInt(endStr, 10));
            }
            if (isFinite(start) && start >= 0 && start < total) {
                if (!isFinite(end) || end >= total) end = total - 1;
                if (end >= start) partial = true;
            } else {
                start = 0;
                end = total - 1;
            }
        }
    }

    const length = partial ? end - start + 1 : total;
    const headers = {
        'Content-Type': mime,
        'Content-Length': length,
        'Accept-Ranges': 'bytes',
        'Cache-Control': ASSET_MAX_AGE,
        'ETag': etag
    };
    if (partial) headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + total;
    res.writeHead(partial ? 206 : 200, headers);

    if (head || length === 0) {
        res.end();
        return;
    }

    const stream = fs.createReadStream(full, { start, end });
    stream.on('error', (e) => {
        log('[服务] 读取失败 ' + full + ': ' + e);
        try { res.destroy(); } catch (ignored) { }
    });
    stream.pipe(res);
}

function handleFlagsChange(req, res) {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
        try {
            const flags = JSON.parse(body || '{}');
            if (typeof flags.cheatUi === 'boolean') {
                cheatUiEnabled = flags.cheatUi;
                log('[服务] 作弊界面: ' + (cheatUiEnabled ? '开' : '关'));
            }
            sendJson(res, { ok: true, r: { cheatUi: cheatUiEnabled } });
        } catch (e) {
            sendJson(res, { ok: false, error: String((e && e.message) || e) });
        }
    });
}

function handleRootChange(req, res) {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
        try {
            const p = JSON.parse(body || '{}').path;
            if (typeof p !== 'string' || !p) throw new Error('缺少 path');
            const abs = path.resolve(p);
            if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) {
                throw new Error('目录不存在: ' + abs);
            }
            gamesRoot = abs;
            keyCache.clear();
            log('[服务] 游戏根目录已切换: ' + gamesRoot);
            sendJson(res, { ok: true, r: gamesRoot });
        } catch (e) {
            sendJson(res, { ok: false, error: String((e && e.message) || e) });
        }
    });
}

function servePageScript(res, fileOrName) {
    const file = path.isAbsolute(fileOrName) ? fileOrName : path.join(pageDir, fileOrName);
    const stat = statOrNull(file);
    if (!stat) {
        sendText(res, 404, 'missing ' + fileOrName);
        return;
    }
    fs.promises.readFile(file).then(data => {
        res.writeHead(200, {
            'Content-Type': 'application/javascript; charset=utf-8',
            'Content-Length': data.length,
            'Cache-Control': 'no-cache',
            'ETag': etagOf(stat)
        });
        res.end(data);
    }).catch(e => {
        log('[服务] 读取注入脚本失败 ' + name + ': ' + e);
        try { sendText(res, 500, 'Read Error'); } catch (ignored) { }
    });
}

// =====================================================================
// 页面里的 require('fs') → 这里
// =====================================================================

function handleFsRpc(req, res) {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => {
        body += chunk;
        if (body.length > 8 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
        let reply;
        try {
            const payload = JSON.parse(body || '{}');
            reply = { ok: true, r: runFsOp(payload) };
        } catch (e) {
            reply = { ok: false, error: String((e && e.message) || e) };
        }
        sendJson(res, reply);
    });
}

function gameDirOf(rootTag) {
    if (typeof rootTag !== 'string' || !rootTag.startsWith('/g/')) return null;
    const dir = safeJoin(gamesRoot, rootTag.slice(3));
    if (!dir) return null;
    try {
        return fs.statSync(dir).isDirectory() ? dir : null;
    } catch (e) {
        return null;
    }
}

/** 页面看到的虚拟路径是 /game/...，映射到该游戏的磁盘目录 */
function resolveVirtual(gameDir, p) {
    if (p === undefined || p === null) return null;
    let s = String(p).replace(/\\/g, '/');
    if (s === '/game') s = '/';
    else if (s.startsWith('/game/')) s = s.slice(5);
    else if (s.startsWith('/')) return null;          // 游戏不该碰虚拟根之外
    const real = path.resolve(gameDir, s.replace(/^\/+/, ''));
    if (real !== gameDir && !real.startsWith(gameDir + path.sep)) return null;
    return real;
}

function runFsOp(payload) {
    const op = payload.op;
    const args = payload.args || [];
    const gameDir = gameDirOf(payload.root);
    if (!gameDir) throw new Error('未知游戏目录: ' + payload.root);

    const toReal = (p) => {
        const real = resolveVirtual(gameDir, p);
        if (!real) throw new Error('非法路径: ' + p);
        return real;
    };

    switch (op) {
        case 'exists':
            try { fs.accessSync(toReal(args[0])); return true; } catch (e) { return false; }

        case 'readFile': {
            const buf = fs.readFileSync(toReal(args[0]));
            const enc = args[1];
            if (!enc || enc === 'buffer') return { base64: buf.toString('base64'), encoding: 'base64' };
            if (enc === 'utf8' || enc === 'utf-8') return { text: buf.toString('utf8'), encoding: 'utf8' };
            if (enc === 'base64') return { text: buf.toString('base64'), encoding: 'utf8' };
            if (enc === 'binary' || enc === 'latin1') return { text: buf.toString('latin1'), encoding: 'utf8' };
            return { text: buf.toString('utf8'), encoding: 'utf8' };
        }

        case 'writeFile': {
            const target = toReal(args[0]);
            const data = args[1];
            const enc = args[2];
            const buf = decodeData(data, enc);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.writeFileSync(target, buf);
            return true;
        }

        case 'appendFile': {
            const target = toReal(args[0]);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.appendFileSync(target, decodeData(args[1], args[2]));
            return true;
        }

        case 'mkdir': {
            fs.mkdirSync(toReal(args[0]), { recursive: args[1] !== false });
            return true;
        }

        case 'readdir':
            return fs.readdirSync(toReal(args[0]));

        // 一次拿到整个目录的条目 + 元信息：页面侧的 exists/stat/readdir 全靠它
        case 'list': {
            const dir = toReal(args[0]);
            const dirents = fs.readdirSync(dir, { withFileTypes: true });
            const out = [];
            for (let i = 0; i < dirents.length; i++) {
                const d = dirents[i];
                let st = null;
                try {
                    st = fs.statSync(path.join(dir, d.name));
                } catch (e) { }
                out.push({
                    name: d.name,
                    isDir: d.isDirectory(),
                    size: st ? st.size : 0,
                    mtimeMs: st ? st.mtimeMs : 0
                });
            }
            return out;
        }

        case 'stat': {
            const st = fs.statSync(toReal(args[0]));
            return {
                size: st.size,
                mtimeMs: st.mtimeMs,
                mode: st.mode,
                isDir: st.isDirectory(),
                isFile: st.isFile()
            };
        }

        case 'unlink':
            try { fs.unlinkSync(toReal(args[0])); return true; } catch (e) {
                if (e && e.code === 'ENOENT') return false;
                throw e;
            }

        case 'rmdir':
            fs.rmSync(toReal(args[0]), { recursive: args[1] !== false, force: true });
            return true;

        case 'copyFile':
            fs.mkdirSync(path.dirname(toReal(args[1])), { recursive: true });
            fs.copyFileSync(toReal(args[0]), toReal(args[1]));
            return true;

        case 'rename':
            fs.mkdirSync(path.dirname(toReal(args[1])), { recursive: true });
            fs.renameSync(toReal(args[0]), toReal(args[1]));
            return true;

        default:
            throw new Error('不支持的文件操作: ' + op);
    }
}

function decodeData(data, enc) {
    if (data === undefined || data === null) return Buffer.alloc(0);
    if (typeof data === 'string') {
        if (enc === 'base64') return Buffer.from(data, 'base64');
        if (enc === 'binary' || enc === 'latin1') return Buffer.from(data, 'latin1');
        return Buffer.from(data, 'utf8');
    }
    if (data.base64) return Buffer.from(data.base64, 'base64');
    return Buffer.alloc(0);
}

// =====================================================================
// 小工具
// =====================================================================

function safeDecode(s) {
    try {
        return decodeURIComponent(s);
    } catch (e) {
        return s;
    }
}

function sendJson(res, obj) {
    const body = Buffer.from(JSON.stringify(obj), 'utf8');
    res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': body.length,
        'Cache-Control': 'no-store'
    });
    res.end(body);
}

function sendText(res, code, text, mime) {
    const body = Buffer.from(text, 'utf8');
    res.writeHead(code, {
        'Content-Type': mime || 'text/plain; charset=utf-8',
        'Content-Length': body.length,
        // 404/500 一律不缓存：游戏资源可能是刚拷了一半的状态，下次还要重新问
        'Cache-Control': 'no-store'
    });
    res.end(body);
}

module.exports = { start };
