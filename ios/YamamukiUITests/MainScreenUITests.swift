import XCTest

/// 方位盤の画面を実際に起動して操作する UI テスト。実機でもシミュレータでも動く。
/// 実機で動かすと、端末に入っている山むきの設定(通信の同意など)をそのまま使い、初回の確認には「はい」で答える。
final class MainScreenUITests: XCTestCase {
    private let app = XCUIApplication()
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launch()
        answerFirstLaunchPrompts()
    }

    func testMainScreenShowsButtons() {
        XCTAssertTrue(app.buttons["設定"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["山データの事前ダウンロード"].exists)
        XCTAssertTrue(app.staticTexts["© OpenStreetMap contributors"].exists)
        attachScreenshot("方位盤")
    }

    func testSettingsOpensAndCloses() {
        openSheet(button: "設定", title: "設定")
    }

    func testAreaDownloadOpensAndCloses() {
        openSheet(button: "山データの事前ダウンロード", title: "事前ダウンロード")
    }

    /// 左下のボタンでシートを開き、画面を撮ってから「閉じる」で閉じる。
    private func openSheet(button: String, title: String) {
        let opener = app.buttons[button]
        XCTAssertTrue(opener.waitForExistence(timeout: 10))
        dismissFetchErrorIfShown()
        opener.tap()

        let bar = app.navigationBars[title]
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        attachScreenshot(title)

        bar.buttons["閉じる"].tap()
        XCTAssertTrue(waitForDisappearance(of: bar))
    }

    /// 初回起動の確認に答える。通信の同意と位置情報の許可は、どちらも 1 度答えると出なくなる。
    private func answerFirstLaunchPrompts() {
        let consent = app.alerts["山データの取得"]
        if consent.waitForExistence(timeout: 3) {
            consent.buttons["はい"].tap()
        }
        let allow = app.buttons["許可する"]
        if allow.waitForExistence(timeout: 2) {
            allow.tap()
            // 位置情報の許可は OS のダイアログなので、ホーム画面のアプリから探す。文言は OS の版と言語で変わる。
            let systemAlert = springboard.alerts.firstMatch
            if systemAlert.waitForExistence(timeout: 5) {
                let whileUsing = NSPredicate(format: "label CONTAINS '使用中は許可' OR label CONTAINS 'While Using'")
                systemAlert.buttons.matching(whileUsing).firstMatch.tap()
            }
        }
    }

    /// 通信に失敗したときのアラートが出ていると、下のボタンを押せないので閉じる。
    private func dismissFetchErrorIfShown() {
        let error = app.alerts["山データを取得できませんでした"]
        if error.exists {
            error.buttons["閉じる"].tap()
        }
    }

    private func waitForDisappearance(of element: XCUIElement) -> Bool {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        return XCTWaiter.wait(for: [gone], timeout: 5) == .completed
    }

    /// 撮った画面はテスト結果(.xcresult)に残り、Xcode の Report ナビゲータで見られる。
    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
