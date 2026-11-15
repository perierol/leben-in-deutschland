//
//  AppContainer.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

/// Creates the app's long-lived dependencies once and supplies them to screens.
///
/// The iOS counterpart of Android's `AppContainer`: plain initializer injection
/// without a DI framework. Repositories are created here, and view models receive
/// them through their initializers, so a test or preview can pass a fake instead.
final class AppContainer {
    let contentRepository: any ContentRepository
    let interfaceLanguageStore: InterfaceLanguageStore

    init(
        contentRepository: any ContentRepository = PlaceholderContentRepository(),
        interfaceLanguageStore: InterfaceLanguageStore = InterfaceLanguageStore()
    ) {
        self.contentRepository = contentRepository
        self.interfaceLanguageStore = interfaceLanguageStore
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(contentRepository: contentRepository)
    }
}
