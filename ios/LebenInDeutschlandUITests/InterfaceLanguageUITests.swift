//
//  InterfaceLanguageUITests.swift
//  LebenInDeutschlandUITests
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import XCTest

/// Verifies that the in-app interface language reaches String Catalog text and persists.
final class InterfaceLanguageUITests: XCTestCase {

    private let titles = [
        "Deutsch": "Bereit zum Lernen?",
        "Türkçe": "Çalışmaya hazır mısınız?",
        "العربية": "هل أنت مستعد للتعلّم؟",
    ]

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testSelectedLanguageUpdatesTextAndPersists() throws {
        let app = XCUIApplication()
        // The device language stays English, so any text that ignores the selection shows up.
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        for language in ["Deutsch", "Türkçe", "العربية"] {
            select(language, in: app)
            let title = app.staticTexts["homeTitle"]
            XCTAssertTrue(title.waitForExistence(timeout: 5))
            XCTAssertEqual(title.label, titles[language], "Title after selecting \(language)")
        }

        // The selection survives a relaunch.
        app.terminate()
        app.launch()
        let title = app.staticTexts["homeTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(title.label, titles["العربية"])

        // Leave the simulator in the default language for other tests.
        select("Deutsch", in: app)
    }

    @MainActor
    private func select(_ language: String, in app: XCUIApplication) {
        app.buttons["interfaceLanguageMenu"].tap()
        let item = app.buttons[language]
        XCTAssertTrue(item.waitForExistence(timeout: 5), "Menu item \(language)")
        item.tap()
    }
}
