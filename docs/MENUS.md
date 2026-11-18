# Menus from files

Since Vystorm Core 0.65.0 you can design your own screen menus and add them to the server as files, without writing a
plugin. A menu is one file in the format `vystorm-menu` (version 1). The Vystorm menu editor, a web page that will be
available on vystorm.net, draws the menu the way the game shows it and saves this file. You can also write the file by
hand; it is plain JSON.

## In short

1. Save the menu from the editor as `<id>.json`, for example `shop.json`. The file name must be the menu's id.
2. Put the file into `plugins/vystorm_core/menus/` on the server. Core creates the folder on its first start.
3. Run `/vadmin menus reload` (or restart). `/vadmin menus` lists every file: loaded, or skipped with the reason.
4. Open the menu with `/vmenu shop`.

A menu also opens

- with its own command, when the file names one (`"command": "shop"` gives `/shop`),
- from its tile in the main menu (`/vmenu`), unless the file says `"mainMenu": false`,
- from another file menu (`{ "open": "shop" }`), and from plugins (`api.gui().fileMenus().open(player, "shop")`).

Switch for the whole feature: `menus.enabled` in Core's `config.yml` (also in `/vadmin features`). With it off, no
file is read and no menu, tile or command exists.

## A small example

```json
{ "format": "vystorm-menu", "version": 1, "id": "shop", "title": "<gold>Shop", "command": "shop",
  "frame": "menu", "size": { "width": 1.2, "height": 0.7 }, "header": ["close"],
  "footer": "Hello {player}, you have %vystorm_vault_balance_formatted%",
  "icon": "EMERALD", "group": "economy",
  "widgets": [
    { "id": "tools", "type": "label", "u": 0.05, "v": 0.16, "width": 1.1, "height": 0.05, "text": "Tools" },
    { "id": "pick", "type": "tile", "u": 0.105, "v": 0.225, "width": 0.19, "height": 0.16,
      "text": "Pickaxe", "icon": "IRON_PICKAXE", "description": "Costs 5 coins",
      "click": { "actions": [ { "command": "eco take {player} 5", "as": "console" },
                              { "command": "kit pickaxe", "as": "player" },
                              { "message": "<green>Enjoy!" }, { "close": true } ],
                 "cooldown": 60, "permission": "shop.use" },
      "rightClick": { "actions": [ { "open": "shop-help" } ] } } ] }
```

## The menu

| Field | Values (default) | Meaning |
| --- | --- | --- |
| `format`, `version` | `"vystorm-menu"`, `1` | Required. |
| `id` | `a-z 0-9 _ -`, 1 to 32 characters | Required, equal to the file name. Not `list`, `search`, `settings`, `news`, `player`, `access`, `profile`, `inbox`, `einstellungen` (words of `/menu`). |
| `title` | text | Title in the frame, and name of the tile in the main menu. |
| `command` | a command name without slash | The menu's own command. A name that already belongs to another command is not taken (see "Commands"). |
| `permission` | `node` or `!node` | Who may open the menu. `!node` means: players who do not have it. |
| `frame` | `menu`, `chest`, `none` (`menu`) | What Core draws around your widgets: the look of the main menu, a grid of tiles like a chest, or only the panel. |
| `size` | `width` 0.2 to 4.5, `height` 0.2 to 1.6 (2.04 x 1.12) | Panel size for the frames `menu` and `none`. |
| `grid` | `columns` 1 to 12, `rows` 1 to 8 (9 x 3) | Cells of the frame `chest`; the panel size follows from it and from the player's display. |
| `header` | list of `display`, `search`, `close` (`["close"]`) | Buttons in the header: display settings, menu search, close. |
| `footer` | text | Line at the bottom while the pointer is on nothing. |
| `backdrop` | `black`, `dim`, `gray`, `white`, `none` (`black`) | What fills the view behind the panel. |
| `mode` | `auto`, `pinned`, `floating` (`auto`) | `auto` follows the player's setting. |
| `pages` | 1 to 99 (1) | The mouse wheel turns pages. |
| `mainMenu` | `true`, `false` (`true`) | `false` keeps the menu out of the main menu and out of the menu search. |
| `icon` | a material name such as `EMERALD` (`BOOK`) | Icon of the tile in the main menu. |
| `group` | `character`, `economy`, `community`, `world`, `building`, `admin` (none) | Section of the main menu that holds the tile; without it the tile is in the first section. |
| `widgets` | list | What the menu shows, in drawing order (later widgets lie on top). |

Lengths are panel units: a 16:9 screen at the default field of view shows 2.49 units in width and 1.40 in height.
`u` runs to the right and `v` downwards from the panel's top-left corner. The file holds
the lengths for UI size 1; Core multiplies them with the UI size a player chose in `/screen settings`. A panel that
is larger than a player's display is shrunk as a whole until it fits.

## Widgets

