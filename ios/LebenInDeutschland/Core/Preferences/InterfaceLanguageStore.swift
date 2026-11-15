//
//  InterfaceLanguageStore.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import Foundation
import Observation
import SwiftUI

/// The persisted interface language, applied to the SwiftUI hierarchy
/// through `\.locale`.
///
/// This is deliberately separate from the question-content language. Content comes
/// from an installed language package and may only switch after a new package has
/// been validated, while the interface can switch immediately.
@Observable
final class InterfaceLanguageStore {
    /// Interface languages, in the same order as Android's `SupportedLanguage`.
    static let supportedCodes = [
        "de", "en", "tr", "uk", "ar", "ro", "pl", "ru", "bg", "hr", "bs",
        "sr-Latn", "el", "sq", "fa", "ps", "ku", "it", "es", "fr", "hu",
    ]

    private static let rightToLeftCodes: Set<String> = ["ar", "fa", "ps"]
    private static let defaultsKey = "interface_language"

    /// The selected language code, or `nil` to follow the device language.
    var selectedCode: String? {
        didSet { defaults.set(selectedCode, forKey: Self.defaultsKey) }
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.string(forKey: Self.defaultsKey)
        selectedCode = stored.flatMap { Self.supportedCodes.contains($0) ? $0 : nil }
    }

    var locale: Locale {
        selectedCode.map(Locale.init(identifier:)) ?? .autoupdatingCurrent
    }

    var layoutDirection: LayoutDirection {
        let code = selectedCode ?? Locale.autoupdatingCurrent.language.languageCode?.identifier ?? ""
        return Self.rightToLeftCodes.contains(code) ? .rightToLeft : .leftToRight
    }

    /// The language's own name, for example "Türkçe" or "العربية".
    static func nativeName(for code: String) -> String {
        Locale(identifier: code).localizedString(forIdentifier: code) ?? code
    }
}
