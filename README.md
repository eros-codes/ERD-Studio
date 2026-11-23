# ERD Studio 2.0

A personal, drawSQL-style database designer for Windows (Java 21 + JavaFX), packaged as a standalone `.exe`.

> Persian guide: [GUIDE-fa.md](GUIDE-fa.md)

## Features

**Canvas**: infinite dot grid, zoom at the pointer, pan, minimap, snap to grid, box select, light and dark themes, multiple diagrams in **tabs**.

**Tables**: PK/FK/UQ/AI/nullable markers, inline rename, a properties panel (types suggested per dialect, defaults, comments, indexes), and **quick add** (`email varchar(255) nn uq`, `user_id -> users`).

**Relations**: drag from a row's edge (or Alt+drag) to another column. Orthogonal lines route around tables, with crow's-foot ends. Cardinality, ON DELETE / ON UPDATE, and one-click "Add foreign key to…".

**Areas**: coloured groups (Ctrl+G groups the selection). Drag an area by its title and everything inside moves with it. Areas export as DBML TableGroups.

**Tidy up** (Ctrl+L): arranges everything neatly with an animation. Each area's tables are laid out inside it, and areas plus loose tables are packed into a widescreen shape.

**Arrange**: align left/centre/right/top/middle/bottom, distribute evenly.

**Import**: SQL (PostgreSQL, MySQL/MariaDB, SQLite, SQL Server), **Prisma**, **DBML** (format auto-detected), drag & drop files, and **live databases** (PostgreSQL, MySQL/MariaDB; read-only connection).

**Export**: SQL for 4 dialects with type conversion, **Prisma**, **DBML**, **Mermaid**, **Markdown docs**, SVG, and a **high-quality screenshot** (1–4×, gradient frame / canvas / white / transparent, light or dark, title, grid, whole diagram / selection / visible area, copy to clipboard).

**Versions & migrations**: save named versions inside the file and generate the ALTER script between any two versions or the current design. Tables and columns are matched by id, so renames stay renames. Works for all 4 dialects; SQLite uses the table-rebuild pattern. Tested against real PostgreSQL 16, MariaDB 10.11 and SQLite 3.

**Safety & comfort**: undo/redo (200 steps), crash-recovery backups, reopening last session, recent files, **command palette** (Ctrl+K), settings (default dialect, id name, timestamp columns, FK naming, backup interval).

## Requirements

* JDK 21 (Temurin / Microsoft / Oracle). `jpackage` is included.
* Maven 3.9+

## Run / build

```bat
run.bat               :: run from source
build-exe.bat         :: target\app\ERD Studio\ERD Studio.exe  (bundles its own Java)
build-exe.bat installer   :: target\installer\ERD-Studio-Setup-<ver>.exe + SHA256SUMS.txt (needs WiX Toolset 3.x on PATH)
```

The installer is per-user (no admin), adds Start menu + desktop shortcuts, associates `.erd` files
(a second launch hands the file to the running window), and upgrades in place thanks to a fixed
`--win-upgrade-uuid` (never change it). The version lives only in `pom.xml`.

### Releases & auto-update

Push a tag `vX.Y.Z` matching the pom version; `.github/workflows/release.yml` builds the installer on
Windows and publishes a GitHub Release with the Setup exe and `SHA256SUMS.txt`. The app checks
`eros-codes/ERD-Studio` releases on start (≤ twice a day, Settings toggle, Help → Check for updates),
offers Update / Later / Skip, downloads with progress, verifies SHA-256 and runs the installer.
Network problems never surface during the automatic check.

The `ERD Studio` folder is portable: copy it anywhere and pin the exe.

### launch4j alternative

`mvn clean package` builds `target\erd-studio.jar` with everything inside. Then:

```bat
jlink --add-modules java.base,java.desktop,java.logging,java.prefs,java.sql,java.xml,java.naming,java.management,java.net.http,java.security.jgss,java.security.sasl,jdk.crypto.ec,jdk.unsupported,jdk.unsupported.desktop,jdk.charsets --strip-debug --no-header-files --no-man-pages --output target\jre
launch4jc packaging\launch4j.xml
```

Ship `ERD Studio.exe` together with the `jre` folder.

## AI assistants (MCP)

`ERD Studio --mcp` (installed as `erd-studio-mcp.exe`, a console launcher next to the app) is a stdio MCP server with
18 tools: list/read/export projects (SQL, Prisma, DBML, Mermaid, Markdown), versions and migrations, and edits
(create project, import schema, add/update/delete tables, columns, foreign keys, indexes, tidy, save version).
When the window is open, calls are forwarded over the loopback single-instance socket and run on the tab's editor
(live, one undo step each, autosaved); otherwise the .erd files are edited directly. Help → Connect an AI assistant
adds it to Claude Desktop and shows the Claude Code / generic commands; a read-only switch hides all write tools.
Projects can't be deleted through MCP.

## Keyboard

| Action | Keys |
|---|---|
| Command palette | Ctrl+K |
| New project / home / open file / save / close tab | Ctrl+N / Ctrl+Shift+H / Ctrl+O / Ctrl+S / Ctrl+W |
| Export project file (.erd) | Ctrl+Shift+S |
| Import file / import from DB | Ctrl+I / Ctrl+Shift+I |
| Export SQL / screenshot | Ctrl+E / Ctrl+Shift+E |
| Versions & migrations | Ctrl+Shift+M |
| Tidy up / group into area | Ctrl+L / Ctrl+G |
| Undo / redo | Ctrl+Z / Ctrl+Y |
| Copy / cut / paste / duplicate | Ctrl+C / X / V / D |
| New table / note (canvas) | T / N |
| Rename | Enter, F2 or double-click |
| Zoom / fit / 100% | Mouse wheel or pinch, Shift+1, Ctrl+0 |
| Pan | Right-drag, Space+drag, touchpad two-finger scroll, Ctrl+wheel (mouse) |
| Box select | Drag on empty canvas (Ctrl/Shift adds to selection) |
| Panels / dark mode / settings | Ctrl+B, Ctrl+Shift+B / Ctrl+Shift+L / Ctrl+, |

## Where things are stored

* Projects: one `.erd` file per project (readable JSON, written atomically; versions live inside) in
  `Documents\ERD Studio\Projects` (changeable in Settings). Pins / last-opened times: `.library.json`;
  deleted projects: `.trash` (kept 30 days). Library projects autosave ~1 s after every change (Settings toggle).
* Older `*.erd.json` files still open; use "Add to library" or File → Import project file.
* Crash-recovery backups: `%APPDATA%\ERD Studio\recovery` (deleted when you save or close normally).
* Settings: Windows registry (`java.util.prefs`). Database passwords are never stored.

## Project layout

```
model/    Diagram, Table, Column, Relation, TableIndex, Note, Area, Version, Dialect, Palette
io/       SqlLexer/SqlImporter, SqlExporter, TypeMapper, MigrationGenerator, PrismaIO, DbmlIO,
          DocsExporter (Mermaid + Markdown), DbReverse (JDBC), JsonStore, ImportResult
layout/   AutoLayout (layered layout + crossing reduction)
editor/   Editor (model, selection, undo, clipboard, tidy, align, versions), History, Settings
canvas/   DiagramCanvas, TableNode, RelationEdge, Routing, NoteNode, AreaNode, Minimap, SvgExporter
ui/       MainWindow (tabs, menus, autosave), Workspace, Sidebar, PropertiesPanel, CommandPalette,
          Import / Export / Screenshot / Versions / Database / Settings dialogs, Theme, Icons
```
