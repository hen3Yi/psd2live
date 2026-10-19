# PSD2Live · InkPet fork

[中文](#中文) | [English](#english)

## 中文

这是 [PSD2Live](https://github.com/tsunehimatoi/psd2live) 的一个修改版，给 Live2D 桌宠 [InkPet 墨宠](https://github.com/hen3Yi/inkpet) 用。和上游一样按 [GPL-3.0](LICENSE) 发布。

- 基于上游提交 [`f5b7931`](https://github.com/tsunehimatoi/psd2live/commit/f5b79316120480eb6e57ca4cc1c92f955a7c11df)（2026-09-03，版本 0.1.0）。上游后来发展成了 3.x 的完整编辑器；这个分支停在 0.1，只加了下面这些改动。
- 修改者 hen3Yi，修改时间 2026-09-21～22、2026-10-16。代码里改过的地方都标了 `InkPet fork`，完整差异见本分支在 `f5b7931` 之后的提交。
- 依赖 [umamo](https://github.com/umamoorg/umamo) 的提交 [`a6efc01`](https://github.com/umamoorg/umamo/commit/a6efc01567eb7cb4ecdc0daea8161f705de9be71)（2026-09-20），没有改动。

### 改了什么

- **尾巴物理**：名字是 `tail`（或 `尾巴`、`尾`）的图层生成三层变形器：
  - 根部 `DeformTailRoot`：跟着身体走；
  - 物理摆动 `DeformTailPhysics`：参数 `ParamTail`，由身体角度驱动，physics3.json 里多一条 `PhysicsTail`；
  - 程序甩尾 `DeformTailWag`：参数 `ParamTailWag`，留给宿主程序用，比如待机轻摆、点一下甩尾。
- **头发网格**：细碎发丝的轮廓坍缩或剖分失败时，只丢掉那一小块。以前整层会退化成铺满图层的矩形，出现拉到画布四角的细线。
- **闭眼**：
  - 闭成一条直线，落在闭眼图层的中心；没有闭眼图层时，落在眼白高度的 62% 处。上游是 U 形曲线。
  - 睫毛压到原来的 15%。
  - 睁开度在 0.235～0.385 之间时，闭眼图淡入，睫毛、眼白、瞳孔同步淡出。
- **嘴和耳朵**直接跟面部九轴网走，不再挂单独的区域变形器。以前细线状的嘴可能被拉偏到看不见。
- **左右拆分**时，每一侧的连通块全部保留，比如画在睫毛层上的眼皮褶。
- **细小图层**（比如嘴）的网格间距不超过内容短边的 1/3，网格不再塌成一条线。
- **透明度门槛**默认从 8 改成 64，滤掉画布上淡到看不见的杂点。
- **中立姿态检查**放宽，允许整张画布大小、四周透明的图层。
- **model3.json** 总是写上 `HitAreas`（Head / Body）。
- **眨眼动作**中间有停顿：闭 0.08 秒，保持 0.34 秒，睁 0.16 秒。动作改成 60 fps。

### 下载

[Releases](https://github.com/hen3Yi/psd2live/releases) 页面有这几样：

| 文件 | 说明 |
| --- | --- |
| `PSD2Live-0.1.0-inkpet.1-windows-x64-portable.zip` | 免安装包，自带 Java。解压后运行 `PSD2Live.exe`，不带参数是图形界面，带参数是命令行 |
| `PSD2Live-0.1.0-inkpet.1-windows-x64.jar` | 单个 jar，要自己装 Java 21 |
| `umamo-a6efc01-source.zip` | 编这个版本用的 umamo 源码。GPL-3.0 要求随程序提供对应的源码，本仓库的源码在同一个 tag 下 |

命令行用法和上游一样：

```powershell
PSD2Live.exe --input 角色.psd --output 输出目录 --lang zh
java -jar PSD2Live-0.1.0-inkpet.1-windows-x64.jar --input 角色.psd --output 输出目录 --lang zh
```

在 InkPet 里用：把免安装包解压到 InkPet 的 `tools\psd2live\`，让 `PSD2Live.exe` 正好在这个目录下。用 jar 的话，把它改名成 `PSD2Live.jar` 放进同一个目录，再装好 Java 21。

### 自己编

要 JDK 21。两个仓库放在同一个目录下：

```powershell
git clone -b inkpet https://github.com/hen3Yi/psd2live.git
git clone https://github.com/umamoorg/umamo.git
git -C umamo checkout a6efc01567eb7cb4ecdc0daea8161f705de9be71
cd psd2live
..\umamo\gradlew.bat -p . test
..\umamo\gradlew.bat -p . packageUberJarForCurrentOS createDistributable
```

编好的 jar 在 `build\compose\jars\`，免安装包在 `build\compose\binaries\main\app\PSD2Live\`。

## English

A modified version of [PSD2Live](https://github.com/tsunehimatoi/psd2live), maintained for the Live2D desktop pet [InkPet](https://github.com/hen3Yi/inkpet). Licensed under [GPL-3.0](LICENSE), like upstream.

- Based on upstream commit [`f5b7931`](https://github.com/tsunehimatoi/psd2live/commit/f5b79316120480eb6e57ca4cc1c92f955a7c11df) (2026-09-03, version 0.1.0). Upstream has since grown into a full 3.x editor; this branch stays on 0.1 and adds only the changes below.
- Modified by hen3Yi on 2026-09-21/22 and 2026-10-16. Changed code is marked `InkPet fork`; the full diff is the commits on this branch after `f5b7931`.
- Built against [umamo](https://github.com/umamoorg/umamo) commit [`a6efc01`](https://github.com/umamoorg/umamo/commit/a6efc01567eb7cb4ecdc0daea8161f705de9be71) (2026-09-20), unmodified.

### Changes

- **Tail physics**: a layer named `tail` (or `尾巴`, `尾`) gets three warps:
  - `DeformTailRoot` follows the body;
  - `DeformTailPhysics` swings with `ParamTail`, driven by the body angles (physics3.json gains `PhysicsTail`);
  - `DeformTailWag` uses `ParamTailWag`, left to the host program for an idle sway or a wag on click.
- **Hair meshing**: a thin detached strand that collapses or fails to triangulate is dropped on its own. The whole layer used to fall back to a rectangle, which stretched slivers to the canvas corners.
- **Eye closing**:
  - The eye closes onto a straight line at the eye-close art, or at 62% of the eye white when there is none. Upstream uses a U curve.
  - Lashes are squashed to 15%.
  - Between eye openness 0.235 and 0.385 the eye-close art fades in while the lash, eye white and iris fade out.
- **Mouth and ears** follow the face nine-pose warp directly. A thin mouth line could be pulled off its art before.
- **Left/right splitting** keeps every component on each side, such as an eyelid crease drawn on the lash layer.
- **Thin layers** such as a mouth get a grid spacing of at most a third of their short side, so the mesh no longer collapses into a line.
- **Alpha threshold** default goes from 8 to 64, ignoring faint stray pixels on the canvas.
- **The neutral-pose check** tolerates layers saved at full canvas size with transparent margins.
- **model3.json** always lists `HitAreas` (Head / Body).
- **The blink motion** holds briefly: close 0.08 s, hold 0.34 s, open 0.16 s. Motions use 60 fps.

### Downloads

The [Releases](https://github.com/hen3Yi/psd2live/releases) page has:

| File | What it is |
| --- | --- |
| `PSD2Live-0.1.0-inkpet.1-windows-x64-portable.zip` | Portable app with a bundled Java runtime. Run `PSD2Live.exe`: the GUI without arguments, the command line with them |
| `PSD2Live-0.1.0-inkpet.1-windows-x64.jar` | A single jar; needs Java 21 |
| `umamo-a6efc01-source.zip` | The umamo source this build uses. GPL-3.0 requires the corresponding source alongside the program; this repository's source is under the same tag |

The command line is the same as upstream:

```powershell
PSD2Live.exe --input character.psd --output out --lang en
java -jar PSD2Live-0.1.0-inkpet.1-windows-x64.jar --input character.psd --output out --lang en
```

With InkPet: unzip the portable app into InkPet's `tools\psd2live\` so that `PSD2Live.exe` sits directly in that folder. For the jar, rename it to `PSD2Live.jar`, put it in the same folder and install Java 21.

### Building

Needs JDK 21. Put both repositories side by side:

```powershell
git clone -b inkpet https://github.com/hen3Yi/psd2live.git
git clone https://github.com/umamoorg/umamo.git
git -C umamo checkout a6efc01567eb7cb4ecdc0daea8161f705de9be71
cd psd2live
..\umamo\gradlew.bat -p . test
..\umamo\gradlew.bat -p . packageUberJarForCurrentOS createDistributable
```

The jar lands in `build\compose\jars\`, the portable app in `build\compose\binaries\main\app\PSD2Live\`.
