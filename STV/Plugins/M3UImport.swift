import CryptoKit
import Foundation

/// In-app port of scripts/source-compat/m3u_to_idj.py (Android: M3UImport.kt): a reviewed, legal extended-M3U channel
/// list becomes a static, permission-scoped idj1 video bundle. Channel URLs are never fetched here, nothing from the list
/// is executed and no cookie is copied. Rejections match the script case by case (shared/legado-port/m3u-cases.json),
/// and main.js is byte-identical to the script's output, so the integrity hash is the same.
enum M3UImport {
    struct Rejected: LocalizedError {
        let code: String
        let message: String
        var errorDescription: String? { message }
    }

    struct Header: Equatable {
        let name: String
        let value: String
    }

    struct Channel: Equatable {
        let title: String
        let group: String
        let cover: String?
        let id: String
        let url: String
        let headers: [Header]
    }

    private static let messages: [String: String] = [
        "not_channel_list": "不是扩展 M3U 频道列表（第一行必须是 #EXTM3U）",
        "hls_playlist": "这是 HLS 播放清单（m3u8），不是频道列表",
        "missing_url": "有频道缺少播放地址",
        "missing_name": "#EXTINF 缺少频道名",
        "unsafe_option": "不支持或不安全的 VLC 选项：",
        "drm_directive": "包含 DRM / 认证 / 额外 HTTP 指令，需要单独审核的适配器",
        "url_before_extinf": "播放地址前缺少 #EXTINF",
        "inline_headers": "不支持地址里内联的 HTTP 头（|…），请改用 #EXTVLCOPT",
        "https_required": "只支持 HTTPS 播放地址",
        "empty_list": "频道列表为空或被截断",
        "url_chars": "地址含空白、控制字符或反斜杠",
        "url_scheme": "只支持不带账号密码的 HTTP(S) 地址",
        "url_port": "非标准端口需要单独审核的适配器",
        "url_ambiguous_host": "纯数字、末尾带点或非 ASCII 的主机名需要单独审核的适配器",
        "url_invalid_host": "主机名无效",
        "url_local_host": "不支持本地主机名",
        "url_signed": "带签名或凭证参数的地址需要单独审核的适配器，不会保存凭证",
        "bad_plugin_id": "插件 ID 必须是反向域名",
    ]
    private static func reject(_ code: String, _ detail: String = "") -> Rejected {
        Rejected(code: code, message: (messages[code] ?? code) + detail)
    }

