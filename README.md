# AI Live Transform Studio — Android prototype

This is a real Android Studio/Gradle project, not a mockup.

## What works in this first build
- Android front-camera preview (CameraX).
- Upload a reference/character image from the phone gallery.
- Enter a text prompt.
- Capture one camera frame and send it to a GPU backend.
- Display the generated frame returned by the backend.
- GitHub Actions workflow builds a debug APK.

## Important current limitations
This is a **single-frame cloud transformation prototype**, not yet continuous real-time video-to-video. The uploaded reference image is accepted by the API but the included SDXL img2img baseline does not yet use it for identity/character conditioning. Continuous temporal-consistent video, full-body identity transfer, object masks/inpainting, audio preservation, and RTMP streaming are later engineering stages. Do not advertise this prototype as delivering those capabilities.

SDXL generation is GPU-intensive and commonly takes several seconds or longer per frame. It cannot deliver high-FPS live transformation as configured here. For production-quality live use, add a dedicated real-time video diffusion/streaming provider, temporal consistency, tracking, frame interpolation, and a streaming compositor.

## Build an APK using only an Android phone
1. Install/open a mobile browser and sign in to GitHub.
2. Create a new **public or private repository** named `ai-live-transform-studio`.
3. Upload the contents of this project ZIP to the repository root. GitHub mobile web may require uploading files in batches; GitHub's web upload page accepts multiple selected files. Keep hidden folders/files such as `.github` included.
4. Open the repository's **Actions** tab. Enable Actions if GitHub asks.
5. Select **Build Android APK** and tap **Run workflow**. Alternatively, pushing to `main` triggers a build.
6. When the workflow completes, open its run, scroll to **Artifacts**, and download `AI-Live-Transform-debug-apk`.
7. Extract the downloaded ZIP on your phone. Tap `app-debug.apk` and allow your browser/file manager to install apps from that source if Android asks.
8. Open **AI Live Transform**.

No laptop is required. GitHub performs the Android build in the cloud.

## Configure the GPU backend
The APK expects a server exposing:
- `GET /health`
- `POST /transform` as multipart form data with `prompt`, `image`, and optional `reference`; returns JPEG bytes.

The included `backend/` is a starter FastAPI + SDXL img2img implementation. Deploy it on a CUDA GPU machine/container, not on the Android phone. For a quick backend smoke test:
```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```
This requires a compatible NVIDIA CUDA GPU and model access. Put the resulting HTTPS base URL in the app's GPU server URL field. Do not expose an unauthenticated GPU endpoint publicly; add authentication, rate limits, and upload limits before real use.

## Next development milestones
1. Reference-conditioned character identity using IP-Adapter/InstantID or a licensed identity model.
2. Segmentation masks and object inpainting.
3. Continuous camera frame pipeline with bounded queue and adaptive frame rate.
4. Temporal consistency / optical-flow propagation to reduce flicker.
5. Audio-preserving encoder and RTMP output.
6. Thermal/battery controls and device-specific performance tuning.

## Project layout
- `app/` Android Kotlin application
- `backend/` GPU inference API
- `.github/workflows/build-apk.yml` cloud APK build workflow
