# Texture upscaling

[Documentation](../../README.md) · [中文](../../zh/guide/TEXTURE_UPSCALE.md) · [User quick reference](USER_GUIDE.md) · [Development and CLI](DEVELOPMENT.md)

This matches lesson 19 of the in-app beginner path. Upscaling enlarges textures layer by layer before atlas packing and is off by default; it does not change the source PSD, the canvas size or the bindings. Preview and export use the processed textures.

## Setup and use

Open **Model → Texture Upscale…** (default `Ctrl+U`) and configure:

| Field | What to enter |
| --- | --- |
| Python | The Python executable with the nunif dependencies installed, not a command string with arguments |
| nunif directory | The local source directory containing the `nunif` and `waifu2x` subdirectories |
| Model directory | The directory that actually holds the Art `.pth` weights; the app can detect the `swin_unet_v3/art` and `swin_unet/art` paths |
| Scale | 1 is off; 2× or 4× enlarges |
| Denoise | -1 only enlarges; 0–3 are the denoise levels the model in use supports, default 1 |
| Neural Alpha | On by default; off, alpha is enlarged bilinearly |
| Input tile | 64–512, default 256; reduce it when video memory runs short |

Prepare a local Python, PyTorch, nunif and matching weights before applying. For the installation requirements of a particular upstream version, consult the instructions shipped with that version; release packages do not include a complete inference environment. Applying rebuilds the preview, and the settings are saved with the project; on another computer, check the local paths again.

4× prefers native 4× weights; without them, the current worker can run a supported 2× model twice. A missing matching model is an error; plain interpolation is never passed off as super-resolution.

## CLI example

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/upscaled-model --upscale 2 --upscale-python C:/venv/Scripts/python.exe --nunif-dir C:/nunif --upscale-model C:/nunif/waifu2x/pretrained_models/swin_unet_v3/art --upscale-tile 256"
```

Turn off neural alpha with `--no-upscale-neural-alpha`. For the other options see the [CLI table](DEVELOPMENT.md). Replace the example paths with paths on your machine; quote paths with spaces as the launcher requires.

## Results and resources

RGB and alpha are processed separately, and colour is extended into transparent areas to reduce contamination at sampled edges; neural alpha can still change semi-transparent outlines. Check hair strands, eyelashes, lip lines and mask edges in motion against both light and dark backgrounds.

Inference runs layer by layer, one at a time, and output and atlas packing also take system memory. The cache is in `.psd2live/cache/upscale` in the user directory and is keyed by the material, settings, code and weights; it can be cleared after the app exits. For model errors, size limits or timeouts, check the log rather than just enlarging the atlas and retrying.

Implementation: [settings and process management](../../../src/main/kotlin/io/github/psd2live/core/TextureUpscale.kt), [nunif worker](../../../src/main/resources/upscale/nunif_worker.py).
