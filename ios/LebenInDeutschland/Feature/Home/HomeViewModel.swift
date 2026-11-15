//
//  HomeViewModel.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import Observation

@Observable
final class HomeViewModel {
    enum ContentStatus: Equatable {
        case loading
        case notInstalled
        case installed(questionCount: Int)
    }

    private(set) var contentStatus: ContentStatus = .loading

    private let contentRepository: any ContentRepository

    init(contentRepository: any ContentRepository) {
        self.contentRepository = contentRepository
    }

    func load() async {
        if let questionCount = await contentRepository.installedQuestionCount() {
            contentStatus = .installed(questionCount: questionCount)
        } else {
            contentStatus = .notInstalled
        }
    }
}
