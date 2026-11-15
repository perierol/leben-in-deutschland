//
//  HomeView.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import SwiftUI

struct HomeView: View {
    // The view owns its view model, so SwiftUI keeps it alive across redraws.
    @State private var viewModel: HomeViewModel
    @Bindable private var interfaceLanguageStore: InterfaceLanguageStore

    init(viewModel: HomeViewModel, interfaceLanguageStore: InterfaceLanguageStore) {
        _viewModel = State(initialValue: viewModel)
        self.interfaceLanguageStore = interfaceLanguageStore
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text(.homeTitle)
                    .font(.largeTitle.bold())
                    .accessibilityIdentifier("homeTitle")
                contentStatus
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding()
            .navigationTitle(Text(verbatim: "Leben in Deutschland"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    interfaceLanguageMenu
                }
            }
        }
        .task {
            await viewModel.load()
        }
    }

    @ViewBuilder
    private var contentStatus: some View {
        switch viewModel.contentStatus {
        case .loading:
            ProgressView()
        case .notInstalled:
            Text(.homeContentNotInstalled)
        case .installed(let questionCount):
            Text(.homeQuestionsAvailable(questionCount))
        }
    }

    // Temporary placement until onboarding and Settings exist (docs/ios-architecture.md).
    private var interfaceLanguageMenu: some View {
        Menu {
            Picker(selection: $interfaceLanguageStore.selectedCode) {
                ForEach(InterfaceLanguageStore.supportedCodes, id: \.self) { code in
                    Text(verbatim: InterfaceLanguageStore.nativeName(for: code))
                        .tag(Optional(code))
                }
            } label: {
                EmptyView()
            }
        } label: {
            Image(systemName: "globe")
        }
        .accessibilityIdentifier("interfaceLanguageMenu")
    }
}

#Preview("No content") {
    HomeView(
        viewModel: HomeViewModel(contentRepository: PlaceholderContentRepository()),
        interfaceLanguageStore: InterfaceLanguageStore()
    )
}

#Preview("Content installed") {
    struct InstalledContentRepository: ContentRepository {
        func installedQuestionCount() async -> Int? { 460 }
    }
    return HomeView(
        viewModel: HomeViewModel(contentRepository: InstalledContentRepository()),
        interfaceLanguageStore: InterfaceLanguageStore()
    )
}
