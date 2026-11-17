package Vystorm.vystorm_core._uiMethods.bookGui;

import org.bukkit.entity.Player;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public interface BookInterface {
    public void openBookGui(String bookName, Player player);

    @SuppressWarnings("removal")
    public BookEditorInterface getBookEditor(String bookName);

    public void createBlancBook(String bookName);

    public void removeBook(String bookName);
}
