// Shared Legado host API, synchronized from LegadoCore.swift.

    (function (h) {
            const call = (n, a) => { const r = h(n, JSON.stringify(a === undefined ? null : a)); return r === undefined ? undefined : JSON.parse(r); };
            const str = (v) => v == null ? '' : String(v);
            globalThis.java = {
                ajax: (u) => call('ajax', { url: str(Array.isArray(u) ? u[0] : u) }),
                connect: (u, h2) => { const r = call('connect', { url: str(u), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (k) => (r.headers || {})[String(k).toLowerCase()], headers: () => r.headers, raw: r }; },
                get: (k, h2) => { if (h2 === undefined && !/^https?:/.test(str(k))) return call('getVar', { k: str(k) }); const r = call('connect', { url: str(k), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (n) => (r.headers || {})[String(n).toLowerCase()] }; },
                post: (u, b, h2) => { const r = call('connect', { url: str(u), method: 'POST', body: str(b), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (n) => (r.headers || {})[String(n).toLowerCase()] }; },
                put: (k, v) => { call('putVar', { k: str(k), v: str(v) }); return v; },
                getString: (rule, content) => call('getString', { rule: str(rule), content: content === undefined ? null : str(content) }),
                getStringList: (rule, content) => call('getStringList', { rule: str(rule), content: content === undefined ? null : str(content) }),
                setContent: (c, b) => call('setContent', { content: str(c), base: b === undefined ? null : str(b) }),
                base64Decode: (s, cs) => call('b64d', { s: str(s), cs: cs || null }),
                base64Encode: (s) => call('b64e', { s: str(s) }),
                md5Encode: (s) => call('md5', { s: str(s) }),
                md5Encode16: (s) => call('md5', { s: str(s) }).substring(8, 24),
                encodeURI: (s, cs) => call('encodeURI', { s: str(s), cs: cs || null }),
                timeFormat: (t) => { const d = new Date(Number(t)); const p = (n) => String(n).padStart(2, '0'); return d.getFullYear() + '/' + p(d.getMonth() + 1) + '/' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); },
                htmlFormat: (s) => call('htmlFormat', { s: str(s) }),
                log: (s) => { call('log', { s: str(s) }); return s; },
                toast: () => {}, longToast: () => {},
                getCookie: (u, k) => call('cookie', { url: str(u), k: k == null ? null : str(k) }),
            };
            globalThis.cookie = { getCookie: (u) => java.getCookie(u), getKey: (u, k) => java.getCookie(u, k), removeCookie: () => {} };
            // ---- Rhino / Android compatibility (only what book sources actually use) ----
            const bytesOf = (v) => Array.isArray(v) ? v : call('bytes.enc', { s: str(v), cs: 'UTF-8' });
            function JString(a, b, c, d) {
                let s;
                if (a == null || typeof a !== 'object' || !Array.isArray(a)) s = str(a);
                else if (typeof b === 'number') s = call('bytes.dec', { b: a.slice(b, b + c), cs: d || 'UTF-8' });
                else s = call('bytes.dec', { b: a, cs: b || 'UTF-8' });
                return { toString: () => s, valueOf: () => s, getBytes: (cs) => call('bytes.enc', { s, cs: cs || 'UTF-8' }), length: () => s.length };
            }
            const Arrays = {
                copyOf: (a, n) => { const r = a.slice(0, n); while (r.length < n) r.push(0); return r; },
                copyOfRange: (a, from, to) => { const r = a.slice(from, to); while (r.length < to - from) r.push(0); return r; },
                toString: (a) => '[' + a.join(', ') + ']',
            };
            const Base64 = {
                getDecoder: () => ({ decode: (v) => call('b64.dec', { s: Array.isArray(v) ? call('bytes.dec', { b: v, cs: 'UTF-8' }) : str(v) }) }),
                getMimeDecoder: () => Base64.getDecoder(),
                getUrlDecoder: () => Base64.getDecoder(),
                getEncoder: () => ({ encodeToString: (b) => call('b64.enc', { b: bytesOf(b) }), encode: (b) => call('bytes.enc', { s: call('b64.enc', { b: bytesOf(b) }), cs: 'UTF-8' }) }),
            };
            function Inflater(nowrap) {
                let input = [];
                return {
                    setInput: (b, off, len) => { off = off || 0; input = b.slice(off, len == null ? b.length : off + len); },
                    inflate: (out, off, len) => { const r = call('inflate', { b: input, nowrap: !!nowrap }); off = off || 0; const n = Math.min(r.length, len == null ? out.length - off : len); for (let i = 0; i < n; i++) out[off + i] = r[i]; return n; },
                    end: () => {}, finished: () => true,
                };
            }
            const MessageDigest = { getInstance: (alg) => { let acc = []; return {
                        update: (b) => { acc = acc.concat(bytesOf(b)); },
                        digest: (b) => { const all = b === undefined ? acc : acc.concat(bytesOf(b)); acc = []; return call('digest', { alg: str(alg), b: all }); },
                    }; } };
            const Cipher = { getInstance: (t) => { let mode = 2, key = null, iv = null; return {
                        init: (m, k, p) => { mode = m; key = k; iv = p || null; },
                        doFinal: (b) => call('cipher', { t: str(t), mode, key: key.key, iv: iv ? iv.iv : null, b: bytesOf(b) }),
                    }; }, ENCRYPT_MODE: 1, DECRYPT_MODE: 2 };
            function SecretKeySpec(k, alg) { return { key: bytesOf(k), alg: str(alg) }; }
            function IvParameterSpec(v) { return { iv: bytesOf(v) }; }
            // Rhino scope helpers: `this.getHeaderMap()` (the request headers) and login state (not supported → empty).
            globalThis.getHeaderMap = () => call('headers', null);
globalThis.getSource = () => globalThis.source;
  // Rhino's JavaImporter + `with (...)`: Java packages are not available here; an empty scope keeps scripts running.
  globalThis.JavaImporter = function () { return new Object(); };
              
  // ---- login, browser and cache (Legado `source` / `cache` objects) ----
  const parseObj = (t) => { try { return JSON.parse(t) || new Object(); } catch (e) { return new Object(); } };
  globalThis.__wrapSource = (src) => {
src.getKey = () => src.bookSourceUrl;
    src.key = src.bookSourceUrl;
    src.getVariable = () => java.get('__sourceVariable');
    src.setVariable = (v) => java.put('__sourceVariable', str(v));
    src.get = (k) => java.get(k);
    src.put = (k, v) => java.put(k, v);
    src.getLoginInfo = () => call('loginInfo', null);
src.getLoginInfoMap = () => __jmap(parseObj(call('loginInfo', null)));
    src.putLoginInfo = (v) => { call('putLoginInfo', { v: str(v) }); return true; };
    src.removeLoginInfo = () => call('putLoginInfo', { v: '' });
    src.getLoginHeader = () => call('loginHeader', null);
src.getLoginHeaderMap = () => __jmap(parseObj(call('loginHeader', null)));
    src.putLoginHeader = (v) => call('putLoginHeader', { v: str(v) });
    src.removeLoginHeader = () => call('putLoginHeader', { v: '' });
    src.getLoginJs = () => '';
    return src;
  };
  globalThis.java.refreshExplore = () => true;
  globalThis.cache = {
    get: (k) => call('cacheGet', { k: str(k) }),
    put: (k, v, sec) => { call('cachePut', { k: str(k), v: v == null ? null : str(v), sec: Number(sec || 0) }); return v; },
    delete: (k) => call('cachePut', { k: str(k), v: null, sec: 0 }),
    getFromMemory: (k) => call('cacheGet', { k: 'mem.' + str(k) }),
    putMemory: (k, v) => call('cachePut', { k: 'mem.' + str(k), v: str(v), sec: 0 }),
    getFile: (k) => call('cacheGet', { k: 'file.' + str(k) }),
    putFile: (k, v, sec) => call('cachePut', { k: 'file.' + str(k), v: str(v), sec: Number(sec || 0) }),
  };
  Object.assign(globalThis.java, {
    webView: (html, url, js) => call('webView', { html: html == null ? null : str(html), url: url == null ? null : str(url), js: js == null ? null : str(js) }),
    webViewGetSource: (html, url, js) => call('webView', { html: html == null ? null : str(html), url: url == null ? null : str(url), js: js == null ? null : str(js) }),
    startBrowser: (url, title) => { call('browser', { url: str(url), title: title == null ? '' : str(title), wait: false }); return ''; },
    startBrowserAwait: (url, title) => { const b = call('browser', { url: str(url), title: title == null ? '' : str(title), wait: true }); return { body: () => b, code: () => 200, url: () => str(url) }; },
    showBrowser: (url, title) => { call('browser', { url: str(url), title: title == null ? '' : str(title), wait: false }); return ''; },
    getCookieStr: (u) => java.getCookie(u),
  });

  // ---- Jsoup / Java collections on top of host-side libxml nodes (handles) ----
  const jsoupEl = (h) => ({
    __h: h,
    select: (q) => jsoupEls(call('jsoup.select', { h: [h], q: str(q) })),
    selectFirst: (q) => { const r = call('jsoup.select', { h: [h], q: str(q), first: true }); return r.length ? jsoupEl(r[0]) : null; },
    getElementsByClass: (c) => jsoupEls(call('jsoup.select', { h: [h], q: '.' + str(c) })),
    getElementsByTag: (t) => jsoupEls(call('jsoup.select', { h: [h], q: str(t) })),
    getElementById: (i) => { const r = call('jsoup.select', { h: [h], q: '#' + str(i), first: true }); return r.length ? jsoupEl(r[0]) : null; },
    children: () => jsoupEls(call('jsoup.children', { h: [h] })),
    text: () => call('jsoup.text', { h: [h] }),
    ownText: () => call('jsoup.ownText', { h: [h] }),
    textNodes: () => call('jsoup.textNodes', { h: [h] }).map((t) => ({ text: () => t, toString: () => t })),
    attr: (k) => call('jsoup.attr', { h: [h], k: str(k) }),
    absUrl: (k) => call('jsoup.attr', { h: [h], k: 'abs:' + str(k) }),
    hasClass: (c) => (' ' + call('jsoup.attr', { h: [h], k: 'class' }) + ' ').indexOf(' ' + str(c) + ' ') >= 0,
    html: () => call('jsoup.html', { h: [h] }),
    outerHtml: () => call('jsoup.outer', { h: [h] }),
    toString: () => call('jsoup.outer', { h: [h] }),
  });
  const jsoupEls = (hs) => {
    const a = hs.map(jsoupEl);
    const def = (k, f) => Object.defineProperty(a, k, { value: f, enumerable: false });
    def('select', (q) => jsoupEls(call('jsoup.select', { h: hs, q: str(q) })));
    def('text', () => call('jsoup.text', { h: hs }));
    def('eachText', () => hs.map((x) => call('jsoup.text', { h: [x] })));
    def('attr', (k) => call('jsoup.attr', { h: hs, k: str(k) }));
    def('eachAttr', (k) => hs.map((x) => call('jsoup.attr', { h: [x], k: str(k) })));
    def('html', () => call('jsoup.html', { h: hs }));
    def('outerHtml', () => call('jsoup.outer', { h: hs }));
    def('first', () => (a.length ? a[0] : null));
    def('last', () => (a.length ? a[a.length - 1] : null));
    def('eq', (i) => jsoupEls(hs[i] === undefined ? [] : [hs[i]]));
    def('toString', () => call('jsoup.outer', { h: hs }));
    return a;
  };
  globalThis.org = { jsoup: { Jsoup: { parse: (html) => jsoupEl(call('jsoup.parse', { html: str(html) })) } } };
  Object.assign(globalThis.java, {
    getElement: (rule) => jsoupEls(call('jsoup.rule', { rule: str(rule) })),
    getElements: (rule) => jsoupEls(call('jsoup.rule', { rule: str(rule) })),
  });
  // Java List / Collection methods on arrays (rules hand `result` to scripts as lists).
  const listMethods = {
    toArray: function () { return this; },
    size: function () { return this.length; },
    get: function (i) { return this[i]; },
    isEmpty: function () { return this.length === 0; },
    add: function (x) { this.push(x); return true; },
    contains: function (x) { return this.indexOf(x) >= 0; },
  };
  for (const k of Object.keys(listMethods)) {
    if (!(k in Array.prototype)) Object.defineProperty(Array.prototype, k, { value: listMethods[k], enumerable: false, writable: true, configurable: true });
  }

  // ---- more Legado / Java bits seen in real sources ----
  Object.assign(globalThis.java, {
    t2s: (s) => call('convert', { s: str(s), to: 'Hant-Hans' }),
    s2t: (s) => call('convert', { s: str(s), to: 'Hans-Hant' }),
    randomUUID: () => { const u = call('uuid', null); return { toString: () => u, valueOf: () => u }; },
    timeFormatUTC: (t, fmt, off) => java.timeFormat(t),
    // RSA (hutool AsymmetricCrypto): public-key "decrypt" = verify-style recovery, used to unpack chapter text.
    createAsymmetricCrypto: (alg) => {
      let pub = null, priv = null;
      const toBytes = (d) => Array.isArray(d) ? d : call('b64.dec', { s: str(d) });
      const o = {
        setPublicKey: (k) => { pub = toBytes(k); return o; },
        setPrivateKey: (k) => { priv = toBytes(k); return o; },
 decrypt: (d) => call('rsa.dec', { key: pub, priv, b: toBytes(d) }),
 decryptStr: (d) => call('bytes.dec', { b: call('rsa.dec', { key: pub, priv, b: toBytes(d) }), cs: 'UTF-8' }),
 encrypt: (d) => call('rsa.enc', { key: pub, b: Array.isArray(d) ? d : call('bytes.enc', { s: str(d), cs: 'UTF-8' }) }),
        encryptBase64: (d) => call('b64.enc', { b: o.encrypt(d) }),
      };
      return o;
    },
    getTxtInFolder: () => '', importScript: () => '', cacheFile: () => '',
  });
  globalThis.cookie.removeCookie = (u) => call('cookieRemove', { url: str(u) });
  globalThis.cookie.setCookie = (u, c) => call('cookieSet', { url: str(u), c: str(c) });
  // Java Map methods on the maps sources get from us (login header / info): map.get("k"), containsKey …
  globalThis.__jmap = (o) => {
    const m = o || new Object();
    const def = (k, f) => Object.defineProperty(m, k, { value: f, enumerable: false, writable: true, configurable: true });
    def('get', (k) => (m[k] === undefined ? null : m[k]));
    def('put', (k, v) => { m[k] = v; return v; });
    def('containsKey', (k) => Object.prototype.hasOwnProperty.call(m, k));
    def('keySet', () => Object.keys(m));
    def('isEmpty', () => Object.keys(m).length === 0);
    return m;
  };
  globalThis.Packages = {
                    java: { lang: { String: JString, Thread: { sleep: () => {} } }, nio: { charset: { Charset: { forName: (name) => str(name) }, StandardCharsets: { UTF_8: 'UTF-8' } } }, util: { Arrays, Base64, UUID: { randomUUID: () => java.randomUUID() }, zip: { Inflater } }, security: { MessageDigest } },
                    javax: { crypto: { Cipher, spec: { SecretKeySpec, IvParameterSpec } } },
                    cn: { hutool: { crypto: { digest: { DigestUtil: { md5Hex: (s) => java.md5Encode(s) } } } } },
                };
                const sym = (t, key, iv) => {
                    const k = bytesOf(key), v = iv == null ? null : bytesOf(iv), tt = str(t);
                    const run = (mode, data) => call('cipher', { t: tt, mode, key: k, iv: v, b: data });
                    const input = (d) => Array.isArray(d) ? d : (/^[0-9a-fA-F]+$/.test(str(d)) && str(d).length % 2 === 0 ? call('hex.dec', { s: str(d) }) : call('b64.dec', { s: str(d) }));
                    return {
                        decrypt: (d) => run(2, input(d)),
                        decryptStr: (d) => call('bytes.dec', { b: run(2, input(d)), cs: 'UTF-8' }),
                        encrypt: (d) => run(1, bytesOf(d)),
                        encryptBase64: (d) => call('b64.enc', { b: run(1, bytesOf(d)) }),
                        encryptHex: (d) => call('hex.enc', { b: run(1, bytesOf(d)) }),
                    };
                };
                Object.assign(globalThis.java, {
                        createSymmetricCrypto: sym,
                        aesBase64DecodeToString: (d, k, t, iv) => sym(t, k, iv).decryptStr(d),
                        aesDecodeToString: (d, k, t, iv) => sym(t, k, iv).decryptStr(d),
                        aesEncodeToBase64String: (d, k, t, iv) => sym(t, k, iv).encryptBase64(d),
                        hexDecodeToString: (s) => call('bytes.dec', { b: call('hex.dec', { s: str(s) }), cs: 'UTF-8' }),
                        hexEncodeToString: (s) => call('hex.enc', { b: bytesOf(s) }),
                        base64DecodeToByteArray: (s) => call('b64.dec', { s: str(s) }),
                        strToBytes: (s, cs) => call('bytes.enc', { s: str(s), cs: cs || 'UTF-8' }),
                        bytesToStr: (b, cs) => call('bytes.dec', { b, cs: cs || 'UTF-8' }),
                        getWebViewUA: () => call('ua', {}),
                        androidId: () => call('androidId', {}),
                        ajaxAll: (urls) => (urls || []).map((u) => { try { const r = call('connect', { url: str(u) }); return { body: () => r.body, code: () => r.status, url: () => r.url }; } catch (e) { return { body: () => '', code: () => 500, url: () => str(u) }; } }),
                        getVerificationCode: () => { throw new Error('需要输入验证码（暂不支持）'); },
                        openUrl: () => {}, open: () => {}, getThemeConfig: () => ({}),
                });

                Object.assign(globalThis.java, Packages.java);
                globalThis.javax = Packages.javax;
                globalThis.__legado_call = call;
        })(__host);
