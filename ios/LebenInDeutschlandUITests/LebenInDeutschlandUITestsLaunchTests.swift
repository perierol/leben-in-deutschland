//
//  LebenInDeutschlandUITestsLaunchTests.swift
//  LebenInDeutschlandUITests
//
//  Created by Perihan İmamoğlu Erol on 10.10.2026.
//

import XCTest

final class LebenInDeutschlandUITestsLaunchTests: XCTestCase {

    // One launch is enough; with 21 languages, every configuration means dozens of launches.
    override class var runsForEachTargetApplicationUIConfiguration: Bool {
        false
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launch()

        // Insert steps here to perform after app launch but before taking a screenshot,
        // such as logging into a test account or navigating somewhere in the app

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Launch Screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
