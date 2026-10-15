import SwiftUI
import UIKit
import LayerloomKit

// Layerloom for iPad: the Swift side only hosts the Kotlin app (ipad/src), which is the same code as the Mac app.
@main
struct LayerloomIPad: App {
    var body: some Scene {
        WindowGroup {
            KotlinRoot().ignoresSafeArea()
        }
    }
}

struct KotlinRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
