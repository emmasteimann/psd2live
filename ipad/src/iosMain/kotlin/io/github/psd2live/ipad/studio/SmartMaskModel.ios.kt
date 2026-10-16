package io.github.psd2live.ipad.studio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreML.MLComputeUnitsAll
import platform.CoreML.MLComputeUnitsCPUOnly
import platform.CoreML.MLDictionaryFeatureProvider
import platform.CoreML.MLFeatureProviderProtocol
import platform.CoreML.MLFeatureValue
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.CoreML.MLMultiArray
import platform.CoreML.MLMultiArrayDataTypeFloat16
import platform.CoreML.MLMultiArrayDataTypeFloat32
import platform.CoreML.create
import platform.CoreML.getBytesWithHandler
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
actual object SmartMaskModel {
	private var dir: String? = null
	actual fun useModelsAt(dir: String) { this.dir = dir }
	actual var info = ""
	private val simulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

	private fun load(name: String, cpu: Boolean): MLModel {
		val url = dir?.let { NSURL.fileURLWithPath("$it/$name.mlmodelc") } ?: NSBundle.mainBundle.URLForResource(name, withExtension = "mlmodelc")
			?: error("The smart mask model isn't in the app (run ipad/scripts/fetch-sam.sh, then deploy again).")
		val config = MLModelConfiguration().apply { computeUnits = if (cpu || simulator) MLComputeUnitsCPUOnly else MLComputeUnitsAll }
		return MLModel.modelWithContentsOfURL(url, configuration = config, error = null) ?: error("The smart mask model couldn't be loaded.")
	}
	private val encoder by lazy { load("SAM2_1TinyImageEncoderFLOAT16", false) }
	private val prompt by lazy { load("SAM2_1TinyPromptEncoderFLOAT16", false) }
	private var decoder: MLModel? = null
	private var embedded: MLFeatureProviderProtocol? = null

	actual fun encode(argb: IntArray) {
		val side = 1024
		memScoped {
			val ref = alloc<CVPixelBufferRefVar>()
			CVPixelBufferCreate(kCFAllocatorDefault, side.toULong(), side.toULong(), kCVPixelFormatType_32BGRA, null, ref.ptr)
			val pb = ref.value ?: error("No memory for the picture.")
			CVPixelBufferLockBaseAddress(pb, 0u)
			val base = CVPixelBufferGetBaseAddress(pb)!!.reinterpret<UByteVar>(); val row = CVPixelBufferGetBytesPerRow(pb).toInt()
			for (y in 0 until side) for (x in 0 until side) {
				val p = argb[y * side + x]; val o = y * row + x * 4
				base[o] = (p and 0xFF).toUByte(); base[o + 1] = ((p ushr 8) and 0xFF).toUByte(); base[o + 2] = ((p ushr 16) and 0xFF).toUByte(); base[o + 3] = 255u
			}
			CVPixelBufferUnlockBaseAddress(pb, 0u)
			val input = MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>("image" to MLFeatureValue.featureValueWithPixelBuffer(pb)), error = null) ?: error("Bad picture input.")
			embedded = encoder.predictionFromFeatures(input, error = null) ?: error("The smart mask couldn't read the picture.")
			CVPixelBufferRelease(pb)
		}
	}

	private fun array(values: FloatArray, shape: List<Int>): MLMultiArray {
		val p = nativeHeap.allocArray<FloatVar>(values.size)
		for (i in values.indices) p[i] = values[i]
		val strides = shape.indices.map { i -> shape.drop(i + 1).fold(1) { a, b -> a * b } }
		return MLMultiArray.create(p, shape.map { NSNumber(int = it) }, MLMultiArrayDataTypeFloat32, strides.map { NSNumber(int = it) }, { ptr -> nativeHeap.free(ptr!!.rawValue) }, null)
			?: error("No memory for the points.")
	}

	actual fun predict(points: FloatArray, labels: FloatArray): Pair<FloatArray, FloatArray> {
		val e = embedded ?: error("Read the picture first.")
		val n = labels.size
		val pin = MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>(
			"points" to MLFeatureValue.featureValueWithMultiArray(array(points, listOf(1, n, 2))),
			"labels" to MLFeatureValue.featureValueWithMultiArray(array(labels, listOf(1, n)))), error = null) ?: error("Bad points.")
		val p = prompt.predictionFromFeatures(pin, error = null) ?: error("The smart mask couldn't read the points.")
		fun v(src: MLFeatureProviderProtocol, name: String) = src.featureValueForName(name)!!
		val din = MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>(
			"image_embedding" to v(e, "image_embedding"), "feats_s0" to v(e, "feats_s0"), "feats_s1" to v(e, "feats_s1"),
			"sparse_embedding" to v(p, "sparse_embeddings"), "dense_embedding" to v(p, "dense_embeddings")), error = null) ?: error("Bad mask input.")
		// The decoder's GPU path can refuse a new point count; the CPU always answers.
		val out = (decoder ?: load("SAM2_1TinyMaskDecoderFLOAT16", false).also { decoder = it }).predictionFromFeatures(din, error = null)
			?: load("SAM2_1TinyMaskDecoderFLOAT16", true).also { decoder = it }.predictionFromFeatures(din, error = null)
			?: error("The smart mask couldn't make masks.")
		return read(out.featureValueForName("low_res_masks")!!.multiArrayValue!!) to read(out.featureValueForName("scores")!!.multiArrayValue!!)
	}

	/** Any rank-2..4 array in logical (row-major) order, float16 or float32. */
	private fun read(a: MLMultiArray): FloatArray {
		val shape = a.shape.map { (it as NSNumber).intValue }; val strides = a.strides.map { (it as NSNumber).intValue }
		val total = shape.fold(1) { x, y -> x * y }; val out = FloatArray(total)
		val f16 = a.dataType == MLMultiArrayDataTypeFloat16
		a.getBytesWithHandler { ptr, _ ->
			val h = ptr!!.reinterpret<UShortVar>(); val f = ptr.reinterpret<FloatVar>()
			for (i in 0 until total) {
				var rem = i; var off = 0
				for (d in shape.indices.reversed()) { off += (rem % shape[d]) * strides[d]; rem /= shape[d] }
				out[i] = if (f16) half(h[off].toInt()) else f[off]
			}
		}
		return out
	}

	private fun half(h: Int): Float {
		val s = (h shr 15) and 1; val e = (h shr 10) and 0x1F; val m = h and 0x3FF
		var v = when (e) { 0 -> m / 1024f / 16384f; 31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN; else -> (1 + m / 1024f) }
		if (e in 1..30) { var k = e - 15; while (k > 0) { v *= 2; k-- }; while (k < 0) { v /= 2; k++ } }
		return if (s == 1) -v else v
	}
	@Suppress("unused") private val unused = MLMultiArrayDataTypeFloat32
}
