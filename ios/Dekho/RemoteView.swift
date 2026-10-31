import SwiftUI

private enum Key {
    static let up = 19, down = 20, left = 21, right = 22, ok = 23
    static let back = 4, home = 3, play = 85
    static let volumeUp = 24, volumeDown = 25, mute = 164, power = 26
}

private let ink = Color(red: 0.063, green: 0.055, blue: 0.047)
private let surface = Color.white.opacity(0.08)
private let cream = Color(red: 0.957, green: 0.929, blue: 0.878)
private let saffron = Color(red: 0.914, green: 0.635, blue: 0.231)
private let live = Color(red: 0.384, green: 0.788, blue: 0.541)

struct RemoteView: View {
    @EnvironmentObject private var tv: TV
    @Environment(\.scenePhase) private var phase
    @State private var text = ""
    @State private var showingAddress = false
    @State private var taps = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if !tv.paired { pairBox }
                    dpad
                    controls
                    typeBox
                    if !tv.note.isEmpty {
                        Text(tv.note).font(.footnote).foregroundStyle(cream.opacity(0.7))
                    }
                    tiles("Apps", tv.apps.map { app in Tile(id: app.id, name: app.name, dot: nil) { tv.open(app) } })
                    tiles("Inputs", tv.inputs.map { input in Tile(id: input.id, name: input.name, dot: input.live) { tv.open(input) } })
                }
                .padding(16)
            }
            .background(ink.ignoresSafeArea())
            .navigationTitle("Dekho")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showingAddress = true } label: {
                        HStack(spacing: 6) {
                            Circle().fill(tv.reachable ? live : .gray).frame(width: 8, height: 8)
                            Image(systemName: "gearshape")
                        }
                    }
                    .accessibilityLabel(tv.reachable ? "Connected. TV address" : "Not connected. TV address")
                }
            }
        }
        .tint(saffron)
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .sheet(isPresented: $showingAddress) { AddressView() }
        .task { await start() }
        .onChange(of: phase) { _, now in
            if now == .active { Task { await tv.refresh() } }
        }
    }

    private func start() async {
        await tv.refresh()
        if tv.address.isEmpty { showingAddress = true }
    }

    private func press(_ code: Int) {
        taps += 1
        tv.key(code)
    }

    private var pairBox: some View {
        VStack(spacing: 8) {
            Text("Allow this remote once on the TV.").foregroundStyle(cream)
            Button("Start") { tv.pair() }.buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(surface, in: RoundedRectangle(cornerRadius: 14))
    }

    private var dpad: some View {
        Grid(horizontalSpacing: 8, verticalSpacing: 8) {
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                pad("chevron.up", "Up", Key.up)
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
            }
            GridRow {
                pad("chevron.left", "Left", Key.left)
                Button("OK") { press(Key.ok) }
                    .font(.headline)
                    .buttonStyle(PadStyle(fill: saffron, ink: ink))
                pad("chevron.right", "Right", Key.right)
            }
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                pad("chevron.down", "Down", Key.down)
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
            }
        }
        .frame(maxWidth: 280)
        .frame(height: 280)
    }

    private func pad(_ icon: String, _ name: String, _ code: Int) -> some View {
        Button { press(code) } label: { Image(systemName: icon) }
            .font(.title2.weight(.semibold))
            .buttonStyle(PadStyle())
            .accessibilityLabel(name)
    }

    private var controls: some View {
        VStack(spacing: 8) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
                small("Back") { press(Key.back) }
                small("Home") { press(Key.home) }
                small("Apps") {
                    taps += 1
                    tv.strip()
                }
                small("Play") { press(Key.play) }
                small("Vol −") { press(Key.volumeDown) }
                small("Vol +") { press(Key.volumeUp) }
                small("Mute") { press(Key.mute) }
                small("Power") { press(Key.power) }
            }
            Button {
                taps += 1
                tv.brightness()
            } label: {
                Label("Brightness", systemImage: "sun.max")
            }
            .font(.subheadline)
            .buttonStyle(PadStyle(height: 48))
        }
    }

    private func small(_ name: String, action: @escaping () -> Void) -> some View {
        Button(name, action: action).font(.subheadline).buttonStyle(PadStyle(height: 48))
    }

    private var typeBox: some View {
        HStack(spacing: 8) {
            TextField("Select a box on the TV, type here", text: $text)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .noAutoCapitals()
                .onSubmit(sendText)
            Button("Send", action: sendText).buttonStyle(.borderedProminent)
        }
    }

    private func sendText() {
        guard !text.isEmpty else { return }
        let words = text
        Task {
            if await tv.type(words) { text = "" }
        }
    }

    private func tiles(_ title: String, _ items: [Tile]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if !items.isEmpty {
                Text(title).font(.footnote.weight(.semibold)).foregroundStyle(cream.opacity(0.7))
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                ForEach(items) { item in
                    Button {
                        taps += 1
                        item.action()
                    } label: {
                        HStack(spacing: 6) {
                            if let dot = item.dot {
                                Circle().fill(dot ? live : .gray).frame(width: 8, height: 8)
                            }
                            Text(item.name).lineLimit(2).minimumScaleFactor(0.8)
                        }
                    }
                    .font(.subheadline)
                    .buttonStyle(PadStyle(height: 56))
                }
            }
        }
    }
}

private struct Tile: Identifiable {
    let id: String
    let name: String
    let dot: Bool?
    let action: () -> Void
}

private struct PadStyle: ButtonStyle {
    var fill = surface
    var ink = cream
    var height: CGFloat?

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, maxHeight: height == nil ? .infinity : nil)
            .frame(height: height)
            .foregroundStyle(ink)
            .background(fill, in: RoundedRectangle(cornerRadius: 14))
            .opacity(configuration.isPressed ? 0.6 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

private struct AddressView: View {
    @EnvironmentObject private var tv: TV
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("http://192.168.0.2:8088/abcd1234", text: $tv.address)
                        .autocorrectionDisabled()
                        .noAutoCapitals()
                } header: {
                    Text("TV address")
                } footer: {
                    Text("On the TV, open Customise on the Dekho home screen to see this address.")
                }
            }
            .navigationTitle("Your TV")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        Task { await tv.refresh() }
                        dismiss()
                    }
                }
            }
        }
    }
}

private extension View {
    @ViewBuilder func noAutoCapitals() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).keyboardType(.asciiCapable)
        #else
        self
        #endif
    }
}
