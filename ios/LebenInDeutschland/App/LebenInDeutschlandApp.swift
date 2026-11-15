//
//  LebenInDeutschlandApp.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 10.10.2026.
//

import SwiftUI

@main
struct LebenInDeutschlandApp: App {
    // Created once for the lifetime of the app, like Android's Application.appContainer.
    private let container = AppContainer()

    var body: some Scene {
        WindowGroup {
            AppRootView(container: container)
        }
    }
}

/// Applies the selected interface language to every screen below it.
private struct AppRootView: View {
    let container: AppContainer

    var body: some View {
        let language = container.interfaceLanguageStore
        HomeView(viewModel: container.makeHomeViewModel(), interfaceLanguageStore: language)
            .environment(\.locale, language.locale)
            .environment(\.layoutDirection, language.layoutDirection)
    }
}
