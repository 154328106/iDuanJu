import SwiftUI
import UIKit

enum SizeTier: Equatable {
    case compact, medium, expanded
}

/// Unified responsive layout metrics shared across iPhone, iPad, Split View, and Stage Manager.
/// Breakpoints match Android `LayoutMetrics` (Compact < 600, Medium 600..<840, Expanded >= 840).
struct LayoutMetrics: Equatable {
    var width: CGFloat = 390
    var height: CGFloat = 844

    var tier: SizeTier {
        if width >= 840 { return .expanded }
        if width >= 600 { return .medium }
        return .compact
    }

    var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    var isLandscape: Bool { width > height }
    var useRail: Bool { width >= 840 }

    /// Portrait poster grid columns (home, category, manga, TVBox, library, novel, search).
    var posterCols: Int {
        if width >= 1100 { return 6 }
        if width >= 840 { return 5 }
        if width >= 600 { return 4 }
        return 3
    }

    /// Landscape / wide card grid columns.
    var wideCardCols: Int {
        if width >= 840 { return 4 }
        if width >= 600 { return 3 }
        return 2
    }

    /// Episode selector grid columns inside detail and player sheets.
    var episodeCols: Int {
        if width >= 840 { return 10 }
        if width >= 600 { return 8 }
        return 6
    }

    var detailMaxWidth: CGFloat? { tier == .compact ? nil : 900 }
    var readerMaxWidth: CGFloat? { tier == .compact ? nil : 720 }

    func posterGridItems(spacing: CGFloat = 10, alignment: Alignment = .top) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: alignment), count: posterCols)
    }

    func wideGridItems(spacing: CGFloat = 12, alignment: Alignment = .top) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: alignment), count: wideCardCols)
    }

    func episodeGridItems(spacing: CGFloat = 8) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing), count: episodeCols)
    }
}

private struct LayoutMetricsKey: EnvironmentKey {
    static let defaultValue = LayoutMetrics()
}

extension EnvironmentValues {
    var layoutMetrics: LayoutMetrics {
        get { self[LayoutMetricsKey.self] }
        set { self[LayoutMetricsKey.self] = newValue }
    }
}
