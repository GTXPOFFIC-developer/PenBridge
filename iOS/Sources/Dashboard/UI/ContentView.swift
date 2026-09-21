import SwiftUI

public struct ContentView: View {
    @StateObject private var google = GoogleAccountSync()
    @StateObject private var connection: ConnectionManager
    @State private var isDrawing = false
    @State private var manualIp = ""
    @State private var pairingPin = ""

    public init() {
        let g = GoogleAccountSync()
        _google = StateObject(wrappedValue: g)
        _connection = StateObject(wrappedValue: ConnectionManager(google: g))
    }

    public var body: some View {
        ZStack {
            Color(red: 0x0B/255.0, green: 0x0E/255.0, blue: 0x1A/255.0)
                .ignoresSafeArea()

            if isDrawing {
                ZStack(alignment: .topTrailing) {
                    DrawingCanvasView(connection: connection)
                        .ignoresSafeArea()

                    Button(action: { isDrawing = false }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.white.opacity(0.6))
                            .padding()
                    }
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        // Header
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(LinearGradient(colors: [Color(red: 0x8B/255.0, green: 0x5C/255.0, blue: 0xF6/255.0),
                                                             Color(red: 0x4F/255.0, green: 0x46/255.0, blue: 0xE5/255.0)],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                                .frame(width: 44, height: 44)
                                .overlay(Text("⚡").font(.title2))

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Dashboard")
                                    .font(.title2.bold())
                                    .foregroundColor(.white)
                                Text("iPad · Apple Pencil Bridge")
                                    .font(.caption)
                                    .foregroundColor(Color(red: 0x8B/255.0, green: 0x93/255.0, blue: 0xB8/255.0))
                            }
                            Spacer()
                        }
                        .padding(.top, 10)

                        // Google Account Card
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Google Account Auto-Pair")
                                .font(.headline)
                                .foregroundColor(.white)

                            if google.signedIn, let email = google.email {
                                HStack {
                                    Circle()
                                        .fill(Color(red: 0x8B/255.0, green: 0x5C/255.0, blue: 0xF6/255.0))
                                        .frame(width: 36, height: 36)
                                        .overlay(Text(String(email.prefix(1)).uppercased()).foregroundColor(.white).bold())

                                    VStack(alignment: .leading) {
                                        Text(email).font(.subheadline.bold()).foregroundColor(.white)
                                        Text("✓ Auto-Pair Active with PC").font(.caption).foregroundColor(Color(red: 0x34/255.0, green: 0xD3/255.0, blue: 0x99/255.0))
                                    }
                                    Spacer()
                                    Button("Sign Out") { google.signOut() }
                                        .font(.caption.bold())
                                        .foregroundColor(Color(red: 0x8B/255.0, green: 0x93/255.0, blue: 0xB8/255.0))
                                }
                            } else {
                                Text("Sign in with Google so PCs on the same account connect automatically.")
                                    .font(.caption)
                                    .foregroundColor(Color(red: 0x8B/255.0, green: 0x93/255.0, blue: 0xB8/255.0))

                                Button(action: {
                                    // Trigger Google sign-in
                                }) {
                                    HStack {
                                        Spacer()
                                        Text("Sign in with Google")
                                            .font(.subheadline.bold())
                                            .foregroundColor(.white)
                                        Spacer()
                                    }
                                    .padding(.vertical, 12)
                                    .background(Color(red: 0x4F/255.0, green: 0x46/255.0, blue: 0xE5/255.0))
                                    .cornerRadius(10)
                                }
                            }
                        }
                        .padding(16)
                        .background(Color(red: 0x12/255.0, green: 0x16/255.0, blue: 0x2B/255.0))
                        .cornerRadius(14)

                        // Discovered PCs Card
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Discovered Host PCs")
                                .font(.headline)
                                .foregroundColor(.white)

                            if connection.hosts.isEmpty {
                                Text("Searching network on UDP 41173...")
                                    .font(.caption)
                                    .foregroundColor(Color(red: 0x8B/255.0, green: 0x93/255.0, blue: 0xB8/255.0))
                            } else {
                                ForEach(connection.hosts) { host in
                                    HStack {
                                        Image(systemName: "display")
                                            .foregroundColor(Color(red: 0x8B/255.0, green: 0x5C/255.0, blue: 0xF6/255.0))
                                        VStack(alignment: .leading) {
                                            Text(host.name).font(.subheadline.bold()).foregroundColor(.white)
                                            Text(host.ip).font(.caption).foregroundColor(.gray)
                                        }
                                        Spacer()
                                        Button("Connect") {
                                            connection.connectTo(host: host)
                                        }
                                        .font(.caption.bold())
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Color(red: 0x8B/255.0, green: 0x5C/255.0, blue: 0xF6/255.0))
                                        .foregroundColor(.white)
                                        .cornerRadius(8)
                                    }
                                    .padding(10)
                                    .background(Color(red: 0x1A/255.0, green: 0x1F/255.0, blue: 0x3D/255.0))
                                    .cornerRadius(8)
                                }
                            }
                        }
                        .padding(16)
                        .background(Color(red: 0x12/255.0, green: 0x16/255.0, blue: 0x2B/255.0))
                        .cornerRadius(14)

                        // Launch Drawing Canvas Button
                        Button(action: { isDrawing = true }) {
                            HStack {
                                Spacer()
                                Image(systemName: "pencil.tip")
                                Text("Open Drawing Surface")
                                    .font(.headline)
                                Spacer()
                            }
                            .foregroundColor(.white)
                            .padding(.vertical, 16)
                            .background(LinearGradient(colors: [Color(red: 0x8B/255.0, green: 0x5C/255.0, blue: 0xF6/255.0),
                                                                Color(red: 0x4F/255.0, green: 0x46/255.0, blue: 0xE5/255.0)],
                                                       startPoint: .leading, endPoint: .trailing))
                            .cornerRadius(16)
                        }
                        .padding(.top, 8)
                    }
                    .padding(20)
                }
            }
        }
        .onAppear {
            connection.startDiscovery()
        }
    }
}
