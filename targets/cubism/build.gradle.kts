// Cubism export target (GPL-3, uses the Umamo engine): PuppetModel <-> RigIR conversion and the
// moc3/cmo3 writers behind the neutral export interface.
// Layerloom iPad plan, phase 2: Kotlin Multiplatform. Shared code in src/commonMain (it compiles for the iPad);
// Java-only code stays in src/main, the JVM target's.
plugins {
	kotlin("multiplatform")
}

kotlin {
	jvmToolchain(21)
	jvm {
		compilerOptions {
			freeCompilerArgs.add("-Xno-optimize")
		}
	}
	iosArm64()
	iosSimulatorArm64()
	sourceSets {
		val commonMain by getting {
			dependencies {
				api(project(":format-model"))
				api(project(":format-compile"))
				api(project(":umamo"))
				implementation("com.squareup.okio:okio:3.17.0")
			}
		}
		val jvmMain by getting {
			kotlin.srcDir("src/main/kotlin")
			resources.srcDir("src/main/resources")
		}
		val jvmTest by getting {
			kotlin.srcDir("src/test/kotlin")
			resources.srcDir("src/test/resources")
			dependencies { implementation(kotlin("test")) }
		}
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	maxHeapSize = "2g"
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the Cubism target tests."
	dependsOn("jvmTest")
}
