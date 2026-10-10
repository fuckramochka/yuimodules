package app.amegram.hot.api;

import android.content.Context;
import android.view.View;
import org.telegram.ui.ActionBar.Theme;

/** Runtime contract for the MD3 music player module. */
public interface HotMd3 {
    boolean sheetForMusic();
    Object createSheet(Context context, Theme.ResourcesProvider resourcesProvider);
    boolean miniForMusic();
    void clearPlaylistState();
    int miniBarHeightDp();
    View createMiniBar(Context context, Theme.ResourcesProvider resourcesProvider,
                       Runnable onClick, Runnable onClose);
}
