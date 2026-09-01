import SwiftUI

private struct FaultButton: Identifiable {
    let id = UUID()
    let code: UInt8
    let name: String
    let symbol: String
}

private let faults: [FaultButton] = [
    FaultButton(code: 0x01, name: "Drop next RSP", symbol: "arrow.down.left.slash"),
    FaultButton(code: 0x02, name: "Drop all RSP", symbol: "wifi.slash"),
    FaultButton(code: 0x03, name: "Corrupt CRC", symbol: "exclamationmark.triangle"),
    FaultButton(code: 0x04, name: "Delay past T_RESP", symbol: "clock.badge.exclamationmark"),
    FaultButton(code: 0x05, name: "Duplicate notify", symbol: "doc.on.doc"),
    FaultButton(code: 0x06, name: "Evict records", symbol: "trash"),
    FaultButton(code: 0x07, name: "Reset store", symbol: "arrow.counterclockwise"),
    FaultButton(code: 0x08, name: "Disconnect after accept", symbol: "bolt.horizontal.circle"),
]

struct ContentView: View {
    @ObservedObject var peripheral: PeripheralController

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            controls
                .frame(width: 420)
                .padding(20)
            Divider()
            eventLog
                .frame(minWidth: 340)
                .padding(20)
        }
        .frame(minWidth: 820, minHeight: 620)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            gattPanel
            pumpPanel
            faultPanel
            Spacer(minLength: 0)
            Text("Keep this window in the foreground. A backgrounded Apple peripheral stops advertising and is invisible to an Android central.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("pump-link peripheral")
                .font(.system(size: 20, weight: .semibold))
            HStack(spacing: 8) {
                pill(text: peripheral.status, tone: statusTone)
                pill(
                    text: peripheral.harnessConnected ? "pump-host up" : "pump-host down",
                    tone: peripheral.harnessConnected ? .green : .red
                )
            }
        }
    }

    private var statusTone: Color {
        if peripheral.subscribed { return .green }
        if peripheral.status.hasPrefix("advertising") { return .orange }
        return .red
    }

    private func pill(text: String, tone: Color) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(tone.opacity(0.18), in: Capsule())
            .foregroundStyle(tone)
    }

    private var gattPanel: some View {
        panel("GATT") {
            row("Service", String(GattUuids.service.prefix(13)) + "…")
            row("Negotiated MTU", "\(peripheral.mtu) B")
            row("RSP subscribed", peripheral.subscribed ? "yes" : "no")
            row("STATUS subscribed", peripheral.statusSubscribed ? "yes" : "no")
            row("Central", peripheral.centralName)
        }
    }

    private var pumpPanel: some View {
        panel("Pump state") {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Reservoir").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(Int(peripheral.reservoirUnits)) U")
                        .font(.system(size: 11, design: .monospaced))
                }
                Slider(value: reservoir, in: 0...300, step: 10)
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Battery").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(Int(peripheral.batteryPercent))%")
                        .font(.system(size: 11, design: .monospaced))
                }
                Slider(value: battery, in: 0...100, step: 5)
            }
        }
    }

    private var reservoir: Binding<Double> {
        Binding(
            get: { peripheral.reservoirUnits },
            set: { peripheral.setReservoir($0) }
        )
    }

    private var battery: Binding<Double> {
        Binding(
            get: { peripheral.batteryPercent },
            set: { peripheral.setBattery($0) }
        )
    }

    private var faultPanel: some View {
        panel("Fault injection") {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(faults) { fault in
                    Button {
                        peripheral.inject(fault: fault.code, named: fault.name)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: fault.symbol)
                                .frame(width: 14)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(fault.name)
                                    .font(.system(size: 11))
                                    .lineLimit(1)
                                Text(String(format: "0x%02X", fault.code))
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.bordered)
                    .tint(peripheral.armedFault == fault.code ? .orange : .accentColor)
                }
            }
            HStack(spacing: 8) {
                Button("Clear fault") { peripheral.clearFault() }
                    .disabled(peripheral.armedFault == 0x00)
                Button("Disconnect now") { peripheral.forceDisconnect() }
                Button("Resume advertising") { peripheral.resumeAdvertising() }
            }
            .controlSize(.small)
            if peripheral.armedFault != 0x00 {
                Text("Armed: the next command is shaped, including its retries. Status polls do not consume this.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }

    private var eventLog: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Event log")
                .font(.system(size: 13, weight: .semibold))
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(peripheral.log) { line in
                            HStack(alignment: .top, spacing: 8) {
                                Text(timestamp(line.at))
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                Text(marker(line.kind))
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundStyle(tone(line.kind))
                                Text(line.text)
                                    .font(.system(size: 11, design: .monospaced))
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                            }
                            .id(line.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: peripheral.log.count) {
                    if let last = peripheral.log.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
            .background(Color(nsColor: .textBackgroundColor).opacity(0.4))
        }
    }

    private func timestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: date)
    }

    private func marker(_ kind: LogLine.Kind) -> String {
        switch kind {
        case .inbound: return "<-"
        case .outbound: return "->"
        case .link: return "**"
        case .fault: return "!!"
        }
    }

    private func tone(_ kind: LogLine.Kind) -> Color {
        switch kind {
        case .inbound: return .blue
        case .outbound: return .green
        case .link: return .secondary
        case .fault: return .orange
        }
    }

    private func panel<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.secondary)
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10))
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.system(size: 11, design: .monospaced))
        }
    }
}
