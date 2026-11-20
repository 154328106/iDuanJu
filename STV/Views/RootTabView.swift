import SwiftUI

/// 四个根页面 + PlutoFish 式底部悬浮玻璃胶囊导航（系统 TabBar 隐藏）。
/// 胶囊只在根页面显示；进入详情 / 播放器等二级页面时隐藏。
struct RootTabView: View {
    @State private var tab = Int(ProcessInfo.processInfo.environment["STV_TAB"] ?? "") ?? 0
    @State private var paths = [NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath()]
    static let items: [(String, String)] = [("首页", "house.fill"), ("TVBox", "tv.fill"),
                                            ("找剧", "square.grid.2x2.fill"), ("我的", "person.crop.circle.fill")]
    var body: some View {
        TabView(selection: $tab) {
            Stack(path: $paths[0]) { HomeView() }.tag(0)
            Stack(path: $paths[1]) { TVBoxHubView() }.tag(1)
            Stack(path: $paths[2]) { CategoryView() }.tag(2)
            Stack(path: $paths[3]) { ProfileView() }.tag(3)
            Stack(path: $paths[4]) { SearchView() }.tag(4)
        }
        .tint(STVTheme.accent)
        .overlay(alignment: .bottom) {
            if paths[tab].isEmpty {
                GlassTabBar(selection: $tab, items: Self.items, searchTag: 4) { i in
                    if i == tab { paths[i] = NavigationPath() }
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.86), value: paths[tab].isEmpty)
        .onAppear {
            Router.handler = { r in paths[tab].append(r) }
            #if DEBUG
            if ProcessInfo.processInfo.environment["STV_TD_OPEN"] == "1" { Router.push(.tdlib) }
            #endif
        }
        // Cold start: restore a saved TDLib login (no network login, nothing is sent).
        .task { await TDLibService.shared.bootstrap() }
    }
}

private struct Stack<V: View>: View {
    @Binding var path: NavigationPath
    @ViewBuilder var content: V
    @Namespace private var ns
    var body: some View {
        NavigationStack(path: $path) {
            content
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: GlassTabBar.clearance) }
                .routes()
        }.environment(\.zoomNS, ns)
    }
}

struct GlassTabBar: View {
    static let clearance: CGFloat = 72
    @Binding var selection: Int
    let items: [(String, String)]
    var searchTag: Int? = nil
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                ForEach(items.indices, id: \.self) { i in
                    let on = i == selection
                    Button {
                        if on { onReselect(i) } else { withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) { selection = i } }
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: items[i].1).font(.system(size: 17, weight: on ? .semibold : .regular)).frame(height: 20)
                            Text(items[i].0).font(.system(size: 11, weight: on ? .semibold : .medium))
                        }
                        .foregroundStyle(on ? STVTheme.text : STVTheme.secondary)
                        .frame(maxWidth: .infinity).frame(height: 48)
                        .background {
                            if on { Capsule().fill(STVTheme.glassBorder).matchedGeometryEffect(id: "pill", in: ns) }
                        }
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(items[i].0)
                    .accessibilityIdentifier("tab.\(i)")
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(6)
            .dsBar(30)

            if let searchTag {
                let searchOn = selection == searchTag
                Button {
                    if searchOn { onReselect(searchTag) } else { withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) { selection = searchTag } }
                } label: {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 19, weight: searchOn ? .bold : .semibold))
                        .foregroundStyle(searchOn ? STVTheme.accent : STVTheme.text)
                        .frame(width: 60, height: 60)
                        .dsBar(30)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("搜索")
                .accessibilityIdentifier("tab.search")
                .accessibilityAddTraits(searchOn ? .isSelected : [])
            }
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 16)
        .padding(.bottom, -8)
    }
}
