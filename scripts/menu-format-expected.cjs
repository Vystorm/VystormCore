/*
 * menu-format-expected.cjs - keeps Core's file-menu loader in step with the editor's format library.
 *
 *   node scripts/menu-format-expected.cjs <folder of the screens editor> [output folder]
 *
 * <folder of the screens editor> holds lib/screen-layout.js and lib/menu-format.js (the JavaScript port of Core's
 * layout numbers and the "vystorm-menu" format). The script
 *   1. writes the editor's two demo menus as files (main.json, locks-general.json) into the output folder
 *      (default: src/test/resources/menus), exactly as the editor saves them;
 *   2. prints the positions the editor computes for a few named widgets at 1280x720 (16:9, FOV 70, UI size 1), as
 *      Java lines for FileMenuLayoutTest.
 * Run it again after the format library changed and paste the printed lines into the test.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const editor = process.argv[2];
if (!editor) { console.error('usage: node scripts/menu-format-expected.cjs <folder of the screens editor> [output folder]'); process.exit(2); }
const out = process.argv[3] || path.join(__dirname, '..', 'src', 'test', 'resources', 'menus');

globalThis.self = globalThis;
require(path.resolve(editor, 'lib', 'screen-layout.js'));
require(path.resolve(editor, 'lib', 'menu-format.js'));
const NS = globalThis.VystormScreensEditor, L = NS.Layout, F = NS.Format;

fs.mkdirSync(out, { recursive: true });
const demos = [F.demoMenu(), F.demoChest()];
for (const menu of demos) {
  const problems = F.validate(menu).filter(p => p.level === 'error');
  if (problems.length) throw new Error(menu.id + ' has errors in the editor: ' + JSON.stringify(problems));
  fs.writeFileSync(path.join(out, menu.id + '.json'), F.toJSON(menu), 'utf8');
}

const profile = L.profile(1280 / 720, 70, 1.0);
const wanted = {
  'main': ['title', 'display', 'search', 'close', 'section-character', 'herbarium', 'herbarium.icon', 'market', 'market.icon',
    'rent-buy-plots', 'party', 'section-world', 'footer'],
  'locks-general': ['title', 'close', 'head-line', 'auto-protect', 'auto-protect.icon', 'auto-protect.name', 'max-buddies', 'back',
    'back.icon', 'back.name', 'foot-line', 'hint']
};
function num(v) { return String(Math.round(v * 1e6) / 1e6); }
for (const menu of demos) {
  // permissions []: a viewer without any permission (so "!vystorm.guest" widgets are shown)
  const ex = F.expand(menu, { profile: profile, page: 1, permissions: [] });
  console.log('// ' + menu.id + ': panel and widgets at 1280x720');
  console.log('panel("' + menu.id + '", ' + num(ex.layout.width) + ', ' + num(ex.layout.height) + ', ' + ex.layout.widgets.length + ');');
  for (const id of wanted[menu.id]) {
    const w = ex.layout.widgets.find(x => x.id === id);
    if (!w) throw new Error(menu.id + ': the editor has no widget ' + id);
    console.log('box("' + menu.id + '", "' + id + '", ' + [w.u, w.v, w.width, w.height].map(num).join(', ') + ');');
  }
  console.log('fits("' + menu.id + '", ' + F.fits(ex.layout, profile) + ');');
}
