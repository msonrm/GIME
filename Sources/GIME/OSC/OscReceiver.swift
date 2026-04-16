import Foundation
import Network

/// OSC メッセージを UDP で受信するデバッグ用 receiver。
///
/// - `NWListener` で 任意ポートにバインドして LAN からの受信も受け付ける
/// - 受信イベントは `handler` クロージャで通知される（メインスレッドとは限らない）
/// - デコード失敗はエラーイベントとして配信
///
/// 使い方:
/// ```
/// let receiver = OscReceiver(port: 9001) { event in
///     // UI 更新は DispatchQueue.main で行うこと
/// }
/// receiver.start()
/// // ...
/// receiver.stop()
/// ```
public final class OscReceiver: @unchecked Sendable {

    public enum Event: Sendable {
        case received(message: OscMessage, fromHost: String)
        case error(String)
    }

    private let port: NWEndpoint.Port
    private let queue = DispatchQueue(label: "OscReceiver")
    private let handler: @Sendable (Event) -> Void
    private var listener: NWListener?
    private let lock = NSLock()

    public init(port: UInt16, handler: @escaping @Sendable (Event) -> Void) {
        self.port = NWEndpoint.Port(rawValue: port) ?? .any
        self.handler = handler
    }

    public func start() {
        lock.lock()
        if listener != nil {
            lock.unlock()
            return
        }
        lock.unlock()

        do {
            let listener = try NWListener(using: .udp, on: port)
            listener.newConnectionHandler = { [weak self] conn in
                self?.acceptConnection(conn)
            }
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .failed(let err):
                    self?.handler(.error("listener failed: \(err.localizedDescription)"))
                case .cancelled:
                    break
                default:
                    break
                }
            }
            listener.start(queue: queue)
            lock.lock()
            self.listener = listener
            lock.unlock()
        } catch {
            handler(.error("listener bind failed: \(error.localizedDescription)"))
        }
    }

    public func stop() {
        lock.lock()
        let l = listener
        listener = nil
        lock.unlock()
        l?.cancel()
    }

    // MARK: - Private

    private func acceptConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            if case .ready = state {
                self?.receiveNext(on: connection)
            } else if case .failed(let err) = state {
                self?.handler(.error("connection failed: \(err.localizedDescription)"))
                connection?.cancel()
            }
        }
        connection.start(queue: queue)
    }

    private func receiveNext(on connection: NWConnection?) {
        guard let connection else { return }
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                let fromHost = Self.describeRemote(connection)
                do {
                    let msg = try OscPacket.decode(data)
                    self.handler(.received(message: msg, fromHost: fromHost))
                } catch {
                    self.handler(.error("decode failed: \(error.localizedDescription)"))
                }
            }
            if let error {
                self.handler(.error("receive failed: \(error.localizedDescription)"))
                return
            }
            // continue receiving on same connection
            self.receiveNext(on: connection)
        }
    }

    private static func describeRemote(_ connection: NWConnection?) -> String {
        guard let endpoint = connection?.endpoint else { return "?" }
        switch endpoint {
        case .hostPort(let host, _):
            switch host {
            case .name(let name, _): return name
            case .ipv4(let addr): return "\(addr)"
            case .ipv6(let addr): return "\(addr)"
            @unknown default: return "?"
            }
        default:
            return "?"
        }
    }
}
