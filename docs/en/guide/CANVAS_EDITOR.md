# Canvas editing reference

[Documentation](../../README.md) · [中文](../../zh/guide/CANVAS_EDITOR.md) · [User quick reference](USER_GUIDE.md) · [Illustrated front/back layering tutorial](DEPTH_SPLIT.md) · [Deform paths](DEFORM_PATHS.md)

This page covers lessons 3 and 6–12 of the in-app beginner path. It groups the canvas editing rules by topic; for exact button positions and current shortcuts, the **Tutorials** on the Help page are authoritative.

## Pick a mode first, then a tool

The mode menu at the top left of the canvas (like Blender's mode drop-down) lists every mode; while it is open, 1–7 pick one directly, the arrow keys move and Enter confirms. **Preview** is in this menu too and does what the title bar's **Preview** used to; on the preview canvas, picking any other mode returns to editing.

| Mode | What it changes | Common tools |
| --- | --- | --- |
| Object | Object selection | Click, box and lasso select; synced with the hierarchy; the toolbar's **Create** |
| Deform | Base shape or the keyform of the current parameters | Vertex / edge / face select, transform, push, smooth, inflate, path control points |
| Edit | Mesh topology and path binding | Vertex / edge / face select, connect, merge, subdivide, knife, delete, Glue (when two meshes are selected) |
| Simulate | Vertex weight groups read by the simulation (this app only) | Weight brush, weight gradient, with the group to paint (pinned, stiffness and so on) switched by kind beneath them; the mode bar creates cloth / hair and switches the reference simulation (not exported) |
| Skeleton | Skeleton | Pose, bone edit; bone edits are saved when switching to pose or leaving the mode |
| Paint | Layer pixels | Brush, pencil, eraser, fill, eyedropper, shapes |
| Preview | Nothing | Playback, mouse follow, physics, frame rate |

Like Edit mode, Simulate mode acts on every selected mesh at once, and always shows the current weight group in Blender's weight colours (blue 0 → green → yellow → red 1). The weight brush ring is coloured by mode (add white, subtract red, set accent, smooth cyan); on hover it marks the vertices a press would affect, and the number next to the pointer is the nearest vertex's weight; Alt + right-drag adjusts radius / hardness and previews the falloff. The weight gradient drags from the full value to where it fades out. The simulation itself runs only inside the editor; to export it, bake it in the Simulation panel, see [Simulation and baking](SIMULATION.md).

The current tool's settings are split across several places, each with its own job:

| Place | Contents |
| --- | --- |
| Left toolbar | The current mode's tools, one per row, grouped by category with dividers; in Object mode the last group creates Warp, Rotation and deform paths. The selected tool's variants (brush shape, paint shape, Glue and skeleton sub-tools, weight group kind) unfold beneath it; `1`–`5` also pick them |
| Mode bar (top of the canvas) | After the mode menu come the items that decide how the canvas behaves: vertex / edge / face for selection tools in Deform / Edit mode (`1`–`3`), and confirm and cancel for the current step (placement, knife, path, Glue and so on) |
| Tool options bar (bottom left of the canvas) | The current tool's values: radius, strength, hardness, opacity, mode and so on; drag a value left or right to adjust it, click it to pop up a slider. In Paint mode the foreground / background colours come first |
| Canvas context menu | The same settings as the mode bar and options bar (one per row), plus the tool's actions, each with an icon; it opens and highlights on hover with the same animation as the mode menu. In Object mode, creating Warp, Rotation, deform paths, bones and swings is gathered in the **Create** submenu. In Object, Edit and Paint modes, actions on the whole layer have submenus too: **Mesh** holds rebuilding the mesh from the current pixels, splitting disconnected meshes and, when available, front/back layers; a layer linked to a material also gets **Material**, which opens it in the material window or unlinks it. When the rest fits in 10 rows it is all shown directly; only beyond that are the largest groups (such as topology, falloff) folded into one row that unfolds to the right on hover. The context menus of the panels (hierarchy, materials, texture atlas, motions, physics and so on) look and open like the canvas menu: they unfold from where you pressed and flip to the other side near the window edge |

The mode bar, options bar and context menu show one shared definition with the same scope; brush shortcuts such as `[` `]` adjust the same value of the current tool shown in the options bar. The **Tool Details** panel on the right keeps only advanced settings that need more room: precise numeric transforms, the creation defaults for Warp / Rotation, the path list, bone binding and weights, and the paint palette. Skin weight display is toggled in the view rail at the bottom right.

By default the wheel zooms, the middle button or Space + left drag pans, Shift adds to the selection and Alt subtracts.

For screenshots of the pin weight brush and the baking workflow, see the [illustrated simulation tutorial](SIMULATION_TUTORIAL.md).

## Points and lines on the canvas

Editable points and lines look the same in every mode, and each colour has a fixed meaning:

| Look | Meaning |
| --- | --- |
| Light thin lines and small points | The mesh or Warp grid being edited; when several meshes are edited at once, each has its own colour |
| Accent colour, enlarged with a white border | Selected vertices, edges and faces |
| White outer ring | The hovered point, i.e. the one a click would select |
| Red circles | Points a brush press would push; larger and more solid means more strongly affected; shown on hover and kept while dragging |
| Accent circles | Points a selection brush press would select |
| Weight colours (blue → red) | Simulation and skeleton weights |

A point's shape says what it is: mesh vertices are round, Warp control points square, Glue points diamonds. Only points that can be clicked directly are shown; the mesh toggle in the bottom-right view rail draws only each part's mesh lines (each in the part's own colour), no longer points. The mesh or Warp being edited is drawn by the canvas itself, so the view rail does not draw a second copy.

## Deformation and keyforms

Objects without bound parameters edit the base shape; bound objects edit the current parameter combination. When the target is a mesh deformer (Warp), the mode bar switches between **Vertices** (edit the grid points) and **Bezier**: in Bezier only the cyan anchors and amber handles can be dragged, and the detailed grid is dimmed and not editable; for other objects the two do not differ and are not shown. Set the target values in the Parameters panel first, then adjust the control points. Check the object's own axes and the motion inherited from its parents, so a parent's pose is not mistaken for a direct binding of the current object.

## Multiple canvases, parameters and animation

All canvases of a workspace share the parameter pose, parameter locks and playback controls. Switching between edit and preview, changing focus or hiding a canvas never swaps back to another pose. Selection, tool, camera and display settings are still kept per canvas; different workspaces keep their own poses.

Dragging a parameter or posing with bones on an edit canvas pauses animation and shows the edited values directly; resetting parameters and snapping to keys do not lock automatically. Dragging the timeline in the animation editor sends the sampled pose to the workspace, so edit canvases can view and keep adjusting it. Edited values never use a preview frame left by earlier playback; while posing, mouse follow and paused physics do not override it. Restarting playback or toggling mouse follow returns to the live preview. Adjusting parameters on a preview canvas still shows how paused physics responds.

The small ruler next to an animation track's name shows the parameter's keys, and the curve view shows the same keys as reference lines with values. Deleting a parameter removes its animation tracks, and shrinking a parameter's range clamps the animation keys and Bezier control points to match.

Playback, the timeline and baking never create, show or switch canvases on their own. When you need a canvas, open it from the **Windows** menu or a panel's show-canvas button.

## Deform brush

The deform brush changes geometry, not textures. With no control points selected it acts on all points of the current object; with a selection it is limited to the selected points. The radius is measured in canvas units. By default Alt + right-drag adjusts radius or hardness, and Esc cancels the current gesture.

The tip shape is picked under the brush in the left toolbar (the same for the weight brush); the other settings are in the tool options bar and the canvas context menu:

| Setting | Effect |
| --- | --- |
| **Shape** | **Circle**, **Line** or **Rectangle** |
| **Radius** / **Hardness** / **Strength** | Reach, edge softness and how hard each stroke pushes |
| **Falloff** | Weight curve from centre to edge: **Smooth**, **Sphere**, **Root**, **Inverse Square**, **Sharp**, **Linear**, **Constant**, **Random** |
| **Connected Only** | Affects only points connected to the mesh under the pointer; the circle brush measures distance along mesh edges instead |

![Deform brush settings, and the reach with Connected Only on](../../imgs/mesh-brush.webp)

*Left: brush settings. Right: with **Connected Only** on, the affected vertices (red) stay on the strand under the pointer even though the circle covers the neighbouring hair. These vertices are marked with red circles already on hover; larger circles mean stronger influence. Useful for brushing parts that sit close together, such as neighbouring hair strands or the upper and lower lip.*

## Mesh topology

| Action | Result |
| --- | --- |
| Subdivide edge / region subdivide | Adds points on the hit edges and rebuilds triangles; neighbouring faces handle the seam in step |
| Connect / merge | Connects the selected points, or merges the selected vertices into one |
| **Duplicate** | Copies the selected vertices and the faces they fully cover; the copy becomes the selection |
| **Delete** | Deletes the selection and tries to rebuild the gap; refused when no valid topology can be formed |
| Knife | Places a polyline point by point; snaps near points or edges, Shift turns snapping off temporarily; Enter finishes, Esc cancels |

The knife may place points outside the original mesh. Topology updates also handle UVs, keyforms, blend shapes and Glue vertex references, and rebind paths. Still check existing poses, especially boundary changes after deleting outline points.

## Creating and placing materials

1. Select the target and pick Warp, Rotation or deform path in the last group of the Object mode toolbar, or press `C` / `R` / `P`; you can also right-click in the hierarchy and add a parent / child Warp or Rotation, or pick from **Create** in the Object mode canvas context menu.
2. Adjust the placement preview on the canvas and check the affected objects, name, parent and division settings.
3. Click **Confirm** on the mode bar or press Enter to write it to the project; click **Cancel** or press Esc to cancel.

Dragging a PNG / WebP / TIFF / BMP onto the hierarchy, or **Import Layer…** in the tree's context menu, also starts a placement session. Dropped on an art mesh, the new layer goes under that art mesh's parent deformer. After placement, check occlusion and multi-parameter poses.

## Transform box

The **Transform** tool, the placement box of a Warp / layer and the selected tiles on a texture atlas page use the same transform box, with the same look and handling. With nothing selected the Transform tool behaves like the selection tool, clicking or box-selecting; once something is selected the transform box appears:

| Action | Effect |
| --- | --- |
| Drag inside the box | Move |
| Drag a corner / edge midpoint | Scale, keeping the opposite side in place; `Shift` on a corner keeps proportions, `Alt` scales about the anchor |
| Drag the round handle above the top edge, or just outside a corner (an arrowed arc lights up outside the corner on hover) | Rotate about the anchor; `Shift` steps by 15° |
| Drag the anchor (the outlined cross in a circle) | Move the anchor; it snaps near the centre, corners or edge midpoints, and dragging it back restores the default |

The anchor starts at the centre of the selection (the centroid of its points). Its position relative to the selection in the box is kept, so it follows the selection after moves, scales and rotations; the rotation and scale of **Precise Transform** also use it as the centre. Changing the selection or leaving the tool with Esc returns the anchor to its default. While dragging, the pointer shows the rotation angle, the scale on both axes or the canvas pixels moved, and the other handles dim. When the box is under 40 screen pixels wide or tall, pressing in the middle always moves and the anchor cannot be dragged (zoom in instead). The placement boxes of Warps and layers are created as rectangles and can only be moved and scaled, not rotated; texture atlas tiles scale density proportionally from their corners and have no edge midpoints.

## Rotation deformers

Drag the origin to move it and the end point to rotate; by default holding `Shift` snaps the angle, and `Alt` + dragging the end point adjusts the scale.

## Multi-mesh editing

Edit mode acts on all selected art meshes at once:

- Vertex clicking, box selection, brush selection, the transform box and the deform brush all work across meshes.
- Clicking a vertex of a mesh makes it the **primary mesh**; the knife, edge / face modes and paths act on the primary mesh only.
- Vertex actions such as delete commit once per mesh with a selection, in a single history entry.

## Glue

With two meshes selected, pick the Glue tool in Edit mode; the toolbar lists three sub-tools beneath it: **Glue brush**, **Weight brush** and **Remerge** (`1`–`3`). A glue point is one point shared by the two meshes and is shown on the canvas as a single diamond. Selected or dragged, the vertices on both sides always move together, so gluing never misaligns them while editing.

As in Cubism, gluing happens on the meshes' rest shape (all parameters at their defaults), where glued points coincide on both sides and pull on nothing. With parameters away from their defaults, the Glue brush, Remerge, **Create glue** and gluing selected vertices first move the parameters smoothly back to their defaults and then glue; adjusting weights and unglueing leave the pose unchanged.

| Action | Effect |
| --- | --- |
| **Glue brush** | Changes only the mesh, not the image: where the meshes overlap (or within the snap tolerance) the other mesh gains or slides a vertex, with its UV following; after the insertion, edges are flipped without changing the texture to avoid thin triangles |
| `Alt` + drag | Unglue |
| **Weight brush** | Shows the weights of both sides on the texture in blue (A) / orange (B) |
| **Remerge** | Removes existing glue on the brushed vertices, then glues again by the snap tolerance |
| `Enter` or **Create glue** on the mode bar | Glues along the two outlines as a whole |
| `Ctrl+G` (the export model key; it does not open export here) | Glues the vertices selected on either mesh |
| Right-click **Remerge the whole seam** | Removes all glue between the two meshes and glues along the outlines again |

## Applying and discarding paint

Select a layer before entering Paint. Strokes are kept in a separate paint session with undo and redo inside the session. After the first stroke, a session bar slides in at the top centre of the canvas (the same as the texture atlas adjustment session bar), showing the layer being painted and the stroke count, with Undo, Redo, **Discard** and **Apply**; on a narrow canvas the buttons show icons only, named on hover. The **Tool Details** panel also has **Discard** and **Apply**.

The palette works the Photoshop way: the overlapping foreground / background swatches come first in the tool options bar at the bottom left, with swap (X) at the top right and the default black / white at the bottom left; clicking a swatch opens the colour picker - a saturation / brightness field on the left, a vertical hue strip on the right, **New / Current** comparison swatches (click **Current** to restore), H S B and R G B values and the hex code, and swatches and recent colours below. The **Tool Details** panel embeds the foreground colour's field and hue strip directly, along with the swatches and recent colours. The brush, pencil, eraser and shape sizes are independent of each other; a shape's line width does not change the brush size.

Painting is not limited to the layer's rectangle; the whole canvas can be painted. Applying cuts the paint by the layer's mesh:

- **The part on the mesh** stays in the layer. The layer's mesh, keyforms and bindings are unchanged.
- **The part off the mesh** is split into a new layer named after the original with a "+" appended, placed directly above the original. The new layer has its own mesh and sits under the original's parent and part, so it moves with it. It also appears in the material library as a material, next to the original layer's material (at the top level when the original has none), and the layer is linked to it.
- Erasing off the mesh does nothing: those pixels are not shown anyway, and the layer's existing soft edge is not split off.
- A layer without a mesh yet (for example a transparent layer just getting its first stroke) is not cut; its mesh is generated from all its pixels.
- For a layer linked to a material, the part on the mesh is written into the material, so every layer using that material changes together; the part off the mesh is likewise split into a new layer.

To have the layer's own mesh cover the newly painted area, use **Rebuild from pixels** in the Mesh panel; keyforms, paths, weights and glue migrate to the new mesh.

After applying, check deformation and masks, then save the project. For implementation details see [paint system](../../zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) (Chinese).

## Commit and restore

Ordinary geometry gestures preview while dragging and enter the project history when they finish successfully; creating, cutting and painting have their own confirm steps. Do not treat "the current draft", "committed history" and "the project written to disk" as the same state.

For the differences between path modes and their export limits, see [Deform paths](DEFORM_PATHS.md). For project persistence, see [Project format](../spec/PROJECT_FORMAT.md).
