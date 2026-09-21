// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DashboardiOS",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "Dashboard",
            targets: ["Dashboard"]),
    ],
    targets: [
        .target(
            name: "Dashboard",
            dependencies: [],
            path: "Sources/Dashboard"
        ),
    ]
)
