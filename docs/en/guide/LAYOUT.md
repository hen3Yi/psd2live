# Materials and Layout

[Documentation](../../README.md) · [中文](../../zh/guide/LAYOUT.md) · [User guide](USER_GUIDE.md) · [Canvas editing](CANVAS_EDITOR.md) · [Implementation notes](../../zh/spec/MATERIAL_LAYOUT_WORKSPACE.md) (Chinese) · [Layout manifest](../spec/LAYOUT_MANIFEST.md)

Layout lets you arrange parts in the project first and generate the rig afterwards. After generating you can keep adding images and changing tags, then generate again; the edits you have already made are kept.

## Getting started

- **From an empty canvas**: on the **Start** page pick **Layout project** (**File → New → New Layout project…** opens it too), choose a common size or type the width and height in the properties panel, and click **Create Layout Project** or double-click a size. The new project has no rig yet and switches to the Layout workspace.
- **From a PSD**: import the PSD as usual. The project generates the rig straight away, and every PSD layer also appears in the Materials panel, linked to its layer on the canvas.
- **From a Layout manifest**: a [Layout manifest](../spec/LAYOUT_MANIFEST.md) written by an external tool (such as AI part splitting) - a JSON, or a zip holding it at its root - opens the way a PSD does, or by dropping it on the window. The layers are arranged as the manifest says: position, stacking order, groups, tags and variants are set, and small parts keep more pixels than the canvas would give them; the project has no rig yet and switches to the Layout workspace.
- Any workspace can open the **Materials** panel from the **Windows** menu.

The Layout workspace: the Materials panel on the left, the edit canvas (Object mode) in the middle, the hierarchy tree and the layer table on the right.

## Materials panel

- **Import**: click **Import** to choose files, **Import folder…** to choose a whole folder, or drop files and folders on the panel (dropping on a folder or on one level of the path bar imports there). A folder is imported with its nesting intact: every level of subfolder becomes a material folder, and files are ordered by the numbers in their names (arm2 before arm10); hidden files and folders (such as .git) and unsupported files are skipped. Supported are PNG, BMP, TIFF, WebP (lossy too), JPEG, PSD, the layered images in a CMO3, and SVG. A PSD becomes a folder, its layer groups become subfolders, and each layer remembers its place in the PSD.
- **Browsing**: on the left is a compact material tree (folders and the images in them, all expanded by default; the tree button on the toolbar collapses it; images in the tree can likewise be dragged onto the canvas or double-clicked to open), and on the right the contents of the current folder: folders first, then materials, as a grid or a list. The divider between the two columns can be dragged to change their widths; the slider at the bottom right sets the grid's thumbnail size (materials with a lot of transparent margin show only the part with content). Double-click a folder to enter it; go back with the tree, the ↑ in the path bar above, or any of its levels; search looks through every folder. The number at an item's top right is how many layers use it.
- **Sources**: a folder imported from a PSD or a CMO3 shows as a file icon with a "PSD" / "CMO3" band, a page stacked behind it, and its item count and an arrow at the bottom right, meaning it opens with a double-click; hover to see the source file name.
- **Organizing**: drag a material or folder onto another folder (in the grid, the tree or on one level of the path bar) to move it in. The context menu opens where you click, like the canvas context menu; the menus of the tree's top level and of folders can create a folder and import files or folders there. The context menu can rename, create a folder, move up a folder and delete. A material that layers are using cannot be deleted; the menu says how many layers use it.
- **SVG**: right-click **Texture resolution…** and enter, say, `512x256`. The resolution decides the pixels that go into the texture atlas; the size on the canvas does not change, and layers already placed update with it.
- **Export PSD**: right-click a folder and choose **Export folder as PSD…**.

## Material window

Double-clicking a material (or right-click **Open in material window**) opens it in a separate window that can be resized freely and moved to another screen. While the window is open, clicking another material in the panel switches the window to it.

- **Viewing**: the wheel zooms, the middle button or Space + drag pans; the bottom right switches between checkerboard / white / black background, **Fit** (F) and 1:1 (Home). At 2× and above it shows pixels as they are.
- **Painting**: the window is the canvas's paint mode - the toolbar on the left, the options bar at the bottom, the paint session bar at the top (undo, redo, discard, apply), the context menu, the brush tip and colour-picker display and every shortcut are the same as in paint mode, only it paints the material's own pixels. Colours and settings such as each tool's size come over from the current canvas and go back when the window closes.
- **Applying**: as in paint mode, strokes stay in the session first; **Apply** (Enter) writes them into the material as one history step and **Discard** drops them; switching to another material or closing the window applies them automatically. While painting you can go on editing other things in the main window.
- **Adjusting**: the **Adjust** menu at the top left flips horizontally or vertically and adjusts hue, saturation, lightness and contrast; dragging a slider previews first, and **Apply** writes it. With unapplied strokes, apply or discard them first.

Every layer that places this material updates with it, keeping its position and size on the canvas. An SVG material becomes a bitmap at its current resolution once painted or adjusted.

## Materials and linked layers

A layer placed from a material (and a layer from an imported PSD) is linked to it: the layer shows the material's pixels, and the material is their only source.

