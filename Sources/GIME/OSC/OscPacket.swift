import Foundation

/// OSC 1.0 パケットの引数型。
///
/// 外部依存なしの最小実装なので `s / i / f / T / F` のみサポートする。
/// VRChat chatbox / avatar parameters にはこれで十分。
public enum OscArgument: Equatable, Sendable {
    case string(String)
    case int32(Int32)
    case float32(Float)
    case bool(Bool)
}

/// デコードされた OSC メッセージ。
public struct OscMessage: Equatable, Sendable {
    public let address: String
    public let arguments: [OscArgument]

    public init(address: String, arguments: [OscArgument]) {
        self.address = address
        self.arguments = arguments
    }

    /// デバッグ表示用。`/chatbox/input "hi" true false` のように整形する。
    public var debugDescription: String {
        let argStr = arguments.map { arg -> String in
            switch arg {
            case .string(let s): return "\"\(s)\""
            case .int32(let i): return "\(i)"
            case .float32(let f): return "\(f)"
            case .bool(let b): return "\(b)"
            }
        }.joined(separator: " ")
        return argStr.isEmpty ? address : "\(address) \(argStr)"
    }
}

public enum OscDecodeError: Error, Equatable, Sendable {
    case invalidAddress(String)
    case invalidTypeTag(String)
    case unsupportedTypeTag(Character)
    case bundleNotSupported
    case unterminatedString
    case truncated
}

/// OSC 1.0 パケットのエンコード/デコード（外部依存なしの最小実装）。
///
/// サポートする型タグ:
/// - `s` : string（UTF-8、null 終端、4 byte アラインメント）
/// - `i` : int32（big-endian）
/// - `f` : float32（big-endian IEEE 754）
/// - `T` : true （引数データなし）
/// - `F` : false（引数データなし）
///
/// Bundles (`#bundle`) は未対応。
///
/// 参考: https://opensoundcontrol.stanford.edu/spec-1_0.html
public enum OscPacket {

    /// OSC メッセージを `Data` にエンコードする。
    public static func encode(_ address: String, _ arguments: OscArgument...) -> Data {
        encode(address, arguments)
    }

    /// OSC メッセージを `Data` にエンコードする（配列版）。
    public static func encode(_ address: String, _ arguments: [OscArgument]) -> Data {
        precondition(address.hasPrefix("/"), "OSC address must start with '/': \(address)")

        // 1. type tag string: ",<tags>"
        var typeTags = ","
        for arg in arguments {
            switch arg {
            case .string: typeTags.append("s")
            case .int32: typeTags.append("i")
            case .float32: typeTags.append("f")
            case .bool(let b): typeTags.append(b ? "T" : "F")
            }
        }

        // 2. 動的バッファに書き込む
        //    アドレス（padded string） + type tag（padded string） + 引数データ
        var buffer = Data()
        appendPaddedString(&buffer, address)
        appendPaddedString(&buffer, typeTags)
        for arg in arguments {
            switch arg {
            case .string(let s):
                appendPaddedString(&buffer, s)
            case .int32(let i):
                var bigEndian = i.bigEndian
                withUnsafeBytes(of: &bigEndian) { buffer.append(contentsOf: $0) }
            case .float32(let f):
                var bits = f.bitPattern.bigEndian
                withUnsafeBytes(of: &bits) { buffer.append(contentsOf: $0) }
            case .bool:
                // T / F はデータなし
                break
            }
        }
        return buffer
    }

    /// `Data` から OSC メッセージをデコードする。
    /// Bundle は未対応（`#bundle` で始まるパケットは [OscDecodeError.bundleNotSupported] を投げる）。
    public static func decode(_ data: Data) throws -> OscMessage {
        var cursor = data.startIndex
        let address = try readPaddedString(data, cursor: &cursor)
        if address == "#bundle" {
            throw OscDecodeError.bundleNotSupported
        }
        if !address.hasPrefix("/") {
            throw OscDecodeError.invalidAddress(address)
        }
        let typeTagStr = try readPaddedString(data, cursor: &cursor)
        guard typeTagStr.hasPrefix(",") else {
            throw OscDecodeError.invalidTypeTag(typeTagStr)
        }
        let tags = typeTagStr.dropFirst()
        var args: [OscArgument] = []
        for tag in tags {
            switch tag {
            case "s":
                args.append(.string(try readPaddedString(data, cursor: &cursor)))
            case "i":
                args.append(.int32(try readInt32(data, cursor: &cursor)))
            case "f":
                args.append(.float32(try readFloat32(data, cursor: &cursor)))
            case "T":
                args.append(.bool(true))
            case "F":
                args.append(.bool(false))
            case "N":
                // Null 値、とりあえず無視
                break
            default:
                throw OscDecodeError.unsupportedTypeTag(tag)
            }
        }
        return OscMessage(address: address, arguments: args)
    }

    // MARK: - ヘルパー

    private static func appendPaddedString(_ buffer: inout Data, _ string: String) {
        let utf8 = Array(string.utf8)
        buffer.append(contentsOf: utf8)
        buffer.append(0)
        // 4 byte アラインメントまで null padding
        let consumed = utf8.count + 1
        let pad = (4 - consumed % 4) % 4
        for _ in 0..<pad {
            buffer.append(0)
        }
    }

    private static func readPaddedString(_ data: Data, cursor: inout Data.Index) throws -> String {
        let start = cursor
        while cursor < data.endIndex, data[cursor] != 0 {
            cursor = data.index(after: cursor)
        }
        if cursor >= data.endIndex {
            throw OscDecodeError.unterminatedString
        }
        let stringData = data[start..<cursor]
        // null terminator を1バイト進める
        cursor = data.index(after: cursor)
        // パディング分を読み飛ばす
        let consumed = data.distance(from: start, to: cursor)
        let pad = (4 - consumed % 4) % 4
        let padEnd = data.index(cursor, offsetBy: pad, limitedBy: data.endIndex) ?? data.endIndex
        cursor = padEnd
        return String(decoding: stringData, as: UTF8.self)
    }

    private static func readInt32(_ data: Data, cursor: inout Data.Index) throws -> Int32 {
        guard let end = data.index(cursor, offsetBy: 4, limitedBy: data.endIndex) else {
            throw OscDecodeError.truncated
        }
        var value: UInt32 = 0
        var shift: UInt32 = 24
        var i = cursor
        while i < end {
            value |= UInt32(data[i]) << shift
            shift = shift &- 8
            i = data.index(after: i)
        }
        cursor = end
        return Int32(bitPattern: value)
    }

    private static func readFloat32(_ data: Data, cursor: inout Data.Index) throws -> Float {
        let raw = try readInt32(data, cursor: &cursor)
        return Float(bitPattern: UInt32(bitPattern: raw))
    }
}