    private static func regex(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: options)
    }
    private static let extinf = regex("^#EXTINF:(?:[^\",]|\"[^\"]*\")*,(.*)$")
    // m3u_to_idj.py's str \w: ICU's own \w also matches combining marks and connector punctuation.
    private static let attribute = regex("([\\p{L}\\p{N}_-]+)=\"([^\"]*)\"")
    private static let numericLabel = regex("^(?:0x[0-9a-f]+|[0-9]+)$", .caseInsensitive)
    private static let dnsName = regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private static let sensitiveKey = regex("^(?:token|access[_-]?token|auth|auth_key|authorization|key|api[_-]?key|password|pass|secret|sign|signature|sig|cookie|x-amz-.+|x-goog-.+)$", .caseInsensitive)
    private static let pluginID = regex("^[a-z0-9]+(?:\\.[a-z0-9-]+){2,}$")

    private static func matches(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }
    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// True when the text claims to be an extended M3U, so the caller routes it here instead of the .idjplugin parser.
    static func isChannelList(_ text: String) -> Bool {
        text.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \t\r\n")).hasPrefix("#EXTM3U")
    }

    /// Python's str.splitlines(): CRLF, LF, CR, VT, FF, FS, GS, RS, NEL, LS and PS all end a line.
    private static func splitLines(_ s: String) -> [String] {
        let breaks: Set<UInt32> = [0x0A, 0x0D, 0x0B, 0x0C, 0x1C, 0x1D, 0x1E, 0x85, 0x2028, 0x2029]
        var lines: [String] = []
        var current = String.UnicodeScalarView()
        var afterCR = false
        for u in s.unicodeScalars {
            if afterCR && u.value == 0x0A { afterCR = false; continue }
            afterCR = u.value == 0x0D
            if breaks.contains(u.value) { lines.append(String(current)); current = String.UnicodeScalarView() } else { current.append(u) }
        }
        if !current.isEmpty { lines.append(String(current)) }
        return lines
    }

    static func parse(_ text: String, base: String? = nil) throws -> [Channel] {
        var body = text
        while body.unicodeScalars.first == "\u{FEFF}" { body.unicodeScalars.removeFirst() }
        let lines = splitLines(body)
        guard let first = lines.first, trim(first) == "#EXTM3U" else { throw reject("not_channel_list") }
        if lines.contains(where: { trim($0).uppercased().hasPrefix("#EXT-X-") }) { throw reject("hls_playlist") }
        var items: [Channel] = []
        var meta: (title: String, group: String, cover: String?)? = nil
        var headers: [Header] = []
        for raw in lines.dropFirst() {
            let line = trim(raw)
            if line.isEmpty { continue }
            if line.hasPrefix("#EXTINF:") {
                if meta != nil { throw reject("missing_url") }
                let ns = line as NSString
                guard let m = extinf.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else { throw reject("missing_name") }
                let title = trim(ns.substring(with: m.range(at: 1)))
                if title.isEmpty { throw reject("missing_name") }
                let prefix = ns.substring(to: m.range(at: 1).location) as NSString
                var attrs: [String: String] = [:]
                for a in attribute.matches(in: prefix as String, range: NSRange(location: 0, length: prefix.length)) {
                    attrs[prefix.substring(with: a.range(at: 1))] = prefix.substring(with: a.range(at: 2))
                }
                let group = attrs["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? "全部"
                let cover = try attrs["tvg-logo"].flatMap { $0.isEmpty ? nil : $0 }.map { try publicURL($0, base: base).url }
                meta = (title, group, cover)
                headers = []
            } else if line.hasPrefix("#EXTGRP:"), let current = meta {
                let group = trim(String(line.dropFirst("#EXTGRP:".count)))
                meta = (current.title, group.isEmpty ? "全部" : group, current.cover)
            } else if line.hasPrefix("#EXTVLCOPT:") {
                let option = String(line.dropFirst("#EXTVLCOPT:".count))
                let parts = option.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                let key = String(parts[0])
                let value = parts.count > 1 ? String(parts[1]) : ""
                let mapped = ["http-referrer": "Referer", "http-user-agent": "User-Agent"][key.lowercased()]
                guard meta != nil, let mapped, value.unicodeScalars.count <= 500,
                      !value.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else { throw reject("unsafe_option", key) }
                if mapped == "Referer" { _ = try publicURL(value, base: nil) }
                if let i = headers.firstIndex(where: { $0.name == mapped }) { headers[i] = Header(name: mapped, value: value) }
                else { headers.append(Header(name: mapped, value: value)) }
            } else if line.hasPrefix("#KODIPROP:") || line.hasPrefix("#EXTHTTP:") {
                throw reject("drm_directive")
            } else if line.hasPrefix("#") {
                continue
            } else {
                guard let current = meta else { throw reject("url_before_extinf") }
                if line.contains("|") { throw reject("inline_headers") }
                let url = try publicURL(line, base: base).url
                if split(url).scheme != "https" { throw reject("https_required") }
                items.append(Channel(title: current.title, group: current.group, cover: current.cover, id: String(items.count + 1), url: url, headers: headers))
                meta = nil
            }
        }
        if meta != nil || items.isEmpty { throw reject("empty_list") }
        return items
    }

    private struct Parts {
        let scheme: String
        let host: String?
        let portText: String?
        let credentials: Bool
        let query: String
        let fragment: String
    }

    private static func isSchemeScalar(_ u: Unicode.Scalar) -> Bool {
        (u.value >= 0x41 && u.value <= 0x5A) || (u.value >= 0x61 && u.value <= 0x7A) || (u.value >= 0x30 && u.value <= 0x39) || u == "+" || u == "-" || u == "."
    }

    /// urllib.parse.urlsplit, reduced to what publicURL reads.
    private static func split(_ url: String) -> Parts {
        var rest = Substring(url)
        var scheme = ""
        if let colon = url.firstIndex(of: ":") {
            let candidate = url[..<colon].unicodeScalars
            if let head = candidate.first, head.isASCII, head.properties.isAlphabetic, candidate.allSatisfy(isSchemeScalar) {
                scheme = String(url[..<colon]).lowercased()
                rest = url[url.index(after: colon)...]
            }
        }
        var netloc: Substring? = nil
        if rest.hasPrefix("//") {
            let after = rest.dropFirst(2)
            let end = after.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) ?? after.endIndex
            netloc = after[..<end]
            rest = after[end...]
        }
        let hash = rest.firstIndex(of: "#")
        let fragment = hash.map { String(rest[rest.index(after: $0)...]) } ?? ""
        let beforeHash = hash.map { rest[..<$0] } ?? rest
        let query = beforeHash.firstIndex(of: "?").map { String(beforeHash[beforeHash.index(after: $0)...]) } ?? ""
        guard let netloc else { return Parts(scheme: scheme, host: nil, portText: nil, credentials: false, query: query, fragment: fragment) }
        var credentials = false
        let at = netloc.lastIndex(of: "@")
        if let at {
            let user = netloc[..<at].split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            credentials = !(user.first ?? "").isEmpty || (user.count > 1 && !user[1].isEmpty)
        }
        let hostinfo = at.map { netloc[netloc.index(after: $0)...] } ?? netloc
        var host = hostinfo
        var port: Substring = ""
        if let open = hostinfo.firstIndex(of: "[") {
            let bracketed = hostinfo[hostinfo.index(after: open)...]
            if let close = bracketed.firstIndex(of: "]") {
                host = bracketed[..<close]
                let tail = bracketed[bracketed.index(after: close)...]
                port = tail.firstIndex(of: ":").map { tail[tail.index(after: $0)...] } ?? ""
            } else {
                host = bracketed
            }
        } else if let colon = hostinfo.firstIndex(of: ":") {
            host = hostinfo[..<colon]
            port = hostinfo[hostinfo.index(after: colon)...]
        }
        return Parts(scheme: scheme, host: host.isEmpty ? nil : host.lowercased(), portText: port.isEmpty ? nil : String(port),
                     credentials: credentials, query: query, fragment: fragment)
    }

    private static func join(_ base: String?, _ raw: String) -> String {
        guard let base, !base.isEmpty, split(raw).scheme.isEmpty, var b = URL(string: base) else { return raw }
        if b.host != nil, b.path.isEmpty, let slash = URL(string: base + "/") { b = slash }
        return URL(string: raw, relativeTo: b)?.absoluteURL.standardized.absoluteString ?? raw
    }

    private static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s
    }

    /// The script's `public_url`: the joined URL and its lowercased host, or Rejected with the script's reason.
    static func publicURL(_ raw: String, base: String?) throws -> (url: String, host: String) {
        let url = join(base, trim(raw))
        if url.unicodeScalars.contains(where: { $0.value <= 0x20 || $0.value == 0x7F || $0 == "\\" }) { throw reject("url_chars") }
        let u = split(url)
        guard u.scheme == "https" || u.scheme == "http", let host = u.host, !u.credentials else { throw reject("url_scheme") }
        if let text = u.portText {
            guard text.unicodeScalars.allSatisfy({ $0.value >= 0x30 && $0.value <= 0x39 }), let port = Int(text), (0...65535).contains(port) else { throw reject("url_port") }
            if port != 0 && port != (u.scheme == "https" ? 443 : 80) { throw reject("url_port") }
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        if host.unicodeScalars.contains(where: { $0.value > 127 }) || host.hasSuffix(".") || labels.allSatisfy({ matches(numericLabel, $0) }) {
            throw reject("url_ambiguous_host")
        }
        if !matches(dnsName, host) { throw reject("url_invalid_host") }
        if host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".localhost") || host.hasSuffix(".internal") { throw reject("url_local_host") }
        let pairs = (u.query.components(separatedBy: "&") + u.fragment.components(separatedBy: "&")).filter { !$0.isEmpty }
        let keys = pairs.map { decode(String($0.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)[0])) }
        if keys.contains(where: { matches(sensitiveKey, $0) }) { throw reject("url_signed") }
        return (url, host)
    }

    /// The .idjplugin bytes for `channels`; `author` defaults to the script's label so the parity cases stay exact.
    static func bundle(_ channels: [Channel], name: String, id: String, author: String = "iDuanJu compatibility test") throws -> Data {
        guard matches(pluginID, id) else { throw reject("bad_plugin_id") }
        var hosts = Set<String>()
        for c in channels {
            for u in [c.url, c.cover].compactMap({ $0 }) { hosts.insert(try publicURL(u, base: nil).host) }
        }
        let domains = hosts.sorted()
        let source = mainJS(channels)
        let hash = "sha256-" + Data(SHA256.hash(data: Data(source.utf8))).base64EncodedString()
        let manifest: [String: Any] = [
            "schema": 1, "id": id, "name": name, "version": "1.0.0", "author": ["name": author], "type": "video", "adult": false,
            "minAppVersion": "1.5", "entry": "main.js", "permissions": ["http": domains, "media": domains],
            "capabilities": ["home", "category", "search", "detail", "play"], "integrity": ["files": ["main.js": hash]],
        ]
        return try JSONSerialization.data(withJSONObject: ["manifest": manifest, "files": ["main.js": source]])
    }

    /// `json.dumps(items, ensure_ascii=False)` followed by the script's fixed module text.
    static func mainJS(_ channels: [Channel]) -> String {
        var s = "const channels = ["
        for (i, c) in channels.enumerated() {
            if i > 0 { s += ", " }
            s += "{\"title\": " + quote(c.title) + ", \"group\": " + quote(c.group)
            if let cover = c.cover { s += ", \"cover\": " + quote(cover) }
            s += ", \"id\": " + quote(c.id) + ", \"url\": " + quote(c.url) + ", \"headers\": {"
            s += c.headers.map { quote($0.name) + ": " + quote($0.value) }.joined(separator: ", ")
            s += "}}"
        }
        return s + "];\n" + module
    }

    private static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0..<0x20: out += String(format: "\\u%04x", u.value)
            default: out.unicodeScalars.append(u)
            }
        }
        return out + "\""
    }

    private static let module = [
        "",
        "function find(id) { const c=channels.find(x => x.id===String(id)); if (!c) throw host.error('E_NOT_FOUND','频道不存在'); return c; }",
        "function page(items, n) { const size=50, p=Math.max(1,Number(n)||1); return {items:items.slice((p-1)*size,p*size),page:p,hasMore:p*size<items.length}; }",
        "export default {",
        " home() { return {categories:Array.from(new Set(channels.map(x=>x.group))).map(x=>({id:x,name:x})),items:channels.slice(0,50)}; },",
        " category({id,page:p}) { return page(channels.filter(x=>!id||x.group===id),p); },",
        " search({keyword,page:p}) { return page(channels.filter(x=>x.title.toLowerCase().includes(String(keyword||'').toLowerCase())),p); },",
        " detail({id}) { const c=find(id); return {title:c.title,cover:c.cover,desc:'M3U 静态导入；原始内容归其权利人所有。',lines:[{name:c.group,episodes:[{id:c.id,name:c.title}]}]}; },",
        " play({episodeId}) { const c=find(episodeId); return {streams:[{url:c.url,headers:c.headers,format:/\\.m3u8(?:[?#]|$)/i.test(c.url)?'hls':'mp4'}]}; }",
        "};",
        "",
    ].joined(separator: "\n")

    /// Stable plugin id for an imported list: re-importing the same link (or the same file content) updates it in place.
    static func importID(_ seed: String) -> String {
        "com.idj.m3u." + String(SHA256.hash(data: Data(seed.utf8)).map { String(format: "%02x", $0) }.joined().prefix(12))
    }

    /// Display name from a list URL's last path segment, without its extension.
    static func name(fromURL url: String) -> String? {
        guard let path = URLComponents(string: url)?.percentEncodedPath else { return nil }
        var last = path
        while last.hasSuffix("/") { last.removeLast() }
        last = String(last.split(separator: "/", omittingEmptySubsequences: false).last ?? "")
        if let dot = last.lastIndex(of: ".") { last = String(last[..<dot]) }
        let name = trim(decode(last))
        return name.isEmpty ? nil : name
    }
}