- Painting such a layer in paint mode paints the material itself, and every layer using the material changes with it; the session bar says **Painting material "…" · shown by N layer(s)**. The whole canvas can be painted: on apply, what falls on the layer's mesh is written into the material, and what falls outside the mesh is split off into a new layer placed right above it, which also goes next to the original material as a new material.
- In the layer table a linked layer has a chain mark after its name; hover to see the material name and how many layers share it. Click it to **Open in material window** or **Unlink from material**.
- After unlinking, the layer's pixels are its own, and painting it no longer affects the material or other layers. Other edits that make a layer's pixels differ from its material (such as splitting, or replacing it with another image) unlink it automatically too.
- **Replace selected layer's image** with another material links the layer to that material instead.

Materials are saved with the project, and every change can be undone.

## Placing

- **Drag** a material or a whole folder **onto the canvas** to place it where it drops; right-click **Place on canvas** does it too. Materials in a folder keep their relative positions, and subfolders become source groups.
- Materials from a PSD go back to their place in the PSD by default: the PSD's canvas as a whole sits in the middle of the project canvas, scaled down proportionally when it is larger; when the two are the same size, it is the original place. Other materials are placed in the middle of the canvas at "1 material pixel = 1 canvas unit", scaled down automatically when they would extend past the canvas.
- Canvas units are the model's coordinates: exported to Cubism they are the pixels of the model canvas, and they decide the model's size and proportions, not its sharpness. Sharpness comes from the material's own pixels - placing and scaling do not resample, so the smaller a layer is placed the denser its texture; on export the textures must also fit the atlas budget (the page size and page count in the Texture workspace), and when they do not fit the tiles shrink together.
- In Object mode the transform box **moves, scales and mirrors** layers. Pixels are not resampled: scale a 600×600 eye down to a 64×64 area of the canvas and the atlas still keeps it at 600×600 density (within the atlas budget).
- **Stacking order**: newly placed layers go on top, and placing a whole folder keeps the folder's original stacking order. With layers selected, use **Bring Forward** and **Send Backward** on the layer table's toolbar, or press Ctrl+] / Ctrl+[ on the canvas to move them forward or backward one step, and Ctrl+Shift+] / Ctrl+Shift+[ to bring them to the front or send them to the back. The layer table lists layers back to front, the lower the further forward, matching Cubism's draw order. Any layer not generated yet can be reordered; after generating, the order of generated layers is adjusted with draw order, while layers placed since can still be reordered until the next generation, and until then they show in front.
- **Reference layers**: a layer used only to compare against, such as the original picture or a sketch, can be made a reference: it stays on the canvas and is placed and ordered as usual, but it is never generated or exported. Select layers and click **Use as Reference** on the layer table toolbar or press Ctrl+Alt+R to switch; the canvas context menu has a **Use as Reference** switch too. A reference layer shows a reference icon after its name; click it to make it a model layer again. Reference layers lie under the model; the canvas display switch **Reference layers on top** (bottom right) lays them over it. Right-click a material for **Place as reference layer**, or turn on **Place as reference by default**; layers a layout manifest marks `reference` open as reference layers. Generated layers cannot become references; a reference given back joins the model at the next generation.
- **Replacing an image**: select the layer first, then right-click a material and choose **Replace selected layer's image**. The layer's position and size on the canvas stay; only its pixels and atlas tile change. Replacing from the context menu in the atlas view works the same way.

## Tags

Every layer in the layer table has a **Tags** cell; click it to edit:

- Part and side words tell the generator what the layer is: for example "hair + front" is front hair, "eye + pupil + left" is the left pupil, "mouth + open" is an open mouth. Parts and sides can be picked directly below the menu.
- The input box takes words from the vocabulary, and also common layer names (such as "前发" or "eye-l"), which are converted to the matching words; any other text becomes a tag of your own, for organizing and search only, with no effect on generation.
- Toggle and switch variants are not tags; they are set in the **Variant** column next to them.
- Tags added to a material or a material folder are carried by the layers placed from it.

## Generating and regenerating

The generate bar at the top right of the edit canvas shows what is still waiting:

- **Not generated**: a new Layout project. Each layer on the canvas is only a plain mesh: deformers and parameters cannot be edited yet, and nothing can be exported. Click **Generate** once the layers are arranged.
- **N waiting**: layers placed after generating, and layers whose tags or classification changed. Newly placed layers show in front of the rig as plain meshes that can be moved, scaled, replaced and deleted, but their rig cannot be edited yet and exports leave them out. A changed classification does not affect the rig until the next generation (the layer table outlines it in the accent colour).
- Clicking **Regenerate** merges these into the rig. The keyforms, deformers, physics, swing, bones and Glue you made are kept; whatever cannot carry over cleanly is listed in the quality check.
- A layer whose image was replaced gets a new mesh from its new pixels by default; to keep its old mesh, choose **Generate, keeping replaced images' meshes** in the generate button's drop-down.

One generation takes about as long as importing a PSD of the same size.

## MCP

Agents follow the same flow with the same operations: `project_create`, `project_import_layout`, `material_import`, `layer_place`, `layer_tag`, `layer_reorder`, `rig_generate` and so on; see the materials and Layout section of [MCP_AUTHORING.md](../agent/MCP_AUTHORING.md).
