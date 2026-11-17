package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import java.util.Map;

/**
 * Lesezugriff von {@link PanelWriter} auf die Panel-Builder (Core-intern, nicht Teil der Plugin-API). Die Umsetzung liegt
 * paket-privat in {@code panel.UiAccess}; geholt wird sie nur über den paket-privaten {@link UiBridges} (kein öffentliches
 * Anmelden/Abfragen). Listen sind nur lesbar. @since 0.25.1
 */
public interface UiBridge {
    Object title(Panel p);
    Object subtitle(Panel p);
    Ui.Icon icon(Panel p);
    Panel.Size size(Panel p);
    Panel.Theme theme(Panel p);
    String accent(Panel p);
    List<Ui.Element<?>> children(Panel p);
    List<Ui.Button> footer(Panel p);
    String type(Ui.Element<?> e);
    String id(Ui.Element<?> e);
    Map<String, Object> fields(Ui.Element<?> e);
    List<Object> tooltip(Ui.Element<?> e);
    List<Ui.Element<?>> children(Ui.Element<?> e);
    Object handler(Ui.Element<?> e);
    Ui.Icon buttonIcon(Ui.Button b);
    List<Object[]> tabList(Ui.Tabs t);
    List<Object[]> kvRows(Ui.Kv kv);
    List<Map<String, Object>> columns(Ui.Table t);
    List<Ui.TableRow> rows(Ui.Table t);
    Ui.Change<Integer> onPage(Ui.Table t);
    Ui.Change<Ui.Sort> onSort(Ui.Table t);
    Ui.Change<String> onSelect(Ui.Table t);
    String key(Ui.TableRow r);
    List<Object> cells(Ui.TableRow r);
    List<Object> rowTooltip(Ui.TableRow r);
    Object cellText(Ui.Cell c);
    Double cellSort(Ui.Cell c);
    List<Ui.ListItem> items(Ui.ListView l);
    String key(Ui.ListItem i);
    Object title(Ui.ListItem i);
    Object subtitle(Ui.ListItem i);
    Object right(Ui.ListItem i);
    Object badge(Ui.ListItem i);
    Ui.Icon icon(Ui.ListItem i);
    boolean selected(Ui.ListItem i);
    List<Object[]> options(Ui.Select s);
    String item(Ui.ItemIcon i);
    String model(Ui.ItemIcon i);
    int count(Ui.ItemIcon i);
    boolean glint(Ui.ItemIcon i);
    boolean isTextRef(Object v);
    Object textRef(Object v);
    boolean isTextList(Object v);
    List<Object> textList(Object v);
    boolean isNav(Object v);
    String navPanel(Object v);
    String navArg(Object v);
}
