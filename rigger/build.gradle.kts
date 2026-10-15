// The rigger: PSD in, rig out (analysis, meshes, deformers, physics, the rig IR compiler) and the project storage
// it builds on - split out of the editor app so it can compile for the iPad (Layerloom iPad plan, phase 2).
// It must not depend on the editor's UI. Kotlin Multiplatform with a JVM target; iOS as its code moves to common.
plugins {
	kotlin("multiplatform")
	kotlin("plugin.serialization")
}

kotlin {
	jvmToolchain(21)
	jvm {
		compilerOptions {
			freeCompilerArgs.add("-Xno-optimize")
		}
	}
	sourceSets {
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
			resources.srcDir("src/main/resources")
			dependencies {
				api(project(":umamo"))
				api(project(":format-model"))
				api(project(":format-compile"))
				api(project(":targets:cubism"))
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
				api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
			}
		}
	}
}
