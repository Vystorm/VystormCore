package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core.panel.internal.UiBridge;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Nur für Core ({@code panel.internal.PanelWriter} über {@link UiBridge}): Lesezugriff auf die Builder. Paket-privat, nicht Teil der Plugin-API. */
final class UiAccess implements UiBridge {
    /** Nur über {@code panel.internal.UiBridges} (Reflexion) erzeugt. */
    private UiAccess() {}

    @Override public Object title(Panel p) { return p.title; }
    @Override public Object subtitle(Panel p) { return p.subtitle; }
    @Override public Ui.Icon icon(Panel p) { return p.icon; }
    @Override public Panel.Size size(Panel p) { return p.size; }
    @Override public Panel.Theme theme(Panel p) { return p.theme; }
    @Override public String accent(Panel p) { return p.accent; }
    @Override public List<Ui.Element<?>> children(Panel p) { return Collections.unmodifiableList(p.children); }
    @Override public List<Ui.Button> footer(Panel p) { return Collections.unmodifiableList(p.footer); }
    @Override public String type(Ui.Element<?> e) { return e.type; }
    @Override public String id(Ui.Element<?> e) { return e.id; }
    @Override public Map<String, Object> fields(Ui.Element<?> e) { return Collections.unmodifiableMap(e.fields); }
    @Override public List<Object> tooltip(Ui.Element<?> e) { return Collections.unmodifiableList(e.tooltip); }
    @Override public List<Ui.Element<?>> children(Ui.Element<?> e) { return Collections.unmodifiableList(e.children); }
    @Override public Object handler(Ui.Element<?> e) { return e.handler; }
    @Override public Ui.Icon buttonIcon(Ui.Button b) { return b.icon; }
    @Override public List<Object[]> tabList(Ui.Tabs t) { return Collections.unmodifiableList(t.tabs); }
    @Override public List<Object[]> kvRows(Ui.Kv kv) { return Collections.unmodifiableList(kv.rows); }
    @Override public List<Map<String, Object>> columns(Ui.Table t) { return Collections.unmodifiableList(t.columns); }
    @Override public List<Ui.TableRow> rows(Ui.Table t) { return Collections.unmodifiableList(t.rows); }
    @Override public Ui.Change<Integer> onPage(Ui.Table t) { return t.onPage; }
    @Override public Ui.Change<Ui.Sort> onSort(Ui.Table t) { return t.onSort; }
    @Override public Ui.Change<String> onSelect(Ui.Table t) { return t.onSelect; }
    @Override public String key(Ui.TableRow r) { return r.key; }
    @Override public List<Object> cells(Ui.TableRow r) { return Collections.unmodifiableList(r.cells); }
    @Override public List<Object> rowTooltip(Ui.TableRow r) { return Collections.unmodifiableList(r.tooltip); }
    @Override public Object cellText(Ui.Cell c) { return c.text; }
    @Override public Double cellSort(Ui.Cell c) { return c.sort; }
    @Override public List<Ui.ListItem> items(Ui.ListView l) { return Collections.unmodifiableList(l.items); }
    @Override public String key(Ui.ListItem i) { return i.key; }
    @Override public Object title(Ui.ListItem i) { return i.title; }
    @Override public Object subtitle(Ui.ListItem i) { return i.subtitle; }
    @Override public Object right(Ui.ListItem i) { return i.right; }
    @Override public Object badge(Ui.ListItem i) { return i.badge; }
    @Override public Ui.Icon icon(Ui.ListItem i) { return i.icon; }
    @Override public boolean selected(Ui.ListItem i) { return i.selected; }
    @Override public List<Object[]> options(Ui.Select s) { return Collections.unmodifiableList(s.options); }
    @Override public String item(Ui.ItemIcon i) { return i.item; }
    @Override public String model(Ui.ItemIcon i) { return i.model; }
    @Override public int count(Ui.ItemIcon i) { return i.count; }
    @Override public boolean glint(Ui.ItemIcon i) { return i.glint; }
    @Override public boolean isTextRef(Object v) { return v instanceof Ui.TextRef; }
    @Override public Object textRef(Object v) { return ((Ui.TextRef) v).value(); }
    @Override public boolean isTextList(Object v) { return v instanceof Ui.TextList; }
    @Override public List<Object> textList(Object v) { return Collections.unmodifiableList(((Ui.TextList) v).lines()); }
    @Override public boolean isNav(Object v) { return v instanceof Ui.Nav; }
    @Override public String navPanel(Object v) { return ((Ui.Nav) v).panel(); }
    @Override public String navArg(Object v) { return ((Ui.Nav) v).arg(); }
}
