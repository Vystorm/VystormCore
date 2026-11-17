package Vystorm.vystorm_core._uiMethods.bookGui;

import java.util.ArrayList;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public interface BookEditorInterface {
    public int nextPage();

    public int previousPage();

    public void replaceLine(int pageNumber, int lineNumber, String newContent);

    public void addPage(ArrayList<String> pageContent);

    public void removePage(int pageNumber);

    public ArrayList<String> getPageContent(int pageNumber);

    public int getTotalPages();
}
