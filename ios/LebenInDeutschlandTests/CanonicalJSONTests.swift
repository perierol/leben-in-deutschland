//
//  CanonicalJSONTests.swift
//  LebenInDeutschlandTests
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import Foundation
import Testing
@testable import LebenInDeutschland

/// Expected values come from the publisher's Python encoder; see
/// `scripts/content/generate_ios_canonical_json_fixtures.py`.
struct CanonicalJSONTests {

    // MARK: - Publisher fixtures

    @Test func loadsEveryPublisherFixture() {
        #expect(CanonicalJSONFixtures.cases.count == 16)
    }

    @Test(arguments: CanonicalJSONFixtures.names)
    func matchesPublisherOutput(caseName: String) throws {
        let fixture = try #require(CanonicalJSONFixtures.named(caseName))

        let canonical = try CanonicalJSON.encode(fixture.input)

        #expect(canonical == fixture.canonical)
        #expect(canonical.utf8.count == fixture.utf8ByteCount)
        #expect(try CanonicalJSON.sha256(fixture.input) == fixture.sha256)
    }

    // MARK: - Native Swift values

    @Test func encodesNativeBooleans() throws {
        let value: [String: Any] = ["enabled": true, "disabled": false, "flags": [true, false, true]]

        try expectCanonical(value, equalsFixture: "booleans")
    }

    @Test func keepsDecimalPointOnNativeWholeNumberDoubles() throws {
        let value: [String: Any] = [
            "one": 1.0, "zero": 0.0, "negativeZero": -0.0, "hundred": 100.0,
            "integerOne": 1, "integerZero": 0,
        ]

        try expectCanonical(value, equalsFixture: "integral-floats")
    }

    @Test func formatsNativeDoublesLikePythonRepr() throws {
        let exponents: [String: Any] = [
            "small": 0.0001, "tiny": 1e-05, "belowLarge": 9.5e15, "large": 1e16, "big": 1e22,
            "largest": 1.7976931348623157e308, "smallestSubnormal": 5e-324,
        ]
        let fractions: [String: Any] = ["scores": [0.907657, 0.9842, 0.1 + 0.2, 123456.789, -2.5]]

        try expectCanonical(exponents, equalsFixture: "float-exponents")
        try expectCanonical(fractions, equalsFixture: "fractional-floats")
    }

    @Test func sortsNestedNativeKeysByCodePoint() throws {
        let nested: [String: Any] = [
            "z": ["b": [["y": 1, "x": 2], [Any](), [String: Any]()], "a": NSNull()],
            "a": ["c": ["e": "f", "d": "g"]],
            "M": "uppercase sorts before lowercase",
        ]
        let keys: [String: Any] = [
            "b": 1, "a": 2, "B": 3, "A": 4, "_": 5, "1": 6, "10": 7, "2": 8,
            "aa": 9, "z": 10, "ä": 11, "é": 12, "Ω": 13,
        ]

        try expectCanonical(nested, equalsFixture: "nested-objects")
        try expectCanonical(keys, equalsFixture: "key-ordering")
    }

    @Test func writesArabicAndQuotesFromNativeStrings() throws {
        let arabic: [String: Any] = [
            "questionText": "هل أنت مستعد للتعلّم؟",
            "answers": ["نعم", "لا", "ربما"],
            "mixed": "Bundesland: برلين (Berlin) 2026",
        ]
        let quotes: [String: Any] = [
            "doubleQuote": "Er sagte \"Ja\"", "singleQuote": "it's",
            "backslash": "C:\\path\\file", "slash": "a/b </script>",
            "newline": "line1\nline2", "tab": "a\tb", "carriageReturn": "a\r\nb",
            "backspaceAndFormFeed": "\u{08}\u{0C}", "otherControl": "\u{00}\u{01}\u{1F}",
            "delete": "\u{7F}", "lineSeparators": "\u{2028}\u{2029}",
            "typographicQuotes": "„…“ «…» ‚…‘",
        ]

        try expectCanonical(arabic, equalsFixture: "arabic")
        try expectCanonical(quotes, equalsFixture: "quotes-and-escapes")
    }

    @Test func encodesOptionalNilAsNull() throws {
        let missing: String? = nil
        let value: [Any] = [3, "two", 1.0, true, missing as Any, ["b": 2, "a": 1]]

        try expectCanonical(value, equalsFixture: "top-level-array")
    }

    // MARK: - Foundation values, as Firestore returns them

    @Test func distinguishesBooleanIntegerAndDoubleNumbers() throws {
        #expect(try CanonicalJSON.encode(kCFBooleanTrue) == "true")
        #expect(try CanonicalJSON.encode(kCFBooleanFalse) == "false")
        #expect(try CanonicalJSON.encode(NSNumber(value: true)) == "true")
        #expect(try CanonicalJSON.encode(NSNumber(value: 1)) == "1")
        #expect(try CanonicalJSON.encode(NSNumber(value: Int64.max)) == "9223372036854775807")
        #expect(try CanonicalJSON.encode(NSNumber(value: 1.0)) == "1.0")
        #expect(try CanonicalJSON.encode(NSNumber(value: 0.0)) == "0.0")
        #expect(try CanonicalJSON.encode(NSNumber(value: 0.907657)) == "0.907657")
    }

    @Test func encodesFoundationContainers() throws {
        let value = NSDictionary(dictionary: [
            "flags": NSArray(array: [kCFBooleanTrue!, kCFBooleanFalse!, kCFBooleanTrue!]),
            "enabled": kCFBooleanTrue!,
            "disabled": kCFBooleanFalse!,
        ])

        try expectCanonical(value, equalsFixture: "booleans")
    }

