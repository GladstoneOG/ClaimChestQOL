# ClaimChestQOL

> **A cooperative plot claiming, plot-wide crafting, and inventory management Quality-of-Life (QoL) mod for Hytale servers.**

---

> [!IMPORTANT]
> **Designed for Friends, Not Public Servers**
> This mod was originally built and optimized for **private co-op servers and small friend groups**, rather than large public or competitive servers.
> 
> Features like plot-wide chest crafting, plot-wide inventory quick-stacking, generous area claims, and seamless plot merging prioritize **convenience, collaboration, and building quality-of-life** over strict competitive grief prevention or tight economy balance.

---

## Features

### 1. Visual Plot Claiming & Live Outlines
- **Two-Corner Claiming Tool (`/claim`):** Receive the Claim Tool (Wooden Hoe). Left-Click to set Corner 1, walk to Corner 2, and Right-Click to claim.
- **Live Glowing Green Selection:** As soon as Corner 1 is placed, a vibrant glowing green 3D bounding box follows your character in real-time as you walk toward Corner 2.
- **Two Claiming Height Modes:**
  - **Bedrock-to-Ceiling (Full Vertical Column):** Automatically protects from sky ceiling to bedrock (`/claim bedrock` or `/claimbedrock`).
  - **Specific-Height (3D Cuboid):** Operates like Minecraft's `/fill` command, protecting only between the two selected Y levels (`/claim fill`, `/claim height`, or `/claimfill`).
