// Export framework (MIT): target interface, capability profiles, loss reports and the lowering steps
// shared by exporters. Depends only on the neutral IR.
plugins {
	kotlin("multiplatform")
}

// The compiler's version as a Kotlin constant (common code: the iPad has no Java resources to read it from).
val writeCompilerVersion by tasks.registering {
	val output = layout.buildDirectory.dir("generated/compiler-version")
	val version = rootProject.version.toString()
	inputs.property("version", version)
	outputs.dir(output)
	doLast {
		val file = output.get().file("io/github/psd2live/format/compile/CompilerVersion.kt").asFile
		file.parentFile.mkdirs()
		file.writeText("package io.github.psd2live.format.compile\n\ninternal const val COMPILER_VERSION: String = \"$version\"\n")
	}
}

// Layerloom iPad plan, phase 2: Kotlin Multiplatform. Shared code in src/commonMain (it compiles for the iPad);
// Java-only code stays in src/main, the JVM target's.
kotlin {
	jvmToolchain(21)
	explicitApi()
	jvm()
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting {
			kotlin.srcDir(writeCompilerVersion)
			dependencies {
				api(project(":format-model"))
				implementation("com.squareup.okio:okio:3.17.0")
			}
		}
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
		}
		val jvmTest by getting {
			kotlin.srcDir("src/test/kotlin")
			resources.srcDir("src/test/resources")
			dependencies { implementation(kotlin("test")) }
		}
	}
}

// The compiler version every export records, so two runs of one file are comparable.

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the export framework tests."
	dependsOn("jvmTest")
}