| Field | Values (default) | Meaning |
| --- | --- | --- |
| `id` | `a-z 0-9 _ . -`, unique | Not one of the frame's own: `title`, `footer`, `hint`, `display`, `search`, `close`, `chest`, `head-line`, `foot-line`. A tile `x` also uses `x.icon` and `x.name`. |
| `type` | `label`, `button`, `tile`, `icon`, `fill` | Text; a clickable box with text; a clickable box with icon and caption; an item icon; a coloured rectangle. |
| `u`, `v`, `width`, `height` | panel units | Position and size. |
| `slot` | 0 = top left, row by row | Only for a `tile` in a `chest` frame, instead of position and size. |
| `text` | text | Caption. Tile captions are cut to 18 characters (14 in a chest grid); other texts shrink to fit their box. |
| `icon` | material name | Required for `tile` and `icon`. An item this server version does not know is shown as paper, with a note in the console. So is an item without a flat picture of its own (chests, beds, banners, heads, carpets): screens draw icons from the game's flat item and block textures. The console names such items when the file is read; pick another icon for them. |
| `description` | text | Shown in the footer while the pointer is on the button or tile. |
| `align` | `start`, `center`, `end` (`center`) | Where the text sits in its box. |
| `textHeight` | panel units (0 = from the box, at most 0.08) | Line height of the text. |
| `fill`, `highlight` | `#RRGGBB` or `#AARRGGBB` | Box colour, and its colour under the pointer. |
| `enabled` | `true`, `false` (`true`) | A disabled button is grey and cannot be clicked. |
| `when` | `{ "permission": "node" or "!node", "page": n }` | Shows the widget only to matching players, or only on one page. |
| `click`, `rightClick` | see below | What a left or a right click does. Only buttons and tiles can be clicked. |

## Clicks

```json
"click": { "actions": [ ... ], "cooldown": 60, "permission": "shop.use" }
```

A player without the `permission` gets no reaction. During the `cooldown` (seconds, per player and widget, kept until
the server restarts) the player is told how long to wait. Then the actions run in order:

| Action | What happens |
| --- | --- |
| `{ "command": "warp shop", "as": "player" }` | The player runs the command with their own permissions. |
| `{ "command": "eco give {player} 5", "as": "console" }` | The console runs the command with full rights. |
| `{ "message": "<gray>Off you go" }` | A chat line for the player. |
| `{ "open": "other-id" }` | Opens another file menu; it gets a Back button. |
| `{ "back": true }` | Back to the screen the player came from, otherwise close. |
| `{ "close": true }` | Closes the menu. |

Commands may contain `{player}` (name) and `{uuid}`, nothing else. A leading slash is optional.

**Console commands run with full rights for whoever clicks.** Give such a click a `permission` or a `cooldown`, or put
the widget or the menu behind a permission. Core accepts a console command without any of these, and writes one
warning per such widget to the console when it reads the file. A console command with a `%placeholder%` is refused.
For a player whose name contains anything but letters, digits, `_` and `.`, console commands with `{player}` are not
run (possible on servers in offline mode).

## Text and placeholders

Texts are MiniMessage with colours and simple styles: `<gold>`, `<#rrggbb>`, `<bold>`, `<italic>`, `<underlined>`,
`<strikethrough>`, `<reset>` and their closing tags. Other tags are shown as written; a menu file cannot carry click
or hover events.

- `{player}` is the viewer's name, `{page}` and `{pages}` the current page and the page count.
- `%vystorm_<plugin>_<value>%` and `{plugin:value}` show the values of Vystorm plugins; `/vadmin placeholders` lists
  them. With PlaceholderAPI installed, placeholders of other plugins work too.
- Values are inserted as plain text. While a menu is open, texts with placeholders are looked up again every
  `menus.refresh-seconds` seconds (default 2; 0 = only when the menu opens).

## Commands

| Command | Does | Permission |
| --- | --- | --- |
| `/vmenu <id>` or `/vmenu menus:<id>` | Opens a file menu. If a plugin's menu already answers to the bare name, use `menus:<id>`. | `vystorm_core.menu`, plus the menu's own |
| the command a file names | Opens that menu. | the menu's own |
| `/vadmin menus` | Lists the files: loaded menus with their command and tile, remarks, and skipped files with every reason. | `vystorm_core.admin` |
| `/vadmin menus reload` | Reads the folder again. | `vystorm_core.admin` |

Core never takes a command name that exists already, also not one of the server itself (`/give`, `/home` of another
plugin): the menu loads, the command is left out and the console says so. The menu's own command is registered a
moment after the server has started, so that every plugin's commands are known.

## Players without screens

Where no screen can open (screens switched off with `screens.enabled` or `screens.off`, or the player is riding,
falling, swimming or was just hit), the menu opens as a chest: every button and tile the player may see is an item
with the text as name and the description below it, and left and right clicks do the same as in the screen. A chest
grid with 9 columns keeps its slots. Labels, icons, fills, the header buttons, the footer and pages are not shown in
the chest; tiles of all pages are listed together. `screens.off: [menus]` sends all file menus to the chest,
`screens.off: [menus:shop]` a single one.

## When a file is not loaded

`/vadmin menus` shows the reason, and so does the console when the file is read, for example

```
[Menus] shop.json skipped: widgets[3] "pick".click.actions[0].command: a console command may only contain {player} and {uuid}, not %vystorm_eco_bonus%
```

A file with a mistake is skipped as a whole; the other files load. Limits: 256 KB per file, 300 widgets, 256
characters per text or command, 16 actions per click, 200 files.
