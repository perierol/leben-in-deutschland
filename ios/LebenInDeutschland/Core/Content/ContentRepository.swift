//
//  ContentRepository.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

/// Read access to the installed question content.
///
/// View models depend on this protocol rather than on SwiftData or Firestore,
/// which keeps storage and networking details out of the UI layer.
protocol ContentRepository {
    /// The number of installed questions, or `nil` when no language package is installed.
    func installedQuestionCount() async -> Int?
}

/// Stands in until SwiftData storage and Firestore sync exist; reports that
/// no content is installed.
struct PlaceholderContentRepository: ContentRepository {
    func installedQuestionCount() async -> Int? {
        nil
    }
}