- **Nearby Plot Visualizer:** Holding the Claim Tool renders nearby plots in glowing **Green** (your own) or **Red / Yellow** (other players').

### 2. Automatic Plot Merging
- When you claim a region that overlaps in 3D with one (or more) of your own existing plots, they are **automatically merged into a single unified plot**.
- **Multi-Box Geometry:** Merged plots preserve the exact shapes of constituent sections (L-shapes, T-shapes, cross-compounds) without claiming unselected empty corners.
- **Shared Plot Slot & Permissions:** Merged plots count as **1 plot** toward server plot limits, share all trusted friends, and unite all chests for crafting and quick-stacking.

### 3. In-Game World Map Territories (`M`)
- **Pixel-Accurate Territory Shading:** Claimed plots are shaded directly onto the in-game World Map with semi-transparent owner colors and crisp borders so terrain and structures remain visible underneath.
- **Plot Owner Pins:** Every plot shows a map pin with the owner's name, dimensions, height range, and merge status.
- **Multi-Height Overlap Visualization:** If two or more Specific-Height plots occupy the same horizontal `(X, Z)` area at different Y heights:
  - The map renders a **diagonal striped dual-color hatch pattern** surrounded by an alternating **Gold / Magenta border**.
  - A dedicated `OVERLAP` map pin appears at the intersection.
- **Live Updates:** Claiming, merging, or unclaiming a plot immediately updates map tiles for all online players.

### 4. Plot-Wide Crafting from Any Chest
- Open any **Crafting Bench** or portable **Pocket Crafting (`C`)** window inside your plot: available crafting materials are automatically pulled from **all chests and containers located anywhere in the plot**!
- No more running back and forth between chests to grab ingredients.

### 5. Plot-Wide Quick Stack (`R` Key)
- Open any single chest inside your plot and press **R** (or click the Quick Stack button).
- Any item in your inventory matching existing stacks across **any chest in the plot** is instantly sorted and deposited into the appropriate chests.

### 6. Plot Protection & Friend Trust
- **Complete Protection:** Protects blocks, containers, workbenches, doors, entities, explosions (mobs/TNT), and fire damage from unauthorized players.
- **Instant Feedback:** Attempting unauthorized interactions notifies the player in chat who owns the plot and briefly flashes the plot boundary in red/yellow.
- **Trust System:** Use `/trust <friend>` to grant friends full access to build, open containers, craft from plot chests, and quick-stack.

### 7. Server Admin Configuration (`config.json` & `/plotconfig`)
- **Configurable Settings:**
  - **Plot Decay:** Inactive player plots can automatically decay and expire after a configurable duration. Active play and plot interactions refresh the decay timer.
  - **Size & Count Limits:** Enforce maximum plots per player, maximum side lengths, 2D area, and 3D height limits.
  - **Excluded Block Types:** Allow players to harvest or interact with specific blocks inside claimed plots (e.g., wild crops, berries, doors) using exact IDs or wildcards (`*Crop*`, `*Door*`).
  - **World Map Toggle:** Enable or disable plot territory rendering on the World Map.

---

## Command Reference

### Player Commands

| Command | Aliases | Description |
| :--- | :--- | :--- |
| `/claim` | `/plot`, `/claimtool` | Gives the Claim Tool (Wooden Hoe). |
| `/claim pos1` | `/claim 1` | Sets Corner 1 at your feet. |
| `/claim pos2` | `/claim 2` | Sets Corner 2 at your feet and confirms claim. |
| `/claim bedrock` | `/claimbedrock` | Switches claiming mode to Bedrock-to-Ceiling (full vertical column). |
| `/claim fill` | `/claimfill`, `/claimheight`, `/claim3d` | Switches claiming mode to Specific-Height (3D cuboid). |
| `/claim switch` | | Toggles between Bedrock-to-Ceiling and Specific-Height modes. |
| `/claim mode` | | Toggles Claim Tool mode for bare hands / held items. |
| `/claim show` | `/claim view` | Highlights your plot (or the plot at your feet) for 10 seconds. |
| `/claim info` | | Displays owner, coordinates, trusted friends, connected chests, and decay timer. |
| `/claim list` | | Lists all plots you currently own. |
| `/claim undo` | `/unclaim` | Undoes a pending corner selection or removes the plot you are standing in. |
| `/trust <friend>` | | Trusts a friend on your plot (build, chest, craft, and quick-stack access). |
| `/untrust <friend>` | | Revokes trust from a friend on your plot. |
| `/trustlist` | | Lists all trusted friends for your plot. |

### Admin Configuration Commands

| Command | Description |
| :--- | :--- |
| `/plotconfig` *(or `/plotconfig info`)* | Displays all current server settings, limits, and excluded blocks. |
| `/plotconfig decay <on\|off\|hours>` | Enables/disables plot decay or sets decay hours (e.g. `168`, `72`, `7d`). |
| `/plotconfig maxplots <count>` | Sets maximum plots allowed per player (`0` = unlimited). |
| `/plotconfig maxsize <blocks>` | Sets maximum X/Z side length in blocks (`0` = unlimited). |
| `/plotconfig maxarea <blocks2D>` | Sets maximum 2D area in blocks (`0` = unlimited). |
| `/plotconfig maxheight <blocksY>` | Sets maximum height in `/claimfill` mode (`0` = unlimited). |
| `/plotconfig exclude <id\|hand\|look>` | Excludes a block type/wildcard from plot protection (e.g. `*Crop*`, `*Door*`, `hand`, `look`). |
| `/plotconfig include <id\|hand\|look>` | Removes a block type/wildcard from exclusion (re-enables protection). |
| `/plotconfig excludelist` | Lists all block types/wildcards currently excluded from protection. |
| `/plotconfig map <on\|off\|refresh>` | Enables, disables, or refreshes claimed plot territory rendering on the World Map. |
| `/plotconfig reload` | Reloads `config.json` from disk and refreshes the World Map. |

---

## Configuration (`config.json`)

The server configuration file is located at `mods/com.claimchestqol_ClaimChestQOL/config.json`:

```json
{
  "plotDecayEnabled": false,
  "plotDecayHours": 168.0,
  "maxPlotsPerPlayer": 5,
  "maxPlotSideLength": 128,
  "maxPlotArea2D": 16384,
  "maxPlotHeightY": 256,
  "excludedBlockTypes": [],
  "showPlotsOnWorldMap": true
}
```

- **`plotDecayEnabled`** *(boolean)*: When `true`, plots owned by players who have been inactive longer than `plotDecayHours` automatically decay and unclaim.
- **`plotDecayHours`** *(number)*: Number of hours of owner inactivity before decay (default: `168.0` = 7 days).
- **`maxPlotsPerPlayer`** *(integer)*: Maximum number of separate plots a player can own (`0` = unlimited). Merging overlapping claims into an existing plot does not consume additional slots.
- **`maxPlotSideLength`** *(integer)*: Maximum width or length of a plot in blocks (`0` = unlimited).
- **`maxPlotArea2D`** *(integer)*: Maximum 2D horizontal footprint area in blocks (`0` = unlimited).
- **`maxPlotHeightY`** *(integer)*: Maximum vertical height in Specific-Height mode (`0` = unlimited).
- **`excludedBlockTypes`** *(list of strings)*: Block type IDs or wildcards that bypass plot protection (e.g., `["*Crop*", "*Berry*", "*Door*"]`).
- **`showPlotsOnWorldMap`** *(boolean)*: Toggles rendering of plot territory tints, borders, and markers on the World Map.

---

## Building & Installation

### Requirements
- **Java 25+** (JDK)
- **Hytale Server JAR** (`HytaleServer.jar`)

### Building from Source
Run the included PowerShell build script:
```powershell
.\build.ps1
```
The script compiles the source files against `HytaleServer.jar`, packages `ClaimChestQOL-1.0.0.jar` in `build/dist/`, and automatically installs it into your server's `mods/` directory.

### Manual Installation
1. Place `ClaimChestQOL-1.0.0.jar` into your server's `mods/` directory.
2. Start or restart your Hytale server.
3. Verify in the server console:
   ```
   [ClaimChestQOL] Started! Plot claiming, plot merging, World Map territories, admin config, protection, plot-wide crafting, and Quick Stack (R) active.
   ```

---

## License
MIT License. Created by [GladstoneOG](https://github.com/GladstoneOG).
