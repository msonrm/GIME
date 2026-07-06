import Foundation
import Network

/// OSC メッセージを UDP で送信するシンプルな sender。
///
/// - Network framework の `NWConnection` を使用（iOS 12+）
/// - 送信先は runtime に変更可能（`updateTarget(host:port:)`）
/// - 送信は fire-and-forget（エラーはコールバックで通知）
/// - メインスレッドから `send(...)` を呼んでもブロックしない
///   （内部で専用 DispatchQueue に dispatch される）
///
/// 使い方:
/// ```
/// let sender = OscSender(host: "127.0.0.1", port: 9000)
/// sender.send("/chatbox/input", .string("こんにちは"), .bool(true), .bool(false))
/// sender.close()
/// ```
public final class OscSender: @unchecked Sendable {

    /// 送信エラーのコールバック。メインスレッドで呼ばれるとは限らない。
    public var onError: (@Sendable (Error) -> Void)?

    private let queue: DispatchQueue
    private let lock = NSLock()
    private var connection: NWConnection

    public init(host: String, port: UInt16) {
        self.queue = DispatchQueue(label: "OscSender.\(host):\(port)")
        self.connection = Self.makeConnection(host: host, port: port)
        self.connection.start(queue: queue)
    }

    /// 送信先を更新する。古い接続はキャンセルされる。
    public func updateTarget(host: String, port: UInt16) {
        lock.lock()
        let old = connection
        let newConn = Self.makeConnection(host: host, port: port)
        connection = newConn
        lock.unlock()

        old.cancel()
        newConn.start(queue: queue)
    }

    /// 現在の送信先（表示用）。
    public func currentTarget() -> (host: String, port: UInt16) {
        lock.lock()
        defer { lock.unlock() }
        switch connection.endpoint {
        case .hostPort(let host, let port):
            return (Self.describe(host: host), port.rawValue)
        default:
            return ("?", 0)
        }
    }

    /// OSC メッセージをエンコードして送信。
    public func send(_ address: String, _ arguments: OscArgument...) {
        send(address, arguments)
    }

    /// OSC メッセージをエンコードして送信（配列版）。
    public func send(_ address: String, _ arguments: [OscArgument]) {
        let data = OscPacket.encode(address, arguments)
        sendRaw(data)
    }

    /// 生バイト列を送信（デバッグ用）。
    public func sendRaw(_ data: Data) {
        lock.lock()
        let conn = connection
        lock.unlock()
        let handler = onError
        conn.send(content: data, completion: .contentProcessed { error in
            if let error {
                handler?(error)
            }
        })
    }

    public func close() {
        lock.lock()
        let conn = connection
        lock.unlock()
        conn.cancel()
    }

    // MARK: - ヘルパー

    private static func makeConnection(host: String, port: UInt16) -> NWConnection {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? .any
        )
        // OSC は connectionless UDP
        return NWConnection(to: endpoint, using: .udp)
    }

    private static func describe(host: NWEndpoint.Host) -> String {
        switch host {
        case .name(let name, _): return name
        case .ipv4(let addr): return "\(addr)"
        case .ipv6(let addr): return "\(addr)"
        @unknown default: return "?"
        }
    }
}
