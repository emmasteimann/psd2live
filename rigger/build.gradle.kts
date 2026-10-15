// The rigger: PSD in, rig out (analysis, meshes, deformers, physics, the rig IR compiler) and the project storage
// it builds on - split out of the editor app so it can compile for the iPad (Layerloom iPad plan, phase 2).
// It must not depend on the editor's UI. Kotlin Multiplatform: shared code in src/commonMain (JVM and iPad), Java-only code in src/main (JVM).
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
	// The iPad: the shared rigger (src/commonMain) compiles for it; Java-only code stays in src/main.
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting {
			dependencies {
				api(project(":umamo"))
				api(project(":format-model"))
				api(project(":format-compile"))
				api(project(":targets:cubism"))
				api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
				api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
				implementation("com.squareup.okio:okio:3.17.0")
				implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
			}
		}
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
			resources.srcDir("src/main/resources")
		}
		val jvmTest by getting {
			kotlin.srcDir("src/test/kotlin")
			dependencies { implementation(kotlin("test")) }
		}
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}
