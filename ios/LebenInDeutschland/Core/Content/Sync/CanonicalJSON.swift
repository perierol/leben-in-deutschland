//
//  CanonicalJSON.swift
//  LebenInDeutschland
//
//  Created by Perihan İmamoğlu Erol on 15.11.2026.
//

import CryptoKit
import Foundation

/// The canonical JSON text that schema-3 content hashes are computed over.
///
/// Reproduces the publisher's `json.dumps(value, ensure_ascii=False, sort_keys=True,
/// separators=(",", ":"))` in `scripts/content/build_schema3.py` byte for byte, the
/// contract Android implements in `CanonicalJson.kt`. Input is what Firestore returns
/// (`NSString`, `NSNumber`, `NSNull`, `NSDictionary`, `NSArray`) or the native Swift
/// equivalents. `ios/LebenInDeutschlandTests/Fixtures/canonical-json-fixtures.json`
/// holds the publisher's expected output.
nonisolated enum CanonicalJSON {
    enum EncodingError: Error, Equatable {
        case nonFiniteNumber
        case nonStringKey
        case unsupportedValue(type: String)
    }

    static func encode(_ value: Any?) throws -> String {
        var output = ""
        try append(value, to: &output)
        return output
    }

    /// Lowercase hexadecimal SHA-256 of the UTF-8 canonical text.
    static func sha256(_ value: Any?) throws -> String {
        let digest = SHA256.hash(data: Data(try encode(value).utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Values

    private static func append(_ value: Any?, to output: inout String) throws {
        guard let value else {
            output += "null"
            return
        }
        // Native Swift scalars first: an `as? NSNumber` cast would also accept them
        // and lose the difference between Bool, Int and Double.
        switch value {
        case is NSNull:
            output += "null"
        case let string as String:
            appendString(string, to: &output)
        case let bool as Bool where type(of: value) == Bool.self:
            output += bool ? "true" : "false"
        case let double as Double where type(of: value) == Double.self:
            try appendDouble(double, to: &output)
        case let float as Float where type(of: value) == Float.self:
            try appendDouble(Double(float), to: &output)
        case let integer as any BinaryInteger:
            output += String(describing: integer)
        case let number as NSNumber:
            try appendNumber(number, to: &output)
        case let object as [String: Any]:
            try appendObject(object, to: &output)
        case is [AnyHashable: Any]:
            throw EncodingError.nonStringKey
        case let array as [Any]:
            output += "["
            for (index, element) in array.enumerated() {
                if index > 0 { output += "," }
                try append(element, to: &output)
            }
            output += "]"
        default:
            let mirror = Mirror(reflecting: value)
            if mirror.displayStyle == .optional {
                // An Optional stored inside `Any`: encode its wrapped value, or null.
                try append(mirror.children.first?.value, to: &output)
            } else {
                throw EncodingError.unsupportedValue(type: String(describing: type(of: value)))
            }
        }
    }

    /// Firestore returns every number and boolean as `NSNumber`, so its Core
    /// Foundation type decides how it is written: `true` must not become `1`, and
    /// `1.0` must not become `1`.
    private static func appendNumber(_ number: NSNumber, to output: inout String) throws {
        if number is NSDecimalNumber {
            throw EncodingError.unsupportedValue(type: "NSDecimalNumber")
        }
        if CFGetTypeID(number) == CFBooleanGetTypeID() {
            output += number.boolValue ? "true" : "false"
        } else if CFNumberIsFloatType(number) {
            try appendDouble(number.doubleValue, to: &output)
        } else if String(cString: number.objCType) == "Q" {
            output += String(number.uint64Value)
        } else {
            output += String(number.int64Value)
        }
    }

    private static func appendObject(_ object: [String: Any], to output: inout String) throws {
        // Python sorts keys by Unicode code point. Swift's `<` on String compares
        // canonically equivalent forms as equal, so compare the scalars instead.
        let keys = object.keys.sorted { lhs, rhs in
            lhs.unicodeScalars.lexicographicallyPrecedes(rhs.unicodeScalars) {
                $0.value < $1.value
            }
        }
        output += "{"
        for (index, key) in keys.enumerated() {
            if index > 0 { output += "," }
            appendString(key, to: &output)
            output += ":"
            try append(object[key] ?? nil, to: &output)
        }
        output += "}"
    }

    // MARK: - Strings

    /// Python's `ensure_ascii=False` escaping: quotes, backslashes and control
    /// characters only. Everything else is written as raw UTF-8, unnormalized.
    private static func appendString(_ string: String, to output: inout String) {
        output += "\""
        for scalar in string.unicodeScalars {
            switch scalar {
            case "\"": output += "\\\""
            case "\\": output += "\\\\"
            case "\n": output += "\\n"
            case "\r": output += "\\r"
            case "\t": output += "\\t"
            case "\u{08}": output += "\\b"
            case "\u{0C}": output += "\\f"
            case _ where scalar.value < 0x20:
                output += String(format: "\\u%04x", scalar.value)
            default:
                output.unicodeScalars.append(scalar)
            }
        }
        output += "\""
    }

    // MARK: - Floating point

    /// Python's `repr(float)`: the shortest digits that round-trip, written in
    /// fixed notation when the decimal exponent is in -4..<16 and in exponent
    /// notation (`1e+16`, `1e-05`) otherwise.
    private static func appendDouble(_ value: Double, to output: inout String) throws {
        guard value.isFinite else { throw EncodingError.nonFiniteNumber }
        if value == 0 {
            output += value.sign == .minus ? "-0.0" : "0.0"
            return
        }

        // Swift's description also yields the shortest round-trip digits; only the
        // layout differs from Python's, so extract the digits and decimal exponent.
        var text = Substring(value.description)
        let isNegative = text.first == "-"
        if isNegative { text = text.dropFirst() }

        var exponent = 0
        if let marker = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exponent = Int(text[text.index(after: marker)...]) ?? 0
            text = text[..<marker]
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let integerDigits = parts[0]
        let fractionDigits = parts.count > 1 ? parts[1] : ""

        var digits = Array(integerDigits + fractionDigits)
        // Position of the decimal point relative to the start of `digits`.
        var decimalPoint = integerDigits.count + exponent
        while digits.count > 1, digits.first == "0" {
            digits.removeFirst()
            decimalPoint -= 1
        }
        while digits.count > 1, digits.last == "0" {
            digits.removeLast()
        }

        if isNegative { output += "-" }
        let scientificExponent = decimalPoint - 1
        if scientificExponent < -4 || scientificExponent >= 16 {
            output.append(digits[0])
            if digits.count > 1 {
                output += "."
                output += String(digits[1...])
            }
            output += scientificExponent < 0 ? "e-" : "e+"
            output += String(format: "%02d", abs(scientificExponent))
        } else if decimalPoint <= 0 {
            output += "0."
            output += String(repeating: "0", count: -decimalPoint)
            output += String(digits)
        } else if decimalPoint >= digits.count {
            output += String(digits)
            output += String(repeating: "0", count: decimalPoint - digits.count)
            output += ".0"
        } else {
            output += String(digits[..<decimalPoint])
            output += "."
            output += String(digits[decimalPoint...])
        }
    }
}
