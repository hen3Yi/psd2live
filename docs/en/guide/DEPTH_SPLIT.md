# Illustrated front/back layering tutorial

[Documentation](../../README.md) · [中文](../../zh/guide/DEPTH_SPLIT.md) · [User quick reference](USER_GUIDE.md) · [Canvas editing](CANVAS_EDITOR.md)

Different parts of one layer sometimes need to sit in front of another part and behind it at the same time. **Front/back layers** quickly duplicates a layer into a front and a back layer, sets the occlusion order, and glues them so they deform together. All you do is erase, on the new front layer, what should go behind.

This tutorial walks through the whole process with a collar passing around a neck, then shows another use with hair and a shoulder ornament. It works for models generated from a PSD or images; imported CMO3 models do not support it yet. The screenshots show the Chinese UI.

## 1. Find what needs front/back occlusion

In the image below, the back of the collar covers the neck. Because the whole collar is on one layer, moving it in front of or behind the neck leaves part of the occlusion wrong either way.

![Before: the back of the collar covers the front of the neck](../../imgs/depth-split/collar-before.webp)

*Before: the collar that should be behind the neck shows in front of it.*

We want the back of the collar hidden behind the neck, with the front collar pieces still covering the bottom edge of the neck:

![Target: the neck sits between the back collar and the front collar pieces](../../imgs/depth-split/collar-target.webp)

*Target: the neck shows through the collar, with natural front/back occlusion.*

The occlusion order is:

| Position | Part | Role |
| --- | --- | --- |
| Frontmost | The collar's layer · front | The back collar is erased, keeping only what should show in front of the neck |
| Middle | Neck | Covers the back collar, covered by the front collar pieces |
| Backmost | The collar's layer · back | Keeps the whole original layer, providing the back content |

The collar in the screenshots belongs to the `topwear` layer, the whole top, so the whole top is what gets layered. There is no need to cut the collar out first.

## 2. Select the top and the neck together

On the edit canvas switch to **Object** mode, select the top layer the collar is on, then hold **Shift** and add the neck. The multiple selection can also be made in the hierarchy tree.

![The top and the neck selected together, with two selection boxes on the canvas](../../imgs/depth-split/select-layers.webp)

*The two selection boxes are the top and the neck. The top is the object to split into front and back layers; the neck is the object in between.*

You can add several ArtMeshes to sit in between, but only one ArtMesh is layered at a time. The other selected objects all go between the front and back layers, keeping their own draw order.

## 3. Right-click the top and run front/back layering

Keeping the selection, right-click the **top** to be layered and choose **Split “topwear” into front/back layers**. The name in the menu follows your layer name; before clicking, check that it names the object to duplicate.

![The context menu item Split "topwear" into front/back layers, outlined in red](../../imgs/depth-split/context-menu.webp)

*The menu item in the red box creates the front and back layers directly and enters the eraser on the front layer.*

Once created, the original layer is replaced by two new layers, "`topwear · Back`" and "`topwear · Front`", both carrying the original layer's full pixels. The program arranges their draw order around the neck, copies the mesh and deformation data, and glues the matching vertices of the front and back layers so the front layer follows the back one. The original layer no longer appears in the layer list; to go back to before the split, use undo.

**If only the top is selected:** right-click the top in the hierarchy tree, choose **Front/back layers…**, pick the neck under **Intervening object** in the dialog, check the front / middle / back arrangement, then click **Create and erase**.

## 4. Erase the back collar on the front layer

After creation the program selects the new front layer and switches to **Paint** mode and the **Eraser**. First check that the top of the canvas shows "`topwear · Front`".

Zoom in on the collar and, following the edge of the neck, erase the part of the collar on the front layer that should be behind the neck. The arrow in the image below marks where to erase.

![Using the eraser on the topwear front layer; a red arrow points at the back collar to erase](../../imgs/depth-split/erase-front.webp)

*What gets erased is the back collar on the front layer. The back layer keeps these pixels, so once they are erased the neck in the middle shows through.*

Adjust the eraser size, clear the inside of the area first, then clean up the edge with a smaller brush. Keep the front collar pieces and whatever should be in front of the neck; there is no need to clear the whole top either.

If you erase too much, **Undo** in the paint session restores strokes. Other parts dim while painting; this is only an editing display effect.

## 5. Apply and check different poses

When erasing is done, click **Apply** at the top of the canvas or in the tool panel. A front layer made by front/back layering keeps its original mesh and deformation, so applying needs no mesh rebuild.

![The collar after applying: the neck shows, and the front collar pieces stay in front of it](../../imgs/depth-split/collar-result.webp)

*The finished occlusion: the back collar behind the neck, the front collar pieces in front of it.*

Then check head turns, looking up and down and body turns in the Parameters panel, paying particular attention to where the collar meets the neck. If the back collar still covers the neck in some pose, go back to painting the front layer, keep cleaning up the edge with the eraser, and apply again.

The front and back layers deform together, but which pixels to erase is still up to you. Finally save the `.psd2live` project to keep the layering, the texture changes and the history.

## 6. The same method handles hair and a shoulder ornament

The shoulder ornament and hair below also need part in front and part behind. First find the layer the ornament to be layered is on, and the hair layer that should sit in between.

![Before: the occlusion between the hair and the shoulder ornament needs adjusting](../../imgs/depth-split/shoulder-before.webp)

*First decide which parts of the ornament should show in front of the hair and which should hide behind it.*

Select the ornament's layer and the relevant hair together, and right-click the **ornament's layer** to run front/back layering. Then, on the new front layer, erase the parts of the ornament the hair should cover.

![Erasing on the shoulder ornament's front layer](../../imgs/depth-split/shoulder-erase.webp)

*Trim only what should go behind, on the new front layer; the back layer keeps the full content.*

After applying, check where the hair meets the ornament, and move the relevant parameters to watch the occlusion in motion.

![After: the hair and the shoulder ornament interleave front and back](../../imgs/depth-split/shoulder-result.webp)

*The result: one ornament layer can provide both front and back content.*

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| The quick layering menu on the canvas is missing | Switch to Object mode and select the mesh to layer together with at least one object in between; or open the dialog from **Front/back layers…** in the hierarchy tree |
| The menu names the wrong object | Right-click the object to duplicate; in the collar example it should be the top, not the neck |
| Nothing looks different after creation | The new front layer starts as a full copy; erase the part of it that should be covered |
| A prompt says to apply or discard painting first | Deal with the current uncommitted paint session first, then create the front/back layers |
| Fine from the front, but still clipping when turning | Check several poses of the relevant parameters, go back to the front layer and erase more and clean up the edge |
