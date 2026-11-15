//
//  LebenInDeutschlandTests.swift
//  LebenInDeutschlandTests
//
//  Created by Perihan İmamoğlu Erol on 10.10.2026.
//

import Testing
@testable import LebenInDeutschland

@MainActor
struct HomeViewModelTests {

    private struct FakeContentRepository: ContentRepository {
        let questionCount: Int?

        func installedQuestionCount() async -> Int? { questionCount }
    }

    @Test func startsLoading() {
        let viewModel = HomeViewModel(contentRepository: FakeContentRepository(questionCount: nil))

        #expect(viewModel.contentStatus == .loading)
    }

    @Test func reportsMissingContent() async {
        let viewModel = HomeViewModel(contentRepository: FakeContentRepository(questionCount: nil))

        await viewModel.load()

        #expect(viewModel.contentStatus == .notInstalled)
    }

    @Test func reportsInstalledQuestionCount() async {
        let viewModel = HomeViewModel(contentRepository: FakeContentRepository(questionCount: 460))

        await viewModel.load()

        #expect(viewModel.contentStatus == .installed(questionCount: 460))
    }
}
