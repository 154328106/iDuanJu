import Foundation

/// Imports HTTP(S) CMS catalogs and standalone HTTP(S) live lists from TVBox.
enum TVBoxImport {
    struct Site: Hashable {
        var name: String
        var api: String
        var adult: Bool
    }
    struct Live: Hashable { var name: String; var url: String }
    struct Config {
        var name: String
        var sites: [Site]
        var lives: [Live]
        var skipped: Int
    }

    static func parse(_ data: Data) throws -> Config {
        guard data.count <= 4 << 20 else { throw PluginError.install("TVBox 配置超过 4 MB") }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw PluginError.install("TVBox 配置不是 JSON 对象")
        }
        guard root["sites"] != nil || root["spider"] != nil || root["lives"] != nil else {
            throw PluginError.install("不是 TVBox 配置（缺少 sites / spider / lives）")
        }
        let entries = root["sites"] as? [Any] ?? []
        let lists = root["lives"] as? [[String: Any]] ?? []
        guard entries.count <= 5000, lists.count <= 5000 else {
            throw PluginError.install("TVBox 配置需要不超过 5000 个站点的 sites 数组")
        }

        var sites: [Site] = []
        var seen: [String: Int] = [:]
        var skipped = 0
        for rawItem in entries {
            guard let item = rawItem as? [String: Any],
                  let type = (item["type"] as? Int) ?? (item["type"] as? String).flatMap(Int.init), [0, 1, 4].contains(type),
                  type != 4 || item["ext"] == nil || (item["ext"] as? String) == "",
                  let raw = item["api"] as? String, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let api = try? CMSStore.normalize(raw, allowHTTP: true) else {
                skipped += 1
                continue
            }
            // HTTP and HTTPS forms of the same TVBox API are one site. Prefer HTTPS when
            // both appear, while retaining HTTP when it is the only working form.
            let identity = api.hasPrefix("http://") ? "https://" + api.dropFirst(7) : api
            if let existing = seen[identity] {
                if api.hasPrefix("https://") { sites[existing] = Site(name: sites[existing].name, api: api, adult: sites[existing].adult) }
                skipped += 1
                continue
            }
            let host = URL(string: api)?.host ?? "TVBox 站点 \(sites.count + 1)"
            let name = (item["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            seen[identity] = sites.count
            sites.append(Site(name: String((name?.isEmpty == false ? name! : host).prefix(80)), api: api,
                              adult: item["adult"] as? Bool ?? false))
        }
        var liveURLs = Set<String>()
        let lives = lists.compactMap { row -> Live? in
            guard let raw = row["url"] as? String, let url = URLComponents(string: raw),
                  url.scheme == "https" || url.scheme == "http",
                  let host = url.host, !host.isEmpty, !NetGuard.isIPLiteral(host),
                  url.user == nil, url.password == nil,
                  url.port == nil || url.port == (url.scheme == "https" ? 443 : 80),
                  liveURLs.insert(raw).inserted else { return nil }
            return Live(name: String(((row["name"] as? String) ?? host).prefix(80)), url: raw)
        }
        guard !sites.isEmpty || !lives.isEmpty else {
            throw PluginError.install("没有可导入的 CMS API（type=0 / type=1 / 兼容的 type=4） 站点；JAR / CatVod / Python spider 不会执行")
        }
        let name = (root["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return Config(name: name.flatMap { $0.isEmpty ? nil : $0 } ?? "TVBox 配置", sites: sites, lives: lives, skipped: skipped)
    }
}
