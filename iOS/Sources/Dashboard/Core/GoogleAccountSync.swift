import Foundation
import AuthenticationServices

public struct GoogleConfig {
    public static let clientId = "YOUR_GOOGLE_CLIENT_ID_HERE"
    public static let clientSecret = "YOUR_GOOGLE_CLIENT_SECRET_HERE"
    public static let redirectUri = "http://localhost:28314/"
}

@MainActor
public class GoogleAccountSync: ObservableObject {
    @Published public var signedIn: Bool = false
    @Published public var email: String? = nil
    @Published public var displayName: String? = nil

    private var accessToken: String = ""

    public init() {
        self.email = UserDefaults.standard.string(forKey: "dashboard_google_email")
        self.accessToken = UserDefaults.standard.string(forKey: "dashboard_google_token") ?? ""
        self.signedIn = !accessToken.isEmpty && email != nil
    }

    public func getAccessToken() -> String {
        return accessToken
    }

    public func signOut() {
        accessToken = ""
        email = nil
        displayName = nil
        signedIn = false
        UserDefaults.standard.removeObject(forKey: "dashboard_google_email")
        UserDefaults.standard.removeObject(forKey: "dashboard_google_token")
    }

    public func setAccount(email: String, token: String, name: String = "") {
        self.email = email
        self.accessToken = token
        self.displayName = name.isEmpty ? email.components(separatedBy: "@").first : name
        self.signedIn = true
        UserDefaults.standard.set(email, forKey: "dashboard_google_email")
        UserDefaults.standard.set(token, forKey: "dashboard_google_token")
    }
}
