import XCTest
@testable import YamamukiCore

final class OverpassParserTests: XCTestCase {
    func testParsesNamedNodes() throws {
        let body = [
            "@id\t@lat\t@lon\tname\tname:ja\tele",
            "1\t35.3606\t138.7274\t富士山\t\t3776",
            "2\t35.0\t138.0\tMt. X\tエックス山\t1,234 m",
            "3\t35.1\t138.1\t\t\t",
            "4\t35.2\t138.2\t無標高山\t\t",
            "1\t35.3606\t138.7274\t富士山\t\t3776",
        ].joined(separator: "\n") + "\n"

        XCTAssertEqual(try OverpassParser.parse(body), [
            Mountain(osmId: 1, name: "富士山", latitude: 35.3606, longitude: 138.7274, elevationM: 3776),
            Mountain(osmId: 2, name: "エックス山", latitude: 35.0, longitude: 138.0, elevationM: 1234),
            Mountain(osmId: 4, name: "無標高山", latitude: 35.2, longitude: 138.2, elevationM: nil),
        ])
    }

    func testSkipsBrokenLines() throws {
        // 値に改行が入ると 1 件が 2 行に割れて列数が合わなくなる。
        let body = [
            "@id\t@lat\t@lon\tname\tname:ja\tele",
            "5\t35.5\t138.5\t改行",
            "山\t\t500",
            "6\t35.6\t138.6\t正常山\t\t600",
        ].joined(separator: "\r\n")

        XCTAssertEqual(try OverpassParser.parse(body), [Mountain(osmId: 6, name: "正常山", latitude: 35.6, longitude: 138.6, elevationM: 600)])
    }

    func testParsesEmptyResult() throws {
        XCTAssertEqual(try OverpassParser.parse("@id\t@lat\t@lon\tname\tname:ja\tele\n"), [])
        XCTAssertEqual(try OverpassParser.parse(""), [])
    }

    func testRejectsUnexpectedFormat() {
        XCTAssertThrowsError(try OverpassParser.parse(#"{"elements":[]}"#)) { XCTAssertTrue($0 is OverpassError) }
    }

    func testParsesElevationVariants() {
        XCTAssertEqual(OverpassParser.parseElevation("3776"), 3776)
        XCTAssertEqual(OverpassParser.parseElevation("3776.5 m"), 3776.5)
        XCTAssertEqual(OverpassParser.parseElevation("3776;3775"), 3776)
        XCTAssertEqual(OverpassParser.parseElevation("1000 ft")!, 304.8, accuracy: 0.001)
        XCTAssertNil(OverpassParser.parseElevation("unknown"))
        XCTAssertNil(OverpassParser.parseElevation(nil))
    }

    func testQueryUsesBboxOrder() {
        let q = OverpassQuery.peaks(BoundingBox(south: 35, west: 138, north: 36, east: 139))
        XCTAssertTrue(q.contains(#"node["natural"="peak"]["name"](35.00000,138.00000,36.00000,139.00000);"#), q)
        XCTAssertTrue(q.contains(#"[out:csv(::id,::lat,::lon,name,"name:ja",ele;true;"\t")]"#), q)
    }
}
