// The rig IR's data model. Layerloom iPad plan, phase 2: Kotlin Multiplatform, all of it common code (it compiles
// for the iPad); the JVM target keeps the desktop build seeing the same module.
plugins {
	kotlin("multiplatform")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
	jvm()
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting { kotlin.srcDir("src/main/kotlin") }
		val jvmTest by getting {
			kotlin.srcDir("src/test/kotlin")
			dependencies { implementation(kotlin("test")) }
		}
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the IR tests."
	dependsOn("jvmTest")
}
