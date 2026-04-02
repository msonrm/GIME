// swift-tools-version: 6.1
import PackageDescription
import AppleProductTypes

let package = Package(
    name: "GIME",
    platforms: [.iOS("18.0")],
    products: [
        .iOSApplication(
            name: "GIME",
            targets: ["GIME"],
            bundleIdentifier: "com.msonrm.GIME",
            teamIdentifier: "",
            displayVersion: "1.0",
            bundleVersion: "1",
            appIcon: .placeholder(icon: .gamecontroller),
            accentColor: .presetColor(.blue),
            supportedDeviceFamilies: [.pad],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeRight,
                .landscapeLeft,
                .portraitUpsideDown(.when(deviceFamilies: [.pad]))
            ]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/msonrm/KeyLogicKit", from: "0.1.0"),
    ],
    targets: [
        .executableTarget(
            name: "GIME",
            dependencies: [
                .product(name: "KeyLogicKit", package: "KeyLogicKit")
            ],
            path: "Sources/GIME",
            resources: [
                .copy("Resources/PrivacyInfo.xcprivacy"),
                .copy("Resources/pinyin_abbrev.json")
            ]
        )
    ]
)
