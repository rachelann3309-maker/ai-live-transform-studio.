import io, os
from typing import Optional
from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.responses import Response
from PIL import Image
import torch
from diffusers import StableDiffusionXLImg2ImgPipeline

app = FastAPI(title="AI Live Transform GPU Backend")
pipe = None

@app.on_event("startup")
def load_model():
    global pipe
    model_id = os.getenv("SDXL_MODEL", "stabilityai/stable-diffusion-xl-base-1.0")
    if not torch.cuda.is_available():
        print("WARNING: CUDA GPU not found. /transform will return 503.")
        return
    pipe = StableDiffusionXLImg2ImgPipeline.from_pretrained(
        model_id, torch_dtype=torch.float16, variant="fp16", use_safetensors=True
    ).to("cuda")
    pipe.enable_vae_slicing()
    pipe.enable_attention_slicing()

@app.get("/health")
def health():
    return {"ok": True, "gpu_loaded": pipe is not None, "mode": "single-frame SDXL img2img prototype"}

@app.post("/transform")
async def transform(
    prompt: str = Form(...),
    image: UploadFile = File(...),
    reference: Optional[UploadFile] = File(None),
):
    if pipe is None:
        raise HTTPException(503, "No CUDA GPU/model loaded. Deploy this backend on a CUDA GPU host.")
    try:
        source = Image.open(io.BytesIO(await image.read())).convert("RGB")
        # SDXL img2img keeps source composition; strength controls how far it changes.
        source.thumbnail((1024, 1024))
        result = pipe(
            prompt=prompt,
            image=source,
            strength=float(os.getenv("IMG2IMG_STRENGTH", "0.48")),
            guidance_scale=float(os.getenv("GUIDANCE_SCALE", "7.0")),
            num_inference_steps=int(os.getenv("STEPS", "25")),
        ).images[0]
        out = io.BytesIO()
        result.save(out, format="JPEG", quality=92)
        return Response(out.getvalue(), media_type="image/jpeg")
    except Exception as exc:
        raise HTTPException(500, f"Generation failed: {exc}")
