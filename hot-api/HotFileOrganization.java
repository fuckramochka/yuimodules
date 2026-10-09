package app.amegram.hot.api;

/** Optional HMOD policy for generating a safe chat subfolder name. */
public interface HotFileOrganization {
    /** Returns a safe relative folder name, or null to use the client fallback. */
    String chatSubfolder(String chatTitle, long peerId);
}
