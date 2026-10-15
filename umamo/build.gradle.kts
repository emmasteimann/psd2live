// Engine ported from Umamo (GPL-3): model, keyform evaluation, edit primitives, PSD/PNG/CMO3/MOC3
// formats and their interop. It must not depend on the PSD2Live product code.
plugins {
	kotlin("multiplatform")
	kotlin("plugin.serialization")
}

// Layerloom iPad plan (layerloom docs/IPAD-PLAN.md, phase 2): Kotlin Multiplatform. The engine's shared code is in
// src/commonMain (it compiles for the iPad too); Java-only code stays in its old folders (src/main, src/test), which
// are the JVM target's, so the desktop build sees the same module as before.
kotlin {
	jvmToolchain(21)
	jvm {
		compilerOptions {
			freeCompilerArgs.add("-Xno-optimize")
		}
	}
	// The iPad: the shared engine (src/commonMain) compiles for it; Java-only code (.cmo3, OpenGL) stays in src/main.
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting {
			dependencies {
				implementation("com.squareup.okio:okio:3.17.0")
				implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
			}
		}
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
			resources.srcDir("src/main/resources")
			dependencies {
				implementation(kotlin("reflect"))
				implementation("org.jdom:jdom:1.1.3")
				implementation("com.squareup.okio:okio:3.17.0")
				implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
				api(project.dependencies.platform("org.lwjgl:lwjgl-bom:3.4.2"))
				api("org.lwjgl:lwjgl")
				api("org.lwjgl:lwjgl-opengl")
			}
		}
		val jvmTest by getting {
			kotlin.srcDir("src/test/kotlin")
			resources.srcDir("src/test/resources")
			dependencies {
				implementation(kotlin("test"))
				// MocDefaultColorsTest hands fresh models to the official Cubism Core, when PSD2LIVE_TEST_CUBISM_CORE names it.
				implementation("net.java.dev.jna:jna:5.18.0")
			}
		}
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	maxHeapSize = "2g"
	// `--tests` names one class from either module; the other module simply has nothing to run.
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showStackTraces = true
	}
}

// Every engine test is quick.
tasks.register("quickTest") {
	group = "verification"
	description = "Runs the engine tests."
	dependsOn("jvmTest")
}
