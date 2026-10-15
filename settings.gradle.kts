pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		google()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	repositories {
		mavenCentral()
		google()
		// Layerloom patch: KCEF (the Prepare workspace's embedded browser) pulls JOGL from JogAmp's repository.
		maven("https://jogamp.org/deployment/maven")
	}
}

rootProject.name = "psd2live"

include(":umamo")
// Layerloom iPad plan, phase 2: the rigger, split out of the editor app.
include(":rigger")
include(":format-model")
include(":format-compile")
include(":targets:cubism")
include(":targets:raster")
include(":targets:psd")
include(":targets:spine")
include(":targets:runtime")
include(":format-eval")
include(":targets:web")
include(":targets:gltf")
include(":targets:dragonbones")
