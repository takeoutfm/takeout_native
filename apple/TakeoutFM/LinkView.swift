import SwiftUI
import Shared

/**
 * Link a device (SIGN-11): approve the code another device shows (a TV, a new phone) with this
 * account, as the server's /link page and the phone's Link screen do. The password is never
 * kept: it's cleared once the code is approved, and when the sheet closes.
 */
struct LinkView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var user = ""
    @State private var password = ""
    @State private var passcode = ""
    @State private var busy = false
    /// The result to show, and whether it's good news.
    @State private var message: (text: String, ok: Bool)?

    private var ready: Bool {
        !code.trimmingCharacters(in: .whitespaces).isEmpty
            && !user.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && !busy
    }

    var body: some View {
        Form {
            Section {
                Text("Enter the code shown on the TV or other device, with your account to approve it.")
                    .foregroundStyle(.secondary)
                TextField("Code", text: $code)
                    .font(.title3.monospaced())
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    #endif
                    .autocorrectionDisabled()
                TextField("User", text: $user)
                    .textContentType(.username)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
                TextField("Passcode (only with two-factor codes)", text: $passcode)
                    .textContentType(.oneTimeCode)
                    .onSubmit(link)
            } footer: {
                Text("Approved on \(model.endpoint).")
            }
            Section {
                Button(action: link) {
                    HStack {
                        Text("Link")
                        if busy { ProgressView().controlSize(.small) }
                    }
                }
                .disabled(!ready)
                .keyboardShortcut(.defaultAction)
                if let message {
                    Label(message.text, systemImage: message.ok ? "checkmark.circle" : "exclamationmark.triangle")
                        .foregroundStyle(message.ok ? Color.green : Color.red)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Link a Device")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .onAppear { if user.isEmpty { user = model.serverUser ?? "" } }
        .onDisappear { password = "" }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 380)
        #endif
    }

    private func link() {
        guard ready else { return }
        busy = true
        message = nil
        Task {
            do {
                let accepted = try await model.calls.link(code: code, user: user.trimmingCharacters(in: .whitespaces),
                                                          password: password, passcode: passcode).boolValue
                if accepted {
                    message = ("Linked. The other device signs in now.", true)
                    code = ""
                    password = ""
                    passcode = ""
                } else {
                    message = ("That code, user name or password wasn't accepted.", false)
                }
            } catch {
                message = ("Can't reach \(model.endpoint). Check your connection.", false)
            }
            busy = false
        }
    }
}
