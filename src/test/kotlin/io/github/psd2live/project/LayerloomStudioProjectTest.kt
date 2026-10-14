package io.github.psd2live.project

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Layerloom patch (ONE-APP-PLAN S3a): a project's Layerloom studio project rides in its .psd2live file. */
class LayerloomStudioProjectTest {
	@Test
	fun studioProjectSurvivesSaveAndReopen() = kotlinx.coroutines.runBlocking<Unit> {
		val temp = Files.createTempDirectory("studio-project")
		val fixture = temp.resolve("v1-sample.psd2live")
		javaClass.getResourceAsStream("/projects/v1-sample.psd2live")!!.use { Files.copy(it, fixture) }
		val saved = temp.resolve("with-art.psd2live")
		val art = """{"format":"layerloom-project","version":1,"name":"Quill","parts":[]}"""
		ProjectRepository().open(fixture).use { project ->
			val file = project.store.projectRoot(project.projectId).resolve("layerloom/project.json")
			Files.createDirectories(file.parent); Files.writeString(file, art)
			ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture(project.projectId, project.history.state(), project.presentation,
				project.source, project.store), saved)
		}
		ProjectRepository().open(saved).use { project ->
			assertEquals(art, Files.readString(project.store.projectRoot(project.projectId).resolve("layerloom/project.json")))
		}
	}
}
