package io.github.psd2live.ipad.studio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import platform.CoreML.create
import platform.CoreML.getBytesWithHandler
import platform.CoreML.MLDictionaryFeatureProvider
import platform.CoreML.MLFeatureValue
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.CoreML.MLMultiArray
import platform.CoreML.MLMultiArrayDataTypeFloat16
import platform.CoreML.MLMultiArrayDataTypeFloat32
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
actual object AnimeUpscaler {
	actual val tile = 288
	actual val factor = 4
	private var path: String? = null
	// The simulator's GPU path returns zeros for this float16 model; there it runs on the CPU. A real iPad uses
	// everything (GPU / Neural Engine).
	private val units: Long = if (platform.Foundation.NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null)
		platform.CoreML.MLComputeUnitsCPUOnly else platform.CoreML.MLComputeUnitsAll
	actual fun useModelAt(path: String) { this.path = path }
	private val model: MLModel by lazy {
		val url = path?.let { platform.Foundation.NSURL.fileURLWithPath(it) } ?: NSBundle.mainBundle.URLForResource("AnimeUpscaler", withExtension = "mlmodelc") ?: error("The upscaler model isn't in the app.")
		MLModel.modelWithContentsOfURL(url, configuration = MLModelConfiguration().apply { computeUnits = units }, error = null) ?: error("The upscaler model couldn't be loaded.")
	}

	actual fun run(input: FloatArray): FloatArray {
		val shape = listOf(1, 3, tile, tile).map { NSNumber(int = it) }
		val p = nativeHeap.allocArray<FloatVar>(input.size)
		for (i in input.indices) p[i] = input[i]
		val strides = listOf(3 * tile * tile, tile * tile, tile, 1).map { NSNumber(int = it) }
		val array = MLMultiArray.create(p, shape, MLMultiArrayDataTypeFloat32, strides, { ptr -> nativeHeap.free(ptr!!.rawValue) }, null)
			?: run { nativeHeap.free(p.rawValue); error("No memory for the upscaler input.") }
		val features = MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>("image" to MLFeatureValue.featureValueWithMultiArray(array)), error = null) ?: error("Bad upscaler input.")
		val result = model.predictionFromFeatures(features, error = null) ?: error("The upscaler failed on a tile.")
		val out = result.featureValueForName("upscaled")?.multiArrayValue ?: error("The upscaler returned nothing.")
		val o = tile * factor
		val outStrides = out.strides.map { (it as NSNumber).intValue }
		val res = FloatArray(3 * o * o)
		// Read through getBytesWithHandler: Core ML's float16 outputs can live in a GPU surface the raw pointer doesn't see.
		val f16 = out.dataType == MLMultiArrayDataTypeFloat16
		out.getBytesWithHandler { ptr, _ ->
			if (f16) {
				val q = ptr!!.reinterpret<UShortVar>()
				for (c in 0 until 3) for (y in 0 until o) for (x in 0 until o) res[(c * o + y) * o + x] = half(q[c * outStrides[1] + y * outStrides[2] + x * outStrides[3]].toInt())
			} else {
				val q = ptr!!.reinterpret<FloatVar>()
				for (c in 0 until 3) for (y in 0 until o) for (x in 0 until o) res[(c * o + y) * o + x] = q[c * outStrides[1] + y * outStrides[2] + x * outStrides[3]]
			}
		}
		var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; for (v in res) { if (v < mn) mn = v; if (v > mx) mx = v }
		var imn = Float.MAX_VALUE; var imx = -Float.MAX_VALUE; for (v in input) { if (v < imn) imn = v; if (v > imx) imx = v }
		lastInfo = "type ${out.dataType} shape ${out.shape} strides $outStrides in [$imn, $imx] out [$mn, $mx]"
		return res
	}
	actual var lastInfo = ""

	private fun half(h: Int): Float {
		val s = (h shr 15) and 1; val e = (h shr 10) and 0x1F; val m = h and 0x3FF
		val v = when (e) { 0 -> m / 1024f * (1f / 16384f); 31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN; else -> (1 + m / 1024f) * Math.pow2(e - 15) }
		return if (s == 1) -v else v
	}
	private object Math { fun pow2(n: Int): Float { var r = 1f; if (n >= 0) repeat(n) { r *= 2 } else repeat(-n) { r /= 2 }; return r } }
}
