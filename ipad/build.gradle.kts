plugins {
	kotlin("multiplatform") version "2.4.10"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
}

kotlin {
	listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
		target.binaries.framework {
			baseName = "LayerloomKit"
			isStatic = true
		}
	}
	sourceSets {
		commonMain.dependencies {
			implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
			implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
			implementation("org.jetbrains.compose.ui:ui:1.11.1")
			implementation("org.jetbrains.compose.material:material:1.11.1")
		}
	}
}
