package Vystorm.vystorm_core._uiMethods.bookGui;

import org.bukkit.entity.Player;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
@SuppressWarnings("removal")
public class BookHandler implements BookInterface {
    @Override
    public void openBookGui(String bookName, Player player) {
    }

    @Override
    public BookEditorInterface getBookEditor(String bookName) {
        return null;
    }

    @Override
    public void createBlancBook(String bookName) {
    }

    @Override
    public void removeBook(String bookName) {
    }
}
