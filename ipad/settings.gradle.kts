// Layerloom for iPad (docs/IPAD-PLAN.md in the layerloom repo): its own Gradle build, so the Mac app's build is
// untouched. Built through Xcode (iosApp/), which calls embedAndSignAppleFrameworkForXcode.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "layerloom-ipad"