    // MARK: - Rejected values

    @Test func rejectsNonFiniteNumbers() {
        #expect(throws: CanonicalJSON.EncodingError.nonFiniteNumber) {
            try CanonicalJSON.encode(Double.nan)
        }
        #expect(throws: CanonicalJSON.EncodingError.nonFiniteNumber) {
            try CanonicalJSON.encode(["score": Double.infinity])
        }
    }

    @Test func rejectsNonStringKeys() {
        #expect(throws: CanonicalJSON.EncodingError.nonStringKey) {
            try CanonicalJSON.encode([1: "one"])
        }
    }

    @Test func rejectsUnsupportedValues() {
        #expect(throws: CanonicalJSON.EncodingError.self) {
            try CanonicalJSON.encode(["createdAt": Date(timeIntervalSince1970: 0)])
        }
    }

    // MARK: - A complete generated release (optional)

    /// Recomputes every chunk, package and catalog hash of a release built by
    /// `build_schema3.py`. Run it with
    /// `TEST_RUNNER_LID_SCHEMA3_RELEASE_DIR=<release dir> xcodebuild test …`.
    @Test(.enabled(if: GeneratedRelease.directory != nil, "No generated release directory set"))
    func reproducesEveryHashOfAGeneratedRelease() throws {
        let root = try #require(GeneratedRelease.directory)
        let release = try GeneratedRelease.readObject(root.appending(path: "release.json"))
        let languages = try #require(release["languages"] as? [String])
        let packageHashes = try #require(release["packageHashes"] as? [String: Any])
        var verifiedChunks = 0

        for language in languages {
            let packageDirectory = root.appending(path: "language-packs/\(language)")
            let package = try GeneratedRelease.readObject(packageDirectory.appending(path: "package.json"))
            let chunkCount = try #require(package["chunkCount"] as? Int)
            var questions: [Any] = []

            for index in 0..<chunkCount {
                let name = String(format: "%04d.json", index)
                let chunk = try GeneratedRelease.readObject(packageDirectory.appending(path: "chunks/\(name)"))
                let chunkQuestions = try #require(chunk["questions"] as? [Any])
                var withoutJSONBytes = chunk
                withoutJSONBytes["jsonBytes"] = nil

                #expect(try CanonicalJSON.sha256(chunkQuestions) == chunk["sha256"] as? String, "\(language)/\(name) sha256")
                #expect(try CanonicalJSON.encode(withoutJSONBytes).utf8.count == chunk["jsonBytes"] as? Int, "\(language)/\(name) jsonBytes")
                questions += chunkQuestions
                verifiedChunks += 1
            }

            #expect(try CanonicalJSON.sha256(questions) == package["packageSha256"] as? String, "\(language) packageSha256")
            #expect(try CanonicalJSON.encode(questions).utf8.count == package["jsonBytes"] as? Int, "\(language) jsonBytes")
        }

        #expect(try CanonicalJSON.sha256(packageHashes) == release["catalogSha256"] as? String)
        #expect(verifiedChunks > 0)
    }

    // MARK: - Helpers

    private func expectCanonical(
        _ value: Any,
        equalsFixture name: String,
        sourceLocation: SourceLocation = #_sourceLocation
    ) throws {
        let fixture = try #require(CanonicalJSONFixtures.named(name), sourceLocation: sourceLocation)
        #expect(try CanonicalJSON.encode(value) == fixture.canonical, sourceLocation: sourceLocation)
        #expect(try CanonicalJSON.sha256(value) == fixture.sha256, sourceLocation: sourceLocation)
    }
}

// MARK: - Fixture loading

private final class FixtureBundleToken {}

struct CanonicalJSONFixture {
    let name: String
    let input: Any
    let canonical: String
    let utf8ByteCount: Int
    let sha256: String
}

enum CanonicalJSONFixtures {
    static let cases: [CanonicalJSONFixture] = load()

    static var names: [String] { cases.map(\.name) }

    static func named(_ name: String) -> CanonicalJSONFixture? {
        cases.first { $0.name == name }
    }

    private static func load() -> [CanonicalJSONFixture] {
        let bundle = Bundle(for: FixtureBundleToken.self)
        guard
            let url = bundle.url(forResource: "canonical-json-fixtures", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let cases = root["cases"] as? [[String: Any]]
        else { return [] }

        return cases.compactMap { entry in
            guard
                let name = entry["name"] as? String,
                let canonical = entry["canonical"] as? String,
                let byteCount = entry["utf8ByteCount"] as? Int,
                let sha256 = entry["sha256"] as? String
            else { return nil }
            // JSONSerialization returns NSNumber, NSString, NSNull, NSDictionary and
            // NSArray, the same object types Firestore hands to the app.
            return CanonicalJSONFixture(
                name: name,
                input: entry["input"] ?? NSNull(),
                canonical: canonical,
                utf8ByteCount: byteCount,
                sha256: sha256
            )
        }
    }
}

enum GeneratedRelease {
    static var directory: URL? {
        ProcessInfo.processInfo.environment["LID_SCHEMA3_RELEASE_DIR"]
            .flatMap { $0.isEmpty ? nil : URL(filePath: $0, directoryHint: .isDirectory) }
    }

    static func readObject(_ url: URL) throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return try #require(object as? [String: Any])
    }
}
