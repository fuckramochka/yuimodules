package com.amegram.mods.md3player;

import android.content.Context;
import android.view.View;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import app.amegram.hot.api.HotHost;
import app.amegram.hot.api.HotMd3;
import app.amegram.hot.api.HotModule;

/** HMOD service adapter for the MD3 player UI hosted by the client APK. */
public final class Md3PlayerModule implements HotModule {
    private HotHost host;

    @Override public String moduleId() { return "md3player"; }

    @Override public void onAttach(Context appContext, HotHost host) {
        this.host = host;
        host.registerService("md3player", new Service());
    }

    @Override public void onDetach() {
        try {
            if (app.exteraless.player.PlayerSheet.instance != null) {
                app.exteraless.player.PlayerSheet.instance.dismissImmediately();
            }
        } catch (Throwable ignored) { }
        if (host != null) {
            host.unregisterService("md3player");
            host = null;
        }
    }

    private static final class Service implements HotMd3 {
        private MessageObject now() {
            try { return MediaController.getInstance().getPlayingMessageObject(); }
            catch (Throwable ignored) { return null; }
        }

        @Override public boolean sheetForMusic() {
            MessageObject message = now();
            return message != null && message.isMusic();
        }

        @Override public Object createSheet(Context context, Theme.ResourcesProvider provider) {
            try {
                if (app.exteraless.player.PlayerSheet.instance != null) {
                    app.exteraless.player.PlayerSheet.instance.dismissImmediately();
                }
                BottomSheet sheet = new app.exteraless.player.PlayerSheet(context, provider);
                return sheetForMusic() ? sheet
                        : new org.telegram.ui.Components.AudioPlayerAlert(context, provider);
            } catch (Throwable error) {
                org.telegram.messenger.FileLog.e(error);
                return new org.telegram.ui.Components.AudioPlayerAlert(context, provider);
            }
        }

        @Override public boolean miniForMusic() { return sheetForMusic(); }

        @Override public void clearPlaylistState() {
            try { MediaController.getInstance().clearMusicPlaylistState(); }
            catch (Throwable ignored) { }
        }

        @Override public int miniBarHeightDp() {
            return app.exteraless.player.PlayerBarView.HEIGHT_DP;
        }

        @Override public View createMiniBar(Context context, Theme.ResourcesProvider provider,
                                             Runnable onClick, Runnable onClose) {
            try {
                return new app.exteraless.player.PlayerBarView(context, provider, onClick, onClose);
            } catch (Throwable error) {
                org.telegram.messenger.FileLog.e(error);
                return null;
            }
        }
    }
}
