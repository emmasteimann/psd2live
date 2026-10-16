import sys, numpy as np, torch, coremltools as ct
from spandrel import ModelLoader
from PIL import Image
src, out, tile = sys.argv[1], sys.argv[2], int(sys.argv[3])
model = ModelLoader().load_from_file(src).model.eval()
x = torch.rand(1, 3, tile, tile)
traced = torch.jit.trace(model, x)
ml = ct.convert(traced, inputs=[ct.TensorType(name="image", shape=x.shape)], outputs=[ct.TensorType(name="upscaled")],
                compute_precision=ct.precision.FLOAT16, minimum_deployment_target=ct.target.iOS17, convert_to="mlprogram")
ml.save(out)
# Check against torch on a real crop.
img = Image.open(sys.argv[4]).convert("RGB").crop((300, 300, 300 + tile, 300 + tile))
t = torch.from_numpy(np.asarray(img, dtype=np.float32) / 255).permute(2, 0, 1)[None]
with torch.no_grad(): ref = model(t).numpy()
got = ml.predict({"image": t.numpy()})["upscaled"]
print("shape", got.shape, "max abs diff", float(np.abs(got - ref).max()), "mean", float(np.abs(got - ref).mean()))
