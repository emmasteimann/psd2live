package io.github.psd2live.render

import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.ui.utils.toSkiaImage
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Blender
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.VertexMode
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A `.p2lrt` rig's fixed drawing data for the Skia renderer: its meshes, parts and atlas pages as Skia images.
 * Made once per compiled rig, on the thread that loads it; the frames of that rig share it.
 */
class SkiaP2lrtModel constructor(val meshes: List<P2lRuntime.Mesh>, val parts: List<P2lRuntime.Part>, pages: List<BufferedImage>) {
	val pages: List<Image> = pages.map { it.toSkiaImage() }

	companion object {
		fun of(rig: P2lRuntime.Rig, pages: List<BufferedImage>) =
			SkiaP2lrtModel(rig.meshIds.indices.map(rig::mesh), rig.partIds.indices.map(rig::part), pages)
	}
}

/**
 * One evaluated frame of a `.p2lrt` rig, copied out of the runtime so the canvas can draw it on its own thread:
 * every mesh's vertices (canvas pixels, y down), opacity and colors, every part's composite, the render commands.
 */
class SkiaP2lrtFrame constructor(
	val model: SkiaP2lrtModel,
	val commands: IntArray,
	val vertices: Array<FloatArray?>,
	val opacity: FloatArray,
	/** Multiply then screen color per mesh, rgb each: 6 floats a mesh. */
	val colors: FloatArray,
	val partOpacity: FloatArray,
	/** Multiply then screen color per part, rgb each: 6 floats a part. */
	val partColors: FloatArray,
) {
	companion object {
		/** Copies [rig]'s last evaluation. */
		fun capture(rig: P2lRuntime.Rig, model: SkiaP2lrtModel): SkiaP2lrtFrame {
			val meshCount = model.meshes.size
			val multiply = FloatArray(3)
			val screen = FloatArray(3)
			val colors = FloatArray(meshCount * 6)
			val opacity = FloatArray(meshCount)
			val vertices = arrayOfNulls<FloatArray>(meshCount)
			for (i in 0 until meshCount) {
				opacity[i] = rig.opacity(i)
				if (!(opacity[i] > 0f)) continue
				vertices[i] = rig.vertices(i)
				rig.colors(i, multiply, screen)
				multiply.copyInto(colors, i * 6); screen.copyInto(colors, i * 6 + 3)
			}
			val partCount = model.parts.size
			val partOpacity = FloatArray(partCount)
			val partColors = FloatArray(partCount * 6)
			for (p in 0 until partCount) {
				partOpacity[p] = rig.partComposite(p, multiply, screen)
				multiply.copyInto(partColors, p * 6); screen.copyInto(partColors, p * 6 + 3)
			}
			return SkiaP2lrtFrame(model, rig.renderCommands(), vertices, opacity, colors, partOpacity, partColors)
		}
	}
}

