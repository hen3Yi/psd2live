# Deform paths (experimental)

[Documentation](../../README.md) · [中文](../../zh/guide/DEFORM_PATHS.md) · [Canvas editing](CANVAS_EDITOR.md)

A path is an editing aid attached to an ArtMesh, not a third kind of deformer beside Warp and Rotation. One ArtMesh can carry several paths; one path cannot control several ArtMeshes.

## Quick steps

1. Select an ArtMesh and right-click in the tree for **Deform path** (default `P` / `D`).
2. Click to place at least two control points; Enter confirms, Esc cancels.
3. In **Edit mode**, adjust the path's binding position, width, hardness, closing and corners; this should not change the image's shape.
4. Set the target pose in the Parameters panel, switch to **Deform mode** and drag the control points to write the result into the ArtMesh's shape.

Object mode only selects objects. Paths come in two groups, L2 and L3, each stored separately (written to `.cmo3` as the path's edit level), unrelated to the warp deformer's **Vertices** / **Bezier** levels: pick the group for a new path under **Path edit level** in the placement panel or in **Tool Details**, and change it for an existing path under **Edit Level** in the Inspector; the canvas shows and edits only the current group's paths. Guide lines show according to the selected object and the edit page's view settings; the preview page does not show paths. Ordinary edits go into the main canvas history.

## Saving and export

| File | What it holds |
| --- | --- |
| `.psd2live` | Path settings, bindings and replayable shape edits |
| `.cmo3` | The native controller structure and the mesh keyforms |
| `.moc3` | Baked mesh keyforms; the runtime does not run the path algorithm |

The current algorithm uses curve sampling, moving least squares and triangle binding. That the controllers read and write does not mean dragging has been verified to match Cubism Editor; width, hardness, large deformations and non-uniform parent scaling especially need checking.

Topology operations on the main canvas rebind paths. A mesh that is too sparse, or constraints that conflict, can still get a deformation rejected. Full compatibility acceptance - opening, dragging and resaving in the official Editor - is not yet recorded on this page.

Agents view paths with `path_list` and `path_get`, create, change or delete them with `path_put` and `path_delete`, try dragging control points with `path_preview`, and write a drag's result as keyforms with `path_deform`; `workspace_inspect` with `scope=paths` lists every path. For the fields see the [MCP interface](../agent/MCP_AUTHORING.md).
