# Layout manifest

[Documentation](../../README.md) · [中文](../../zh/spec/LAYOUT_MANIFEST.md) · [Materials and Layout implementation](../../zh/spec/MATERIAL_LAYOUT_WORKSPACE.md) (Chinese) · [User guide](../guide/LAYOUT.md) · [PSD artwork and naming](PSD_LAYER_SPEC.md)

A layout manifest is the exchange format PSD2Live reads layered artwork from: a JSON file stating which images make up a canvas — each image's path and pixel size, its rectangle on the canvas, stacking order, group, semantic tags, variant, visibility, opacity, and whether it is only a reference. Any tool can write a manifest following this page; PSD2Live uses it to create a Layout project that is not generated yet, or imports it as materials.

Unlike a PSD, an image's pixel count need not equal its size on the canvas: small parts can carry more pixels than the canvas gives them, and PSD2Live keeps them as [per-layer sizes](../../zh/spec/DOCUMENT_LAYER.md#逐层尺寸) (Chinese), so textures are denser.

## Files and archives

- A manifest is a UTF-8 JSON object with extension `.json`, no larger than 16 MiB. Image paths are relative to the folder holding the manifest.
- The manifest and images can also be packed into a `.zip`: the manifest sits at the archive root, or in the archive's only top-level folder (common when a folder is zipped directly). PSD2Live finds the JSON that declares `format` at that level, and image paths are relative to it; two manifests at the same level are refused. The `__MACOSX/` and `._*` entries macOS adds do not count as content.
- Images can be any format PSD2Live decodes (PNG, WebP, JPEG, BMP, TIFF); PNG with non-premultiplied alpha is recommended. Each image is at most 16 MP and each image file at most 1 GiB; the images together have no limit of their own and, as with a PSD, are bounded only by the memory the decoded layers take (images are read and decoded one at a time, so their raw bytes are not held together). There are at most 4096 layers. The same image can be referenced by several layers.

## Structure

```json
{
  "format": "psd2live-layout",
  "version": 1,
  "name": "character",
  "canvas": { "width": 3258, "height": 4344 },
  "groups": [
    { "path": "head", "visible": true, "opacity": 1 }
  ],
  "layers": [
    {
      "name": "source",
      "image": { "path": "source.png", "width": 3258, "height": 4344 },
      "rect": { "left": 0, "top": 0, "width": 3258, "height": 4344 },
      "reference": true,
      "opacity": 0.5
    },
    {
      "name": "back hair",
      "image": { "path": "parts/back_hair.png", "width": 484, "height": 601 },
      "rect": { "left": 804.3, "top": 207.0, "width": 1642.6, "height": 2039.6 },
      "tags": ["hair", "back"]
    },
    {
      "id": "iris_l",
      "name": "iris L",
      "image": { "path": "parts/iris_l.png", "width": 126, "height": 147 },
      "rect": { "left": 1724.24, "top": 745.36, "width": 101.65, "height": 118.63 },
      "group": "head/eyes",
      "tags": ["eye", "iris", "left"]
    },
    {
      "name": "mouth open",
      "image": { "path": "parts/mouth_open.png", "width": 183, "height": 78 },
      "rect": { "left": 1555.6, "top": 935.8, "width": 147.6, "height": 62.9 },
      "group": "head",
      "tags": ["mouth", "open"],
      "variant": { "type": "toggle", "parameter": "ParamMouthOpen" },
      "opacity": 1,
      "visible": true
    }
  ]
}
```

### Top level

