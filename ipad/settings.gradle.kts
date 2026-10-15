// Layerloom for iPad (docs/IPAD-PLAN.md in the layerloom repo): its own Gradle build, so the Mac app's build is
// untouched. Built through Xcode (iosApp/), which calls embedAndSignAppleFrameworkForXcode.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "layerloom-ipad"

// The shared rigger (psd2live's :rigger, Kotlin Multiplatform) straight from the Mac app's build: a composite build,
// so the iPad app always rigs with the same code as the desktop, with nothing published in between.
includeBuild("..") {
	dependencySubstitution {
		substitute(module("io.github.psd2live:rigger")).using(project(":rigger"))
		substitute(module("io.github.psd2live:editor")).using(project(":editor"))
	}
}
