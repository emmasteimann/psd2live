// The editor: workspace sessions, editing commands, the view model and the Compose UI - split out of the desktop app
// so it can come to the iPad (Layerloom iPad plan, phase 5). The root project keeps the launcher and packaging.
// Kotlin Multiplatform: shared code in src/commonMain (desktop and iPad), desktop-only code in src/main.
plugins {
	kotlin("multiplatform")
	kotlin("plugin.serialization")
	id("org.jetbrains.compose")
	id("org.jetbrains.kotlin.plugin.compose")
}

val hostOs = System.getProperty("os.name").lowercase().let { os ->
	when {
		os.contains("mac") || os.contains("darwin") -> "macos"
		os.contains("linux") || os.contains("nix") -> "linux"
		else -> "windows"
	}
}
val hostArm = System.getProperty("os.arch").lowercase().let { it.startsWith("aarch64") || it.startsWith("arm") }

kotlin {
	jvmToolchain(21)
	jvm {
		compilerOptions {
			// As the app: Kotlin's redundant-null-check pass can spend minutes on the large Compose/editor methods.
			freeCompilerArgs.add("-Xno-optimize")
		}
	}
	// The iPad: the shared editor (src/commonMain) compiles for it; desktop-only code stays in src/main.
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting {
			dependencies {
				api(project(":umamo"))
				api(project(":rigger"))
				api(project(":targets:cubism"))
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
				api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
				api("org.jetbrains.compose.runtime:runtime:1.11.1")
				api("org.jetbrains.compose.foundation:foundation:1.11.1")
				api("org.jetbrains.compose.ui:ui:1.11.1")
				api("org.jetbrains.compose.material:material:1.11.1")
				implementation("com.squareup.okio:okio:3.17.0")
			}
		}
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
			resources.srcDir("src/main/resources")
			dependencies {
				api(project.dependencies.platform("io.ktor:ktor-bom:3.5.1"))
				api(project(":umamo"))
				api(project(":rigger"))
				api(project(":targets:cubism"))
				api(project(":targets:raster"))
				api(project(":targets:psd"))
				api(project(":targets:spine"))
				api(project(":targets:runtime"))
				api(project(":format-eval"))
				api(project(":targets:web"))
				api(project(":targets:gltf"))
				api(project(":targets:dragonbones"))
				val lwjglNatives = "natives-$hostOs" + if (hostArm) "-arm64" else ""
				api(project.dependencies.platform("org.lwjgl:lwjgl-bom:3.4.2"))
				api("org.lwjgl:lwjgl")
				api("org.lwjgl:lwjgl-opengl")
				api("org.lwjgl:lwjgl-glfw")
				runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
				api("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
				api("io.ktor:ktor-server-cio")
				api("io.ktor:ktor-server-auth")
				api("io.ktor:ktor-server-content-negotiation")
				api("io.ktor:ktor-server-sse")
				api("io.ktor:ktor-serialization-kotlinx-json")
				api("net.java.dev.jna:jna:5.18.0")
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
				api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
				api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
				compileOnly("dev.datlag:kcef:2025.03.23")
				api(compose.desktop.currentOs)
				api("org.jetbrains.compose.runtime:runtime:1.11.1")
				api("org.jetbrains.compose.foundation:foundation:1.11.1")
				api("org.jetbrains.compose.ui:ui:1.11.1")
				api("org.jetbrains.compose.material:material:1.11.1")
			}
		}
		val commonTest by getting {
			dependencies { implementation(kotlin("test")) }
		}
	}
}
