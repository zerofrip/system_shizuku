// settings.gradle
include(':external:Shizuku-API:api')
include(':external:Shizuku-API:aidl')

// build.gradle (Application module)
dependencies {
    // Depend on the Shizuku-API submodule
    implementation project(':external:Shizuku-API:api')
}

// Android.bp (System Service side)
// Ensure the AIDL from the submodule is included or referenced
java_library {
    name: "system_shizuku_aidl",
    srcs: [
        "aidl/**/*.aidl",
        "external/Shizuku-API/aidl/src/main/aidl/**/*.aidl",
    ],
    aidl: {
        include_dirs: [
            "aidl",
            "external/Shizuku-API/aidl/src/main/aidl",
        ],
    },
    sdk_version: "system_current",
}
