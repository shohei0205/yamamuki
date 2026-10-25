// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import XCTest
@testable import YamamukiCore

final class TagGroupsTests: XCTestCase {
    private func peak(_ id: Int64, _ tags: String...) -> Mountain {
        Mountain(id: "osm:\(id)", name: "山\(id)", latitude: 35.0, longitude: 138.0, elevationM: 1000.0, tags: tags)
    }

    func testGroupsAreCountedAndSortedByName() {
        let groups = TagGroups.of([
            ["日本百名山"],
            ["日本百名山", "九州百名山"],
            ["九州百名山", "九州百名山"],
            [],
            ["北海道百名山"],
        ])
        // 文字コード順。九(4E5D) < 北(5317) < 日(65E5)。
        XCTAssertEqual(groups, [
            TagGroup(name: "九州百名山", count: 2),
            TagGroup(name: "北海道百名山", count: 1),
            TagGroup(name: "日本百名山", count: 2),
        ])
    }

    func testNoTagsMeansNoGroups() {
        XCTAssertEqual(TagGroups.of([[], [""]]), [])
    }

    func testEffectiveDropsGroupsMissingFromData() {
        let groups = [TagGroup(name: "九州百名山", count: 100), TagGroup(name: "日本百名山", count: 100)]
        XCTAssertEqual(TagGroups.effective(selected: ["日本百名山", "消えたグループ"], groups: groups), ["日本百名山"])
        XCTAssertEqual(TagGroups.effective(selected: ["消えたグループ"], groups: groups), [])
        XCTAssertEqual(TagGroups.effective(selected: [], groups: groups), [])
    }

    func testEffectiveKeepsSelectionWhileGroupsAreUnknown() {
        XCTAssertEqual(TagGroups.effective(selected: ["日本百名山", "九州百名山"], groups: nil), ["九州百名山", "日本百名山"])
    }

    func testPeaksInAnySelectedGroupMatch() {
        let selected = ["九州百名山", "日本百名山"]
        XCTAssertTrue(peak(1, "日本百名山").inTagGroups(selected))
        XCTAssertTrue(peak(2, "九州百名山", "その他").inTagGroups(selected))
        XCTAssertFalse(peak(3, "北海道百名山").inTagGroups(selected))
        XCTAssertFalse(peak(4).inTagGroups(selected))
    }

    func testEmptySelectionShowsEveryPeak() {
        XCTAssertTrue(peak(1).inTagGroups([]))
        XCTAssertTrue(peak(2, "日本百名山").inTagGroups([]))
    }
}