| Field | Required | Description |
| --- | --- | --- |
| `format` | Yes | Always `psd2live-layout` |
| `version` | Yes | Integer, currently `1`. PSD2Live refuses versions newer than itself |
| `canvas` | Yes | `{width, height}`: canvas size in integer canvas units, 1–8192 per side and at most 16 MP in total. Canvas units are the pixels of the model canvas after export to Cubism |
| `layers` | Yes | Array of layers, 1–4096 entries, ordered as in [Stacking order](#stacking-order) |
| `groups` | No | Group properties: `path` (required), `visible` (default `true`), `opacity` (0–1, default `1`). Paths must not repeat |
| `name` | No | The manifest's name, used as the material folder name; the file name when omitted |

### Layers

| Field | Required | Description |
| --- | --- | --- |
| `name` | Yes | Layer name, at most 128 printable characters |
| `image` | Yes | `{path, width, height}`: the image path (relative, separated by `/`, without `..` or `.` segments, backslashes or drive letters, and not escaping the manifest's folder or that level of the archive) and the image's pixel size; the size must match the image |
| `rect` | Yes | `{left, top, width, height}`: the canvas rectangle the image fills, in float canvas units, with positive width and height; it may extend beyond the canvas. The ratio of pixel size to rectangle is the texture density: a `126 × 147` image filling a `101.65 × 118.63` rectangle gives about 1.24 texture pixels per canvas unit |
| `id` | No | Layer ID, `[A-Za-z0-9_.:#-]{1,96}`, unique within the manifest. Later edits and MCP refer to the layer by it; when omitted it is derived from the index and name, and the same manifest always gives the same IDs |
| `group` | No | Group path, names joined with `/`, such as `head/eyes`; every level becomes a source group |
| `tags` | No | Array of strings: semantic tags and user tags, see [Tags](#tags) |
| `variant` | No | Variant, see [Variants](#variants) |
| `visible` | No | Default `true` |
| `opacity` | No | 0–1, default `1` |
| `reference` | No | Default `false`. `true` makes it a [reference layer](#reference-layers) |

Unknown fields are ignored; fields added within a version are all optional, and changing the meaning of an existing field raises the version.

### Stacking order

`layers` runs back to front: later layers draw over earlier ones. This is the same as the layer order of PSD2Live's source (painter's order, also the order PSD2Live gets from reading a PSD), and the direction in which Cubism draw order increases: the draw order of layer i is not lower than that of layer i−1.

PSD2Live's layer table lists layers in this order, and generation uses it for the base draw order; generators such as the mouth have rules of their own, and settings on the draw order ruler take precedence.

### Tags

- Part words and `left` / `right` in `tags` decide the part and side, for example `["eye", "iris", "left"]` is the left iris and `["hair", "front"]` front hair; for the vocabulary and combinations see [Semantic tags](PSD_LAYER_SPEC.md#semantic-tags). Other words are user tags, used only for organizing and searching, and do not affect generation.
- When a part word is present, both part and side come from `tags` (no side word means no side). When there is no part word or `tags` is omitted, part and side are recognized from the layer name by the same rules as PSD layers (see [Name reference](PSD_LAYER_SPEC.md#name-reference)), and a side word in `tags` still decides the side.
- Left and right are the character's own: the character's left side is usually on the right of the image.
- A layer cannot have both `left` and `right`.

### Reference layers

A layer with `reference` set to `true` is only a reference: the picture the parts were split from, for instance, to place and trace against. Like any layer it has a stacking order, a group and an opacity and shows on the canvas, but the generators never read it, the rig and every export leave it out, and it needs no tags. Once opened it can be made a model layer at any time, and other layers not generated yet can become references (see [Materials and Layout implementation](../../zh/spec/MATERIAL_LAYOUT_WORKSPACE.md#参考图层), Chinese).

### Variants

`variant` is `{type, parameter, switch_id}`, the same as MCP `layer_set_variant`:

| `type` | Meaning | `parameter` | `switch_id` |
| --- | --- | --- | --- |
| `none` | No variant (default) | Omitted | Omitted or `0` |
| `toggle` | Shown or hidden by a parameter | Required | Omitted or `0` |
| `switch` | Choice number `switch_id` of a parameter | Required | Non-negative integer |

## How PSD2Live reads it

- **Open as a project**: choose a manifest or zip on the **Start** page or in the open dialog, drag it into the window, or use MCP `project_import_layout`. This creates a Layout project that is not generated yet and enters the Layout workspace: the canvas is `canvas`, layers are placed as the manifest says, and images keep their original pixels, filling their rectangles; tags and variants become layer classification, user tags become layer tags, and `reference` layers become reference layers; each layer is also registered as a material (in a folder named after `name`) and linked to it. Placement and stacking order can then be adjusted as usual before generating. The project does not keep the manifest or image files; pixels are stored in the project history.
- **Import as materials**: import in the **Materials** panel, or MCP `material_import`. This gives a folder named after `name`, with groups as subfolders; each item's default place is its `rect`, pixels keep their original resolution, and `tags` come along; items of `reference` layers are marked to place as references. Variants belong to layers and do not travel with materials. Placing the whole folder keeps the manifest's stacking order. Scanning a folder skips the JSON and zip files in it, so a manifest's images are imported only once, as ordinary images.
- Reading stops at any error and states where (such as `layers[3]: parts/face.png is 808x935 pixels, the manifest says 800x935`); nothing is imported partially.

## Verification

`LayoutManifestScenario` covers: a manifest opening as a project not generated yet, with images kept at the manifest's pixels and rectangles (a part with 3× pixels keeping its density, fractional rectangles), stacking order, groups and group opacity, tag classification and user tags, variants, opacity, and material registration and links, with reference layers kept out of the rig before and after generating and joining the next generation once given back; generating after reordering, refusing to reorder generated layers, and saving and reopening; zips with the manifest at the root and in a top-level folder reading the same content, importing as materials with default places and tags, and folder scans skipping manifests; rejection of escaping paths, mismatched sizes, newer versions, other formats and two manifests in one archive; and placing a whole material folder from a PSD or a manifest keeping the original stacking order; and reference materials placing as their mark says unless the placement says otherwise.
