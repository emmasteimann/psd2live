package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportsTest {
	/** Reads a stored zip back: name to bytes, checking every CRC. */
	private fun unzip(z: ByteArray): Map<String, ByteArray> {
		val out = mutableMapOf<String, ByteArray>(); var i = 0
		fun u16(o: Int) = (z[o].toInt() and 0xFF) or ((z[o + 1].toInt() and 0xFF) shl 8)
		fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
		while (u32(i) == 0x04034b50) {
			val crc = u32(i + 14); val size = u32(i + 18); val nl = u16(i + 26)
			val name = z.copyOfRange(i + 30, i + 30 + nl).decodeToString(); val data = z.copyOfRange(i + 30 + nl, i + 30 + nl + size)
			assertEquals(crc, Zip.crc32(data), name); out[name] = data; i += 30 + nl + size
		}
		assertEquals(0x02014b50, u32(i))
		return out
	}

	@Test fun crcMatchesTheStandard() { assertEquals(0xCBF43926.toInt(), Zip.crc32("123456789".encodeToByteArray())) }

	@Test fun layersZipHasEveryLayerAndAManifest() {
		val w = 20; val h = 20
		val src = pngDataUrl(encodePng(w, h, IntArray(w * h) { -1 }))
		val art = pngDataUrl(encodePng(w, h, IntArray(w * h) { if (it % w < 5) 0xFF112233.toInt() else 0 }))
		val p = StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"My Doll","source":"$src","width":$w,"height":$h,"parts":[
			{"id":"a","name":"Hair L","group":"Hair","visible":true,"mask":null,"repairs":[],"cutout":"$art","extractionMask":"$art"}],"history":[]}""")
		val (name, bytes) = StudioExports.layersZip(p)
		assertEquals("My_Doll_layers.zip", name)
		val files = unzip(bytes)
		assertEquals(setOf("00_Remaining_artwork.png", "01_Hair_L.png", "manifest.json"), files.keys)
		assertTrue(files["manifest.json"]!!.decodeToString().contains("\"layerOrder\": \"bottom-to-top\""))
		assertEquals(5 * h, decodeImage(files["01_Hair_L.png"]!!)!!.argb.count { (it ushr 24) != 0 })
		// Left for a check with the system's own unzip when this machine has the scratch folder.
		val dir = "/private/tmp/claude-502/-Users-goosible-Downloads-beautiful-failure-copy/818085c4-7ee9-433c-b85d-eff01e588b80/scratchpad".let { okio.Path.Companion.run { it.toPath() } }
		if (okio.FileSystem.SYSTEM.exists(dir)) okio.FileSystem.SYSTEM.write(dir / "export-test.zip") { write(bytes) }
	}
}