/** A captured frame and the camera it was asked for: canvas pixel (x, y) at `x * scale + offsetX`, `y * scale + offsetY`. */
class SkiaPreviewDraw(val frame: SkiaP2lrtFrame, val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * Draws a [SkiaP2lrtFrame] with Skia by the compositing rules of `p2l_runtime.h`, as [P2lrtGlRenderer] does with
 * OpenGL, so the Preview tab plays the runtime on any Skia backend (Metal on a Mac, and an iPad later):
 *
 * - each mesh is its triangles textured from its page, its multiply and screen colors applied to the straight
 *   color, times its opacity; a culling mesh loses its back faces on the CPU;
 * - a mesh's masks keep it where a mask texel is at least half opaque (inverted: where none is), as the GL
 *   stencil does: the mesh draws into a layer that the masks then trim (DST_IN / DST_OUT);
 * - normal over and Cubism's add and multiply blend directly; every other pair of color and alpha modes draws the
 *   mesh alone into a layer and composites it through [COMPOSITE_SKSL], an SkSL port of umamo's composite shader
 *   whose spec is `BlendMath.compositeReference`;
 * - an isolated group draws into a layer composited with its modes, opacity, colors and mask coverage (its masks'
 *   largest texture alpha); a group that would composite as plain source-over draws in place.
 */
object SkiaP2lrtPainter {
	private const val BLEND_NORMAL = 0
	private const val BLEND_CUBISM_ADD = 1
	private const val BLEND_CUBISM_MULTIPLY = 2
	private const val BLEND_LAST = 17
	private const val ALPHA_OVER = 0
	private const val ALPHA_LAST = 4

	private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

	/** The texture with multiply and screen colors on its straight color, times opacity; premultiplied out. */
	private val tinted by lazy { RuntimeEffect.makeForShader("""
		uniform shader tex;
		uniform float opacity;
		uniform float3 multiplyColor;
		uniform float3 screenColor;
		half4 main(float2 p) {
			float4 c = float4(tex.eval(p));
			float3 rgb = c.a > 0.0 ? c.rgb / c.a : float3(0.0);
			rgb *= multiplyColor;
			rgb = rgb + screenColor - rgb * screenColor;
			float a = c.a * opacity;
			return half4(float4(rgb * a, a));
		}
	""".trimIndent()) }

	/** A mask's stencil: opaque where the texel is at least half opaque. */
	private val stencil by lazy { RuntimeEffect.makeForShader("""
		uniform shader tex;
		half4 main(float2 p) { return tex.eval(p).a >= 0.5 ? half4(1.0) : half4(0.0); }
	""".trimIndent()) }

	/** A mask's coverage: the texel's alpha. */
	private val coverage by lazy { RuntimeEffect.makeForShader("""
		uniform shader tex;
		half4 main(float2 p) { return half4(tex.eval(p).a); }
	""".trimIndent()) }

	/** Coverage of several masks is the largest. */
	private val maxBlender: Blender by lazy {
		RuntimeEffect.makeForBlender("half4 main(half4 src, half4 dst) { return max(src, dst); }").makeBlender(null)
	}

	/** Cubism's add: color adds, the destination keeps its alpha (GL ONE, ONE / ZERO, ONE). */
	private val cubismAdd: Blender by lazy {
		RuntimeEffect.makeForBlender("half4 main(half4 src, half4 dst) { return half4(min(src.rgb + dst.rgb, half3(1.0)), dst.a); }").makeBlender(null)
	}

	/** Cubism's multiply: GL DST_COLOR, ONE_MINUS_SRC_ALPHA / ZERO, ONE. */
	private val cubismMultiply: Blender by lazy {
		RuntimeEffect.makeForBlender("half4 main(half4 src, half4 dst) { return half4(src.rgb * dst.rgb + dst.rgb * (1.0 - src.a), dst.a); }").makeBlender(null)
	}

	private val compositeEffect by lazy { RuntimeEffect.makeForBlender(COMPOSITE_SKSL) }

	/** [compositeEffect] with its uniforms, laid out tightly in declaration order (floats, then two float3s). */
	fun compositeBlender(colorMode: Int, alphaMode: Int, opacity: Float, colors: FloatArray?, colorsAt: Int): Blender {
		val bytes = ByteBuffer.allocate(9 * 4).order(ByteOrder.LITTLE_ENDIAN)
		bytes.putFloat(colorMode.toFloat()).putFloat(alphaMode.toFloat()).putFloat(opacity)
		if (colors == null) bytes.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f)
		else for (k in 0 until 6) bytes.putFloat(colors[colorsAt + k])
		return compositeEffect.makeBlender(Data.makeFromBytes(bytes.array()))
	}

	/**
	 * Draws [frame] into [canvas]: canvas pixel (x, y) lands at `x * scale + offsetX`, `y * scale + offsetY`.
	 */
	fun draw(canvas: Canvas, frame: SkiaP2lrtFrame, scale: Float, offsetX: Float, offsetY: Float) {
		if (frame.model.meshes.isEmpty()) return
		val saved = canvas.save()
		try {
			canvas.translate(offsetX, offsetY)
			canvas.scale(scale, scale)
			val shaders = frame.model.pages.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null) }
			try {
				val commands = frame.commands
				Draw(canvas, frame, shaders).span(commands, P2lrtGlRenderer.groupEnds(commands), 0, commands.size)
			} finally { shaders.forEach { it.close() } }
		} finally { canvas.restoreToCount(saved) }
	}

	private class Draw(val canvas: Canvas, val frame: SkiaP2lrtFrame, val shaders: List<Shader>) {
		val meshes = frame.model.meshes
		val parts = frame.model.parts

		fun span(commands: IntArray, ends: IntArray, from: Int, to: Int) {
			var i = from
			while (i < to) {
				val command = commands[i]
				when {
					command >= 0 -> { mesh(command); i++ }
					command <= -2 -> { group(P2lRuntime.groupPart(command), commands, ends, i + 1, ends[i]); i = ends[i] + 1 }
					else -> i++
				}
			}
		}

		fun mesh(index: Int) {
			val mesh = meshes.getOrNull(index) ?: return
			val meshOpacity = frame.opacity[index]
			if (!(meshOpacity > 0f) || mesh.indices.isEmpty()) return
			val blend = colorMode(mesh.blend)
			val alphaBlend = alphaMode(mesh.alphaBlend)
			if (fixedFunction(blend, alphaBlend)) { colored(index, mesh, meshOpacity, blend); return }
			// The mesh alone, over nothing, then composited by its modes.
			Paint().use { layer ->
				layer.blender = compositeBlender(blend, alphaBlend, 1f, null, 0)
				canvas.saveLayer(null, layer)
				colored(index, mesh, meshOpacity, BLEND_NORMAL)
				canvas.restore()
			}
		}

		fun group(part: Int, commands: IntArray, ends: IntArray, from: Int, to: Int) {
			val info = parts.getOrNull(part) ?: return span(commands, ends, from, to)
			val groupOpacity = frame.partOpacity[part]
			if (!(groupOpacity > 0f)) return
			val blend = colorMode(info.blend)
			val alphaBlend = alphaMode(info.alphaBlend)
			if (inPlace(blend, alphaBlend, info.masks.isNotEmpty(), groupOpacity, part, commands, ends, from, to)) {
				span(commands, ends, from, to)
				return
			}
			Paint().use { layer ->
				layer.blender = compositeBlender(blend, alphaBlend, groupOpacity.coerceAtMost(1f), frame.partColors, part * 6)
				canvas.saveLayer(null, layer)
				span(commands, ends, from, to)
				if (info.masks.isNotEmpty()) trim(info.masks, info.invertMask, coverageMode = true)
				canvas.restore()
			}
		}

		/** Mirrors [P2lrtGlRenderer]'s `drawsInPlace`: plain source-over all the way down composites as drawn in place. */
		fun inPlace(blend: Int, alphaBlend: Int, masked: Boolean, groupOpacity: Float, part: Int, commands: IntArray,
					ends: IntArray, from: Int, to: Int): Boolean {
			if (blend != BLEND_NORMAL || alphaBlend != ALPHA_OVER || masked || groupOpacity < 1f) return false
			val c = frame.partColors
			val at = part * 6
			if (c[at] != 1f || c[at + 1] != 1f || c[at + 2] != 1f || c[at + 3] != 0f || c[at + 4] != 0f || c[at + 5] != 0f) return false
			var i = from
			while (i < to) {
				val command = commands[i]
				if (command >= 0) {
					val mesh = meshes.getOrNull(command)
					if (mesh != null && (colorMode(mesh.blend) != BLEND_NORMAL || alphaMode(mesh.alphaBlend) != ALPHA_OVER)) return false
					i++
				} else if (command <= -2) {
					val child = parts.getOrNull(P2lRuntime.groupPart(command))
					if (child != null && (colorMode(child.blend) != BLEND_NORMAL || alphaMode(child.alphaBlend) != ALPHA_OVER)) return false
					i = ends[i] + 1
				} else i++
			}
			return true
		}

		/** Mesh [index] with its colors and opacity, trimmed by its masks, blending by [blend]. */
		fun colored(index: Int, mesh: P2lRuntime.Mesh, meshOpacity: Float, blend: Int) {
			val shader = shaders.getOrNull(mesh.texture) ?: return
			val positions = frame.vertices[index] ?: return
			val (xy, uv) = triangles(mesh, positions, frame.model.pages[mesh.texture], cull = mesh.culling)
			if (xy.isEmpty()) return
			val masked = mesh.masks.isNotEmpty()
			val blender = when (blend) {
				BLEND_CUBISM_ADD -> cubismAdd
				BLEND_CUBISM_MULTIPLY -> cubismMultiply
				else -> null
			}
			Paint().use { paint ->
				paint.isAntiAlias = false
				val c = frame.colors
				val at = index * 6
				val neutral = c[at] == 1f && c[at + 1] == 1f && c[at + 2] == 1f && c[at + 3] == 0f && c[at + 4] == 0f && c[at + 5] == 0f
				val tint = if (neutral) null else tintedShader(shader, meshOpacity.coerceIn(0f, 1f), c, at)
				try {
					paint.shader = tint ?: shader
					if (tint == null) paint.setAlphaf(meshOpacity.coerceIn(0f, 1f))
					if (masked) {
						// The masks trim the mesh in a layer of its own; the layer then blends as the mesh would.
						Paint().use { layer ->
							if (blender != null) layer.blender = blender
							canvas.saveLayer(bounds(xy), layer)
							canvas.drawVertices(VertexMode.TRIANGLES, xy, null, uv, null, BlendMode.MODULATE, paint)
							trim(mesh.masks, mesh.invertMask, coverageMode = false)
							canvas.restore()
						}
					} else {
						if (blender != null) paint.blender = blender
						canvas.drawVertices(VertexMode.TRIANGLES, xy, null, uv, null, BlendMode.MODULATE, paint)
					}
				} finally { tint?.close() }
			}
		}

		/**
		 * Keeps what is drawn in the current layer where [masks] cover (inverted: where they do not): their stencil
		 * (texel alpha at least half) for a mesh, their largest alpha ([coverageMode]) for a group.
		 */
		fun trim(masks: IntArray, invert: Boolean, coverageMode: Boolean) {
			Paint().use { trimPaint ->
				trimPaint.blendMode = if (invert) BlendMode.DST_OUT else BlendMode.DST_IN
				canvas.saveLayer(null, trimPaint)
				Paint().use { maskPaint ->
					maskPaint.isAntiAlias = false
					if (coverageMode) maskPaint.blender = maxBlender
					for (mask in masks) {
						val maskMesh = meshes.getOrNull(mask) ?: continue
						val texture = shaders.getOrNull(maskMesh.texture) ?: continue
						val positions = frame.vertices[mask] ?: continue
						val (xy, uv) = triangles(maskMesh, positions, frame.model.pages[maskMesh.texture], cull = false)
						if (xy.isEmpty()) continue
						val effect = if (coverageMode) coverage else stencil
						effect.makeShader(null, arrayOf(texture), null).use { maskShader ->
							maskPaint.shader = maskShader
							canvas.drawVertices(VertexMode.TRIANGLES, xy, null, uv, null, BlendMode.MODULATE, maskPaint)
						}
					}
				}
				canvas.restore()
			}
		}

		fun tintedShader(texture: Shader, opacity: Float, colors: FloatArray, at: Int): Shader {
			val bytes = ByteBuffer.allocate(7 * 4).order(ByteOrder.LITTLE_ENDIAN)
			bytes.putFloat(opacity)
			for (k in 0 until 6) bytes.putFloat(colors[at + k])
			return tinted.makeShader(Data.makeFromBytes(bytes.array()), arrayOf(texture), null)
		}
	}

	/**
	 * [mesh]'s triangles unindexed (no 16-bit index limit), positions in canvas pixels and texture coordinates in
	 * page pixels; with [cull], triangles facing away are left out (front faces turn counter-clockwise on screen, as
	 * the GL renderer's clockwise front face in its flipped clip space).
	 */
	fun triangles(mesh: P2lRuntime.Mesh, positions: FloatArray, page: Image, cull: Boolean): Pair<FloatArray, FloatArray> {
		val indices = mesh.indices
		val w = page.width.toFloat()
		val h = page.height.toFloat()
		val xy = FloatArray(indices.size * 2)
		val uv = FloatArray(indices.size * 2)
		var n = 0
		var t = 0
		while (t + 2 < indices.size) {
			val a = indices[t]; val b = indices[t + 1]; val c = indices[t + 2]
			t += 3
			if (a * 2 + 1 >= positions.size || b * 2 + 1 >= positions.size || c * 2 + 1 >= positions.size) continue
			if (cull) {
				val cross = (positions[b * 2] - positions[a * 2]) * (positions[c * 2 + 1] - positions[a * 2 + 1]) -
					(positions[c * 2] - positions[a * 2]) * (positions[b * 2 + 1] - positions[a * 2 + 1])
				if (cross > 0f) continue
			}
			for (v in intArrayOf(a, b, c)) {
				xy[n] = positions[v * 2]; xy[n + 1] = positions[v * 2 + 1]
				uv[n] = mesh.uvs[v * 2] * w; uv[n + 1] = mesh.uvs[v * 2 + 1] * h
				n += 2
			}
		}
		return if (n == xy.size) xy to uv else xy.copyOf(n) to uv.copyOf(n)
	}

	private fun bounds(xy: FloatArray): Rect {
		var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
		var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
		for (i in xy.indices step 2) { l = minOf(l, xy[i]); r = maxOf(r, xy[i]); t = minOf(t, xy[i + 1]); b = maxOf(b, xy[i + 1]) }
		return Rect.makeLTRB(l - 1f, t - 1f, r + 1f, b + 1f)
	}

	private fun colorMode(mode: Int) = if (mode in 0..BLEND_LAST) mode else BLEND_NORMAL
	private fun alphaMode(mode: Int) = if (mode in 0..ALPHA_LAST) mode else ALPHA_OVER
	private fun fixedFunction(blend: Int, alphaBlend: Int) =
		blend == BLEND_CUBISM_ADD || blend == BLEND_CUBISM_MULTIPLY || (blend == BLEND_NORMAL && alphaBlend == ALPHA_OVER)

	/**
	 * umamo's composite fragment shader (`org.umamo.render.glsl.CompositeShaders`) as an SkSL blender: the layer is
	 * `src`, the destination `dst`, both premultiplied. Mask coverage is applied to the layer before it composites
	 * (the trim layers), so only opacity and the colors are uniforms here. Keep it in step with that shader and with
	 * `BlendMath.compositeReference`, the spec the test compares against.
	 */
	val COMPOSITE_SKSL = """
		uniform float colorModeF;
		uniform float alphaModeF;
		uniform float opacity;
		uniform float3 multiplyColor;
		uniform float3 screenColor;

		float lum3(float3 c) { return 0.3 * c.r + 0.59 * c.g + 0.11 * c.b; }
		float3 clipColor3(float3 c) {
			float l = lum3(c);
			float n = min(c.r, min(c.g, c.b));
			float x = max(c.r, max(c.g, c.b));
			if (n < 0.0) { c = l + (c - l) * l / (l - n); }
			if (x > 1.0) { c = l + (c - l) * (1.0 - l) / (x - l); }
			return c;
		}
		float3 setLum3(float3 c, float l) { return clipColor3(c + (l - lum3(c))); }
		float sat3(float3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
		float3 setSat3(float3 c, float s) {
			float mn = min(c.r, min(c.g, c.b));
			float mx = max(c.r, max(c.g, c.b));
			return (mx > mn) ? (c - mn) * s / (mx - mn) : float3(0.0);
		}
		float hardLightChannel(float base, float selector) {
			float doubled = 2.0 * selector - 1.0;
			return (selector <= 0.5) ? 2.0 * selector * base : base + doubled - base * doubled;
		}
		float softLightD(float x) { return (x <= 0.25) ? ((16.0 * x - 12.0) * x + 4.0) * x : sqrt(x); }
		float blendChannel(int colorMode, float b, float s) {
			if (colorMode == 1 || colorMode == 3 || colorMode == 4) { return min(1.0, b + s); }
			if (colorMode == 2 || colorMode == 6) { return b * s; }
			if (colorMode == 5) { return min(b, s); }
			if (colorMode == 7) { return (b >= 1.0) ? 1.0 : ((s <= 0.0) ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s)); }
			if (colorMode == 8) { return max(0.0, b + s - 1.0); }
			if (colorMode == 9) { return max(b, s); }
			if (colorMode == 10) { return b + s - b * s; }
			if (colorMode == 11) { return (b <= 0.0) ? 0.0 : ((s >= 1.0) ? 1.0 : min(1.0, b / (1.0 - s))); }
			if (colorMode == 12) { return hardLightChannel(s, b); }
			if (colorMode == 13) { return (s <= 0.5) ? b - (1.0 - 2.0 * s) * b * (1.0 - b) : b + (2.0 * s - 1.0) * (softLightD(b) - b); }
			if (colorMode == 14) { return hardLightChannel(b, s); }
			if (colorMode == 15) { return clamp(b + 2.0 * s - 1.0, 0.0, 1.0); }
			return s;
		}
		float3 blendColor3(int colorMode, float3 Cb, float3 Cs) {
			if (colorMode == 16) { return setLum3(setSat3(Cs, sat3(Cb)), lum3(Cb)); }
			if (colorMode == 17) { return setLum3(Cs, lum3(Cb)); }
			return float3(blendChannel(colorMode, Cb.r, Cs.r), blendChannel(colorMode, Cb.g, Cs.g), blendChannel(colorMode, Cb.b, Cs.b));
		}

		half4 main(half4 srcH, half4 dstH) {
			int colorMode = int(colorModeF + 0.5);
			int alphaMode = int(alphaModeF + 0.5);
			float4 layer = float4(srcH) * opacity;
			float4 dest = float4(dstH);
			float sa = layer.a;
			float da = dest.a;
			float3 Cs = (sa > 0.0) ? clamp(layer.rgb / sa, 0.0, 1.0) : float3(0.0);
			Cs = Cs * multiplyColor;
			Cs = Cs + screenColor - Cs * screenColor;
			float3 Cb = (da > 0.0) ? clamp(dest.rgb / da, 0.0, 1.0) : float3(0.0);
			float4 outColor;
			if (colorMode == 1 || colorMode == 2 || (alphaMode == 0 && colorMode == 0)) {
				float3 srcPremul = Cs * sa;
				if (colorMode == 0) {
					outColor = float4(srcPremul + dest.rgb * (1.0 - sa), sa + da * (1.0 - sa));
				} else if (colorMode == 1) {
					outColor = float4(srcPremul + dest.rgb, da);
				} else {
					outColor = float4(srcPremul * dest.rgb + dest.rgb * (1.0 - sa), da);
				}
			} else {
				float p0 = sa * da;
				if (alphaMode == 3) { p0 = min(sa, da); }
				else if (alphaMode == 4) { p0 = max(sa + da - 1.0, 0.0); }
				float w = (sa > 0.0) ? p0 / sa : 0.0;
				float3 mixed = (1.0 - w) * Cs + w * blendColor3(colorMode, Cb, Cs);
				float Fa;
				float Fb;
				if (alphaMode == 1) { Fa = da; Fb = 1.0 - sa; }
				else if (alphaMode == 2) { Fa = 0.0; Fb = 1.0 - sa; }
				else if (alphaMode == 3) { Fa = 1.0; Fb = (da <= 0.0 || sa >= da) ? 0.0 : 1.0 - sa / da; }
				else if (alphaMode == 4) { Fa = 1.0; Fb = (da <= 0.0) ? 0.0 : min(1.0, (1.0 - sa) / da); }
				else { Fa = 1.0; Fb = 1.0 - sa; }
				outColor = float4(sa * Fa * mixed + da * Fb * Cb, sa * Fa + da * Fb);
			}
			return half4(clamp(outColor, 0.0, 1.0));
		}
	""".trimIndent()
}
