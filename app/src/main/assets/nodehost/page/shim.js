/**
 * shim.js —— 在游戏页面里伪造出「NW.js / Node」环境（安卓版）
 *
 * 桌面端游戏跑在 nw.exe 里，页面里直接就有 require / process / nw。安卓上没有 V8 与页面共享
 * 全局对象这回事，所以这里的做法是：
 *
 *   页面里的 require('fs').xxxSync()  →  同步 XHR  →  内嵌的 Node 进程真正读写磁盘
 *
 * 用同步 XHR 是因为 Node 的 fs 是同步 API，返回值必须当场拿到（存档就是这么用的）。
 * 服务器就在 127.0.0.1 上、与页面同源，所以同步 XHR 合法且开销极小（一次几毫秒）。
 *
 * 装好之后：
 *   Utils.isNwjs() === true  →  引擎走「单机」分支，存档写进游戏目录的 save/，
 *   与桌面端存档格式、位置完全一致，可以直接互相拷贝。
 */
(function () {
    'use strict';

    if (window.__RPG_NODE_SHIM__) return;

    var VROOT = '/game';                 // 页面里看到的虚拟根（映射到该游戏的磁盘目录）
    var RPC_URL = '/__rpg/fs';

    // ---------------------------------------------------------------
    // 虚拟根：从 location 推断（页面是 /g/<游戏相对路径>/index.html）
    // ---------------------------------------------------------------
    var ROOT_TAG = (function () {
        try {
            var p = decodeURIComponent(location.pathname || '');
            var idx = p.lastIndexOf('/');
            return idx > 0 ? p.slice(0, idx) : p;
        } catch (e) {
            return '/g/';
        }
    })();

    // ---------------------------------------------------------------
    // 与 Node 的通道
    // ---------------------------------------------------------------
    function rpc(op, args) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', RPC_URL, false);          // 同步
        xhr.setRequestHeader('Content-Type', 'application/json');
        try {
            xhr.send(JSON.stringify({ root: ROOT_TAG, op: op, args: args || [] }));
        } catch (e) {
            throw rpcError('EBRIDGE', '无法连接内嵌 Node 服务: ' + e);
        }
        if (xhr.status !== 200) throw rpcError('EBRIDGE', 'Node 服务返回 ' + xhr.status);
        var reply;
        try {
            reply = JSON.parse(xhr.responseText);
        } catch (e) {
            throw rpcError('EBRIDGE', 'Node 服务返回了无法解析的内容');
        }
        if (!reply.ok) throw rpcError(reply.code || 'EIO', reply.error || '未知错误');
        return reply.r;
    }

    function rpcError(code, message) {
        var err = new Error(message);
        err.code = code;
        return err;
    }

    // 探测一次：Node 没起来就完全不装 require，游戏退回浏览器模式（至少还能玩）
    try {
        rpc('exists', [VROOT]);
    } catch (e) {
        console.error('[RPG] 内嵌 Node 服务不可用，回退到浏览器模式：' + (e && e.message));
        return;
    }

    // ---------------------------------------------------------------
    // Buffer（Uint8Array 子类，够 MV/MZ 和常见插件用）
    // ---------------------------------------------------------------
    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

    function b64Encode(bytes) {
        var out = '';
        var i = 0;
        for (; i + 2 < bytes.length; i += 3) {
            var n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
            out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63];
        }
        var rest = bytes.length - i;
        if (rest === 1) {
            var a = bytes[i] << 16;
            out += B64[(a >> 18) & 63] + B64[(a >> 12) & 63] + '==';
        } else if (rest === 2) {
            var b = (bytes[i] << 16) | (bytes[i + 1] << 8);
            out += B64[(b >> 18) & 63] + B64[(b >> 12) & 63] + B64[(b >> 6) & 63] + '=';
        }
        return out;
    }

    function b64Decode(str) {
        var clean = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
        var len = clean.length;
        var pad = 0;
        if (len && clean[len - 1] === '=') pad++;
        if (len > 1 && clean[len - 2] === '=') pad++;
        var outLen = Math.floor(len * 3 / 4) - pad;
        var out = new Uint8Array(outLen > 0 ? outLen : 0);
        var p = 0;
        for (var i = 0; i < len; i += 4) {
            var n = 0;
            for (var j = 0; j < 4; j++) {
                var ch = clean[i + j];
                var v = ch === '=' || ch === undefined ? 0 : B64.indexOf(ch);
                n = (n << 6) | (v < 0 ? 0 : v);
            }
            if (p < outLen) out[p++] = (n >> 16) & 255;
            if (p < outLen) out[p++] = (n >> 8) & 255;
            if (p < outLen) out[p++] = n & 255;
        }
        return out;
    }

    function BytesUtf8Encode(str) {
        var s = String(str);
        if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(s);
        var utf8 = unescape(encodeURIComponent(s));
        var out = new Uint8Array(utf8.length);
        for (var i = 0; i < utf8.length; i++) out[i] = utf8.charCodeAt(i);
        return out;
    }

    function BytesUtf8Decode(bytes) {
        if (typeof TextDecoder !== 'undefined') return new TextDecoder('utf-8').decode(bytes);
        var s = '';
        for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
        try {
            return decodeURIComponent(escape(s));
        } catch (e) {
            return s;
        }
    }

    function Buffer(arg, encodingOrOffset, length) {
        var u8;
        if (typeof arg === 'number') {
            u8 = new Uint8Array(arg);
        } else if (arg instanceof Uint8Array) {
            u8 = new Uint8Array(arg);
        } else if (arg instanceof ArrayBuffer) {
            u8 = new Uint8Array(arg, encodingOrOffset || 0, length || (arg.byteLength - (encodingOrOffset || 0)));
        } else if (Array.isArray(arg)) {
            u8 = new Uint8Array(arg);
        } else {
            u8 = new Uint8Array(0);
        }
        Object.setPrototypeOf(u8, Buffer.prototype);
        return u8;
    }
    Buffer.prototype = Object.create(Uint8Array.prototype);
    Buffer.prototype.constructor = Buffer;
    Object.defineProperty(Buffer.prototype, '_isBuffer', { value: true });

    Buffer.prototype.toString = function (encoding, start, end) {
        var u8 = new Uint8Array(this.buffer, this.byteOffset, this.byteLength);
        var slice = u8.subarray(start || 0, end === undefined ? u8.length : end);
        var enc = (encoding || 'utf8').toLowerCase();
        if (enc === 'base64') return b64Encode(slice);
        if (enc === 'hex') {
            var hex = '';
            for (var i = 0; i < slice.length; i++) hex += (slice[i] < 16 ? '0' : '') + slice[i].toString(16);
            return hex;
        }
        if (enc === 'binary' || enc === 'latin1') {
            var s = '';
            for (var j = 0; j < slice.length; j++) s += String.fromCharCode(slice[j]);
            return s;
        }
        return BytesUtf8Decode(slice);
    };

    Buffer.prototype.toJSON = function () {
        return { type: 'Buffer', data: Array.prototype.slice.call(this) };
    };

    Buffer.from = function (value, encoding) {
        if (typeof value === 'string') {
            var enc = (encoding || 'utf8').toLowerCase();
            if (enc === 'base64') return Buffer(b64Decode(value));
            if (enc === 'hex') {
                var out = new Uint8Array(Math.floor(value.length / 2));
                for (var i = 0; i < out.length; i++) out[i] = parseInt(value.substr(i * 2, 2), 16);
                return Buffer(out);
            }
            if (enc === 'binary' || enc === 'latin1') {
                var arr = new Uint8Array(value.length);
                for (var k = 0; k < value.length; k++) arr[k] = value.charCodeAt(k) & 255;
                return Buffer(arr);
            }
            return Buffer(BytesUtf8Encode(value));
        }
        if (value instanceof ArrayBuffer) return Buffer(new Uint8Array(value));
        if (value && value.buffer instanceof ArrayBuffer) {
            return Buffer(new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength));
        }
        return Buffer(value);
    };
    Buffer.alloc = function (size, fill) {
        var u8 = new Uint8Array(size);
        if (fill) u8.fill(typeof fill === 'string' ? fill.charCodeAt(0) : fill);
        return Buffer(u8);
    };
    Buffer.allocUnsafe = Buffer.alloc;
    Buffer.isBuffer = function (v) {
        return !!(v && v._isBuffer);
    };
    Buffer.concat = function (list, totalLength) {
        var total = totalLength;
        if (total === undefined) {
            total = 0;
            for (var i = 0; i < list.length; i++) total += list[i].length;
        }
        var out = new Uint8Array(total);
        var pos = 0;
        for (var j = 0; j < list.length; j++) {
            out.set(list[j], pos);
            pos += list[j].length;
        }
        return Buffer(out);
    };
    Buffer.byteLength = function (str, enc) {
        return Buffer.from(str, enc).length;
    };

    // ---------------------------------------------------------------
    // path（虚拟路径一律是 POSIX 风格）
    // ---------------------------------------------------------------
    var pathModule = (function () {
        function normalizeArray(parts, allowAboveRoot) {
            var res = [];
            for (var i = 0; i < parts.length; i++) {
                var p = parts[i];
                if (!p || p === '.') continue;
                if (p === '..') {
                    if (res.length && res[res.length - 1] !== '..') res.pop();
                    else if (allowAboveRoot) res.push('..');
                } else {
                    res.push(p);
                }
            }
            return res;
        }

        function normalize(p) {
            p = String(p);
            if (p.length === 0) return '.';
            var isAbs = p.charAt(0) === '/';
            var trailing = p.charAt(p.length - 1) === '/';
            p = normalizeArray(p.split('/'), !isAbs).join('/');
            if (!p && !isAbs) p = '.';
            if (p && trailing) p += '/';
            return (isAbs ? '/' : '') + p;
        }

        function join() {
            var parts = [];
            for (var i = 0; i < arguments.length; i++) {
                if (arguments[i]) parts.push(String(arguments[i]));
            }
            if (!parts.length) return '.';
            return normalize(parts.join('/'));
        }

        function resolve() {
            var resolved = '';
            var isAbs = false;
            for (var i = arguments.length - 1; i >= 0 && !isAbs; i--) {
                var p = String(arguments[i] || '');
                if (!p) continue;
                resolved = p + '/' + resolved;
                isAbs = p.charAt(0) === '/';
            }
            if (!isAbs) resolved = VROOT + '/' + resolved;
            resolved = normalizeArray(resolved.split('/'), false).join('/');
            return '/' + resolved;
        }

        function dirname(p) {
            p = String(p);
            if (!p) return '.';
            var hasRoot = p.charAt(0) === '/';
            var end = -1;
            var matchedSlash = true;
            for (var i = p.length - 1; i >= 1; i--) {
                if (p.charAt(i) === '/') {
                    if (!matchedSlash) {
                        end = i;
                        break;
                    }
                } else {
                    matchedSlash = false;
                }
            }
            if (end === -1) return hasRoot ? '/' : '.';
            if (hasRoot && end === 1) return '//';
            return p.slice(0, end);
        }

        function basename(p, ext) {
            p = String(p);
            var base = p.slice(p.lastIndexOf('/') + 1);
            if (ext && base.length > ext.length && base.slice(-ext.length) === ext) {
                return base.slice(0, -ext.length);
            }
            return base;
        }

        function extname(p) {
            var base = basename(p);
            var idx = base.lastIndexOf('.');
            if (idx <= 0) return '';
            return base.slice(idx);
        }

        function isAbsolute(p) {
            return String(p).charAt(0) === '/';
        }

        function relative(from, to) {
            var f = resolve(from).split('/').filter(Boolean);
            var t = resolve(to).split('/').filter(Boolean);
            var i = 0;
            while (i < f.length && i < t.length && f[i] === t[i]) i++;
            var up = [];
            for (var j = i; j < f.length; j++) up.push('..');
            return up.concat(t.slice(i)).join('/') || '';
        }

        return {
            sep: '/',
            delimiter: ':',
            normalize: normalize,
            join: join,
            resolve: resolve,
            dirname: dirname,
            basename: basename,
            extname: extname,
            isAbsolute: isAbsolute,
            relative: relative,
            posix: null
        };
    })();
    pathModule.posix = pathModule;

    // ---------------------------------------------------------------
    // fs —— 页面里最热的调用
    //
    // 引擎判断"存档在不在"、读存档都走这里。MV 的读档界面会给每个存档位算一遍
    // loadGlobalInfo()（读 1 次 global.rpgsave + 20 次 exists），20 个位置就是近千次调用。
    // 这些调用在本地存储/桌面端都是微秒级的，但每次走一遍同步 HTTP 就是毫秒级，
    // 累加起来就是"点继续要等好几秒"。
    //
    // 所以这里加两层缓存：
    //   目录缓存：dir -> 条目表。一次 list RPC 就能回答整个目录的 exists/stat/readdir
    //   文件缓存：path -> 原始字节。重复读同一个存档不再走 HTTP
    //
    // 写入方只有 Node 一个，所以写操作成功后按路径精确失效；再留 1 秒 TTL，
    // 兜住"游戏运行时从电脑拷了存档进来"这种情况。
    // ---------------------------------------------------------------

    var CACHE_TTL = 1000;

    var dirCache = Object.create(null);    // dir -> { entries, expires }
    var fileCache = Object.create(null);   // path -> { b64, expires }
    var fsStats = { rpc: 0, hits: 0 };

    /**
     * 统一成虚拟绝对路径（/game/...）：相对路径按 Node 的 cwd 语义（也就是游戏根）解析。
     * 这样同一份文件不会因为写法不同（'save/x' 与 '/game/save/x'）被缓存成两份，
     * 失效也就不会漏。
     */
    function normPath(p) {
        var s = String(p === undefined || p === null ? '' : p).replace(/\\/g, '/');
        if (s.charAt(0) !== '/') s = VROOT + '/' + s;
        s = pathModule.normalize(s);
        if (s.length > 1) s = s.replace(/\/+$/, '');
        return s || '/';
    }

    function parentOf(p) {
        var i = p.lastIndexOf('/');
        return i <= 0 ? '/' : p.slice(0, i);
    }

    function baseOf(p) {
        return p.slice(p.lastIndexOf('/') + 1);
    }

    function isRootPath(p) {
        return p === VROOT || p === '/';
    }

    /**
     * 目录条目表：TTL 内所有 exists/stat/readdir 都命中缓存。
     * 目录不存在时缓存空表，避免对同一个坏路径反复打服务端。
     */
    function dirEntries(dir) {
        var hit = dirCache[dir];
        if (hit && hit.expires > Date.now()) {
            fsStats.hits++;
            return hit.entries;
        }
        var list;
        try {
            fsStats.rpc++;
            list = rpc('list', [dir]);
        } catch (e) {
            dirCache[dir] = { entries: null, expires: Date.now() + CACHE_TTL };
            return null;
        }
        var map = Object.create(null);
        for (var i = 0; i < list.length; i++) {
            map[list[i].name] = list[i];
        }
        dirCache[dir] = { entries: map, expires: Date.now() + CACHE_TTL };
        return map;
    }

    /** 在目录条目里按名字精确查找（安卓文件系统区分大小写，引擎用的文件名都是自己写的，不会不一致） */
    function findEntry(dir, name) {
        var entries = dirEntries(dir);
        return entries ? (entries[name] || null) : null;
    }

    function invalidatePath(p) {
        delete fileCache[p];
        delete dirCache[parentOf(p)];
    }

    function invalidateDir(dir) {
        delete dirCache[dir];
    }

    function statToObject(info) {
        return {
            size: info.size,
            mode: info.mode || 0,
            mtime: new Date(info.mtimeMs),
            mtimeMs: info.mtimeMs,
            atime: new Date(info.mtimeMs),
            ctime: new Date(info.mtimeMs),
            isDirectory: function () { return !!info.isDir; },
            isFile: function () { return !info.isDir; },
            isSymbolicLink: function () { return false; }
        };
    }

    function toEncoding(options) {
        if (typeof options === 'string') return options;
        if (options && typeof options === 'object' && options.encoding) return options.encoding;
        return null;
    }

    /** 文件内容以 base64 缓存，取用时就地转成调用方要的编码，避免同一文件多份缓存 */
    function fromBase64(b64, enc) {
        var buf = Buffer.from(b64 || '', 'base64');
        if (!enc || enc === 'buffer') return buf;
        var e = String(enc).toLowerCase();
        if (e === 'base64') return buf.toString('base64');
        if (e === 'binary' || e === 'latin1') return buf.toString('binary');
        if (e === 'hex') return buf.toString('hex');
        return buf.toString('utf8');
    }

    /** 异步接口用微任务排队，比 setTimeout(0) 少一个宏任务往返 */
    function defer(fn) {
        if (typeof queueMicrotask === 'function') queueMicrotask(fn);
        else Promise.resolve().then(fn);
    }

    var fsModule = {
        existsSync: function (p) {
            var path = normPath(p);
            if (isRootPath(path)) return true;
            try {
                return !!findEntry(parentOf(path), baseOf(path));
            } catch (e) {
                return false;
            }
        },
        readFileSync: function (p, options) {
            var path = normPath(p);
            var cached = fileCache[path];
            if (cached && cached.expires > Date.now()) {
                fsStats.hits++;
                return fromBase64(cached.b64, toEncoding(options));
            }
            fsStats.rpc++;
            var r;
            try {
                // 统一取原始字节，编码在本地转
                r = rpc('readFile', [path, null]);
            } catch (e) {
                invalidatePath(path);
                throw e;
            }
            var b64 = (r && r.base64) ? r.base64 : '';
            fileCache[path] = { b64: b64, expires: Date.now() + CACHE_TTL };
            return fromBase64(b64, toEncoding(options));
        },
        writeFileSync: function (p, data, options) {
            var path = normPath(p);
            var enc = toEncoding(options);
            fsStats.rpc++;
            if (data instanceof ArrayBuffer) {
                rpc('writeFile', [path, { base64: b64Encode(new Uint8Array(data)) }, 'base64']);
            } else if (data && (data._isBuffer || data instanceof Uint8Array)) {
                rpc('writeFile', [path, { base64: b64Encode(new Uint8Array(data)) }, 'base64']);
            } else {
                rpc('writeFile', [path, String(data), enc || 'utf8']);
            }
            invalidatePath(path);
        },
        appendFileSync: function (p, data, options) {
            var path = normPath(p);
            var enc = toEncoding(options);
            fsStats.rpc++;
            if (data && (data._isBuffer || data instanceof Uint8Array)) {
                rpc('appendFile', [path, { base64: b64Encode(new Uint8Array(data)) }, 'base64']);
            } else {
                rpc('appendFile', [path, String(data), enc || 'utf8']);
            }
            invalidatePath(path);
        },
        mkdirSync: function (p, options) {
            var path = normPath(p);
            var recursive = !options || options === true || options.recursive !== false;
            fsStats.rpc++;
            rpc('mkdir', [path, recursive]);
            invalidateDir(path);
            invalidateDir(parentOf(path));
        },
        readdirSync: function (p) {
            var path = normPath(p);
            var entries = dirEntries(path);
            if (entries) {
                var names = [];
                for (var key in entries) names.push(key);
                return names;
            }
            // 目录确实打不开时让服务端抛出真实错误（ENOENT 等）
            fsStats.rpc++;
            return rpc('readdir', [path]);
        },
        statSync: function (p) {
            var path = normPath(p);
            if (isRootPath(path)) {
                return statToObject({ size: 0, mtimeMs: 0, isDir: true });
            }
            var entry = findEntry(parentOf(path), baseOf(path));
            if (entry) return statToObject(entry);
            fsStats.rpc++;
            return statToObject(rpc('stat', [path]));
        },
        lstatSync: function (p) {
            return fsModule.statSync(p);
        },
        unlinkSync: function (p) {
            var path = normPath(p);
            fsStats.rpc++;
            rpc('unlink', [path]);
            invalidatePath(path);
        },
        rmdirSync: function (p, options) {
            var path = normPath(p);
            var recursive = !options || options === true || options.recursive !== false;
            fsStats.rpc++;
            rpc('rmdir', [path, recursive]);
            invalidateDir(path);
            invalidateDir(parentOf(path));
        },
        rmSync: function (p, options) {
            fsModule.rmdirSync(p, options);
        },
        copyFileSync: function (from, to) {
            fsStats.rpc++;
            rpc('copyFile', [normPath(from), normPath(to)]);
            invalidatePath(normPath(to));
        },
        renameSync: function (from, to) {
            fsStats.rpc++;
            rpc('rename', [normPath(from), normPath(to)]);
            invalidatePath(normPath(from));
            invalidatePath(normPath(to));
        },
        accessSync: function (p) {
            if (!fsModule.existsSync(p)) throw rpcError('ENOENT', 'no such file: ' + p);
        },
        chmodSync: function () { },
        closeSync: function () { },

        // ---- 异步接口：语义上是异步，内部同样走缓存 ----
        readFile: function (p, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            var opts = typeof options === 'function' ? null : options;
            defer(function () {
                try {
                    callback(null, fsModule.readFileSync(p, opts));
                } catch (e) {
                    callback(e);
                }
            });
        },
        writeFile: function (p, data, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            var opts = typeof options === 'function' ? null : options;
            defer(function () {
                try {
                    fsModule.writeFileSync(p, data, opts);
                    callback(null);
                } catch (e) {
                    callback(e);
                }
            });
        },
        appendFile: function (p, data, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            var opts = typeof options === 'function' ? null : options;
            defer(function () {
                try {
                    fsModule.appendFileSync(p, data, opts);
                    callback(null);
                } catch (e) {
                    callback(e);
                }
            });
        },
        mkdir: function (p, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            var opts = typeof options === 'function' ? null : options;
            defer(function () {
                try {
                    fsModule.mkdirSync(p, opts);
                    callback(null);
                } catch (e) {
                    callback(e);
                }
            });
        },
        readdir: function (p, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            defer(function () {
                try {
                    callback(null, fsModule.readdirSync(p));
                } catch (e) {
                    callback(e);
                }
            });
        },
        stat: function (p, cb) {
            defer(function () {
                try {
                    cb(null, fsModule.statSync(p));
                } catch (e) {
                    cb(e);
                }
            });
        },
        unlink: function (p, cb) {
            defer(function () {
                try {
                    fsModule.unlinkSync(p);
                    if (cb) cb(null);
                } catch (e) {
                    if (cb) cb(e);
                }
            });
        },
        rm: function (p, options, cb) {
            var callback = typeof options === 'function' ? options : cb;
            var opts = typeof options === 'function' ? null : options;
            defer(function () {
                try {
                    fsModule.rmdirSync(p, opts);
                    if (callback) callback(null);
                } catch (e) {
                    if (callback) callback(e);
                }
            });
        },
        copyFile: function (from, to, cb) {
            defer(function () {
                try {
                    fsModule.copyFileSync(from, to);
                    if (cb) cb(null);
                } catch (e) {
                    if (cb) cb(e);
                }
            });
        },
        constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 },
        promises: null
    };

    fsModule.promises = {
        readFile: function (p, o) {
            return new Promise(function (res, rej) {
                fsModule.readFile(p, o, function (e, d) { e ? rej(e) : res(d); });
            });
        },
        writeFile: function (p, d, o) {
            return new Promise(function (res, rej) {
                fsModule.writeFile(p, d, o, function (e) { e ? rej(e) : res(); });
            });
        },
        mkdir: function (p, o) {
            return new Promise(function (res, rej) {
                fsModule.mkdir(p, o, function (e) { e ? rej(e) : res(); });
            });
        },
        readdir: function (p, o) {
            return new Promise(function (res, rej) {
                fsModule.readdir(p, o, function (e, d) { e ? rej(e) : res(d); });
            });
        },
        stat: function (p) {
            return new Promise(function (res, rej) {
                fsModule.stat(p, function (e, d) { e ? rej(e) : res(d); });
            });
        },
        unlink: function (p) {
            return new Promise(function (res, rej) {
                fsModule.unlink(p, function (e) { e ? rej(e) : res(); });
            });
        }
    };
    fsModule.promises.access = function (p) {
        return new Promise(function (res, rej) {
            fsModule.existsSync(p) ? res() : rej(rpcError('ENOENT', 'no such file'));
        });
    };

    // 调试用：控制台执行 __rpgFsStats() 可以看到 HTTP 次数与缓存命中次数
    window.__rpgFsStats = function () {
        var dirs = 0, files = 0;
        for (var d in dirCache) dirs++;
        for (var f in fileCache) files++;
        return {
            rpcCalls: fsStats.rpc,
            cacheHits: fsStats.hits,
            cachedDirs: dirs,
            cachedFiles: files
        };
    };
    window.__rpgFsClearCache = function () {
        dirCache = Object.create(null);
        fileCache = Object.create(null);
        return 'cleared';
    };

    // ---------------------------------------------------------------
    // nw / nw.gui —— 只保留游戏常用的几个方法
    // ---------------------------------------------------------------
    function makeNwWindow() {
        var win = {
            title: document.title || 'RPG Maker',
            width: window.innerWidth,
            height: window.innerHeight,
            x: 0,
            y: 0,
            on: function () { return win; },
            once: function () { return win; },
            removeListener: function () { return win; },
            addListener: function () { return win; },
            show: function () { return win; },
            hide: function () { return win; },
            close: function () { },
            focus: function () { },
            blur: function () { },
            minimize: function () { },
            restore: function () { },
            maximize: function () { },
            unmaximize: function () { },
            toggleFullscreen: function () { },
            enterFullscreen: function () { },
            leaveFullscreen: function () { },
            isFullscreen: false,
            moveTo: function () { },
            resizeTo: function () { },
            setAlwaysOnTop: function () { },
            showDevTools: function () { },
            closeDevTools: function () { },
            reload: function () { location.reload(); },
            reloadIgnoringCache: function () { location.reload(); },
            setMaximumSize: function () { },
            setMinimumSize: function () { },
            setResizable: function () { }
        };
        return win;
    }

    var nwWindow = null;
    var gui = {
        Window: {
            get: function () {
                if (!nwWindow) nwWindow = makeNwWindow();
                return nwWindow;
            },
            open: function () { return makeNwWindow(); }
        },
        App: {
            argv: [],
            dataPath: VROOT,
            clearCache: function () { },
            quit: function () { },
            closeAllWindows: function () { }
        },
        Clipboard: {
            get: function () { return { set: function () { }, get: function () { return ''; } }; }
        },
        Shell: {
            openExternal: function (url) { try { window.open(url, '_blank'); } catch (e) { } },
            openItem: function () { },
            showItemInFolder: function () { }
        },
        Menu: function () {
            this.append = function () { };
            this.popup = function () { };
        },
        MenuItem: function (opts) {
            this.type = (opts && opts.type) || 'normal';
            this.label = opts && opts.label;
            this.enabled = true;
        },
        Tray: function () {
            this.setTitle = function () { };
            this.setToolTip = function () { };
            this.show = function () { };
            this.hide = function () { };
            this.remove = function () { };
        }
    };

    // ---------------------------------------------------------------
    // 其他常用 Node 模块的占位实现
    // ---------------------------------------------------------------
    var osModule = {
        platform: function () { return 'android'; },
        tmpdir: function () { return '/tmp'; },
        homedir: function () { return VROOT; },
        EOL: '\n',
        cpus: function () { return []; },
        totalmem: function () { return 0; },
        freemem: function () { return 0; }
    };

    var childProcessStub = {
        exec: function (cmd, cb) { if (typeof cb === 'function') setTimeout(function () { cb(new Error('安卓版不支持启动外部程序'), '', ''); }, 0); },
        execSync: function () { throw new Error('安卓版不支持启动外部程序'); },
        spawn: function () { throw new Error('安卓版不支持启动外部程序'); },
        spawnSync: function () { return { status: 1, stdout: '', stderr: '' }; }
    };

    var utilModule = {
        inherits: function (ctor, superCtor) {
            ctor.super_ = superCtor;
            ctor.prototype = Object.create(superCtor.prototype, {
                constructor: { value: ctor, enumerable: false, writable: true, configurable: true }
            });
        },
        format: function () { return Array.prototype.join.call(arguments, ' '); },
        inspect: function (o) {
            try { return JSON.stringify(o); } catch (e) { return String(o); }
        },
        promisify: function (fn) {
            return function () {
                var args = Array.prototype.slice.call(arguments);
                var self = this;
                return new Promise(function (res, rej) {
                    args.push(function (err, value) { err ? rej(err) : res(value); });
                    fn.apply(self, args);
                });
            };
        }
    };

    /**
     * require：游戏和插件里出现的模块都从这里拿。
     * 不认识的一律返回空对象，避免直接抛异常把游戏打死。
     */
    function nodeRequire(id) {
        var name = String(id).replace(/^node:/, '');
        if (name === 'fs' || name === 'fs-extra' || name === 'graceful-fs') return fsModule;
        if (name === 'path' || name === 'path-browserify') return pathModule;
        if (name === 'nw.gui') return gui;
        if (name === 'nw') return { gui: gui, Window: gui.Window, App: gui.App, Shell: gui.Shell };
        if (name === 'child_process') return childProcessStub;
        if (name === 'os') return osModule;
        if (name === 'util') return utilModule;
        if (name === 'zlib') return {};
        if (name === 'crypto') return { randomBytes: function (n) { var b = Buffer.alloc(n); for (var i = 0; i < n; i++) b[i] = Math.floor(Math.random() * 256); return b; } };
        if (name === 'buffer') return { Buffer: Buffer };
        if (name === 'events') {
            var E = function () { this._events = {}; };
            E.prototype.on = function (n, f) { (this._events[n] = this._events[n] || []).push(f); return this; };
            E.prototype.once = E.prototype.on;
            E.prototype.emit = function (n) {
                var list = this._events[n] || [];
                for (var i = 0; i < list.length; i++) list[i].apply(this, Array.prototype.slice.call(arguments, 1));
                return list.length > 0;
            };
            E.prototype.removeListener = function () { return this; };
            return E;
        }
        console.warn('[RPG] 未实现的模块: ' + name + '（返回空对象）');
        var empty = function () { };
        empty.prototype = {};
        return empty;
    }

    // ---------------------------------------------------------------
    // 安装到 window
    // ---------------------------------------------------------------
    var processStub = {
        platform: 'win32',                 // 与桌面端一致，避免游戏走 mac/linux 分支
        arch: 'x64',
        type: 'renderer',
        pid: 1,
        title: 'node',
        argv: ['node', VROOT + '/index.html'],
        execPath: VROOT + '/nw.exe',
        env: { NODE_ENV: 'production' },
        versions: {
            node: '18.20.4',
            v8: '10.2.154.26-node.37',
            nw: '0.80.0',
            modules: '108'
        },
        version: 'v18.20.4',
        cwd: function () { return VROOT; },
        chdir: function () { },
        exit: function () { },              // 阉割：检测到异常时不许自杀
        abort: function () { },
        kill: function () { },
        on: function () { return processStub; },
        once: function () { return processStub; },
        off: function () { },
        removeListener: function () { },
        emit: function () { return false; },
        nextTick: function (fn) { setTimeout(fn, 0); },
        hrtime: function () {
            var ms = performance.now();
            return [Math.floor(ms / 1000), Math.floor((ms % 1000) * 1e6)];
        },
        mainModule: {
            id: '.',
            filename: VROOT + '/index.html',
            loaded: true,
            exports: {},
            paths: [VROOT]
        }
    };

    try {
        window.require = nodeRequire;
        window.process = processStub;
        window.Buffer = Buffer;
        window.global = window;
        window.nw = { gui: gui, Window: gui.Window, App: gui.App, Shell: gui.Shell, Menu: gui.Menu, MenuItem: gui.MenuItem };
        window.__dirname = VROOT;
        window.__filename = VROOT + '/index.html';
        window.__rpgRootTag = ROOT_TAG;
        window.__RPG_NODE_SHIM__ = 1;
        console.log('[RPG] 内嵌 Node 环境已就绪，游戏根目录: ' + ROOT_TAG + '（存档写入 save/）');
    } catch (e) {
        console.error('[RPG] 安装 Node 环境失败: ' + (e && e.message));
    }
})();
