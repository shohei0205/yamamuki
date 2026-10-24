// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation

/// 地点の `tags`(「日本百名山」などの分類ラベル)で分けた山のグループ。
/// タグ名はコードに持たず、保存している山データにあるものをそのまま一覧にする。
public struct TagGroup: Equatable, Hashable, Sendable {
    public let name: String
    /// このタグを持つ山の数。
    public let count: Int

    public init(name: String, count: Int) {
        self.name = name
        self.count = count
    }
}

public enum TagGroups {
    /// 山ごとのタグの一覧から、グループの一覧を作る。同じ名前はまとめて山の数を数え、名前順(文字コード順)に並べる。
    /// 1 つの山に同じタグが重なっていても 1 山と数える。
    /// 並びは Android(Kotlin の文字列の比較)と同じになるよう、UTF-16 の並びで比べる。
    public static func of<S: Sequence>(_ tagLists: S) -> [TagGroup] where S.Element == [String] {
        var counts: [String: Int] = [:]
        for tags in tagLists {
            for tag in Set(tags) where !tag.isEmpty {
                counts[tag, default: 0] += 1
            }
        }
        return counts.map { TagGroup(name: $0.key, count: $0.value) }
            .sorted { $0.name.utf16.lexicographicallyPrecedes($1.name.utf16) }
    }

    /// 実際に絞り込みに使うグループ。選んだもののうち、今の山データにあるものだけを名前順に返す。
    /// 山データの更新で選んだタグが無くなったときに、山が 1 つも出なくなるのを防ぐ(空なら絞り込まない)。
    /// `groups` がまだ分からない(読み込み中)ときは nil を渡し、選んだものをそのまま使う。
    public static func effective(selected: Set<String>, groups: [TagGroup]?) -> [String] {
        guard let groups else {
            return selected.sorted { $0.utf16.lexicographicallyPrecedes($1.utf16) }
        }
        return groups.map(\.name).filter { selected.contains($0) }
    }
}

extension Mountain {
    /// `groups` のどれかのタグを持つ山か。`groups` が空なら絞り込まないので、どの山も当てはまる。
    public func inTagGroups(_ groups: [String]) -> Bool {
        groups.isEmpty || tags.contains { groups.contains($0) }
    }
}
