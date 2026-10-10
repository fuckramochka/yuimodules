package com.amegram.mods.fileorganization;

import android.content.Context;
import java.text.Normalizer;
import app.amegram.hot.api.HotFileOrganization;
import app.amegram.hot.api.HotHost;
import app.amegram.hot.api.HotModule;

/** Hot-updatable chat-to-folder naming policy. */
public final class FileOrganizationModule implements HotModule, HotFileOrganization {
    private HotHost host;

    @Override
    public String moduleId() {
        return "fileorganization";
    }

    @Override
    public void onAttach(Context appContext, HotHost host) {
        this.host = host;
        host.registerService("file_organization", this);
    }

    @Override
    public void onDetach() {
        if (host != null) {
            host.unregisterService("file_organization");
            host = null;
        }
    }

    @Override
    public String chatSubfolder(String chatTitle, long peerId) {
        String name = Normalizer.normalize(chatTitle == null ? "" : chatTitle, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u206F]", "")
                .replaceAll("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]", "_")
                .trim()
                .replaceAll("^\\.+|\\.+$", "");
        return name.isEmpty() ? String.valueOf(peerId) : name;
    }
}
