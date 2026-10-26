package com.amegram.mods.badges;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import app.amegram.hot.api.HotBadges;
import app.amegram.hot.api.HotHost;
import app.amegram.hot.api.HotModule;
import app.amegram.hot.api.HotServices;
import app.miogram.bridge.MiogramLocale;
import app.miogram.bridge.badge.MiogramArrowDrawable;
import app.miogram.bridge.badge.MiogramBadgeBottomSheet;
import app.miogram.bridge.badge.MiogramBadgeType;

/**
 * Системний гарячий модуль бейджів та статусів спільноти (GitOps):
 * - Автономна синхронізація реєстру з GitHub (badges/badges.json).
 * - Підтримка HTTP ETag (304 Not Modified) для нульового оверхеду по трафіку.
 * - Локальне сховище та кешування кастомних векторних SVG-файлів.
 * - Повний рендеринг 10 канонічних стилів Needy Streamer Overload / Ame & KAngel.
 */
public class BadgesModule implements HotModule, HotBadges {

    private static final String GITHUB_BADGES_URL =
            "https://raw.githubusercontent.com/fuckramochka/yuimodules/main/badges/badges.json";
    private static final String GITHUB_SVG_BASE_URL =
            "https://raw.githubusercontent.com/fuckramochka/yuimodules/main/badges/svg/";

    private static final String PREFS_NAME = "hotmod_badges_prefs";
    private static final String KEY_ETAG = "badges_etag";
    private static final String KEY_CACHE_JSON = "badges_cached_json";

    public static final long FOUNDER_USER_ID = 8011880648L;

    public static class BadgeItem {
        public final long userId;
        public final MiogramBadgeType type;
        public final String customSvg;
        public final String title;
        public final String lore;
        public final String date;
        public final boolean verified;

        public BadgeItem(long userId, MiogramBadgeType type, String customSvg, String title, String lore, String date, boolean verified) {
            this.userId = userId;
            this.type = type != null ? type : MiogramBadgeType.ORIGINAL;
            this.customSvg = customSvg;
            this.title = title;
            this.lore = lore;
            this.date = date;
            this.verified = verified;
        }
    }

    private HotHost host;
    private final LongSparseArray<BadgeItem> badgeCache = new LongSparseArray<>();
    private final LongSparseArray<Drawable> svgDrawableCache = new LongSparseArray<>();
    private long lastSyncTime = 0;

    public BadgesModule() {
    }

    @Override
    public String moduleId() {
        return "badges";
    }

    @Override
    public void onAttach(Context appContext, HotHost host) {
        this.host = host;
        try {
            host.registerService(HotServices.BADGES, this);
        } catch (Throwable ignore) {
        }

        // 1. Відновити бейджі з локального кешу
        loadCachedBadges(appContext);

        // 2. Асинхронно перевірити оновлення на GitHub через ETag
        refreshBadges(null);

        try {
            host.log("badges system module attached with GitOps backend");
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onDetach() {
        try {
            if (host != null) {
                host.unregisterService(HotServices.BADGES);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public boolean hasBadge(long userId) {
        if (userId <= 0) return false;
        synchronized (badgeCache) {
            if (badgeCache.indexOfKey(userId) >= 0) {
                return true;
            }
        }
        return userId == FOUNDER_USER_ID;
    }

    @Override
    public Drawable getBadgeDrawable(long userId, int sizeDp) {
        BadgeItem item;
        synchronized (badgeCache) {
            item = badgeCache.get(userId);
        }

        MiogramBadgeType type = item != null ? item.type : (userId == FOUNDER_USER_ID ? MiogramBadgeType.ORIGINAL : MiogramBadgeType.ORIGINAL);

        if (item != null && !TextUtils.isEmpty(item.customSvg)) {
            Drawable cachedSvg;
            synchronized (svgDrawableCache) {
                cachedSvg = svgDrawableCache.get(userId);
            }
            if (cachedSvg != null) {
                return cachedSvg;
            }
            // Спробувати завантажити та зрендерити збережений SVG
            Drawable svg = tryLoadSvgDrawable(userId, item.customSvg, sizeDp);
            if (svg != null) {
                synchronized (svgDrawableCache) {
                    svgDrawableCache.put(userId, svg);
                }
                return svg;
            }
        }

        return new MiogramArrowDrawable(sizeDp, type);
    }

    @Override
    public String getBadgeTitle(long userId) {
        BadgeItem item;
        synchronized (badgeCache) {
            item = badgeCache.get(userId);
        }
        if (item != null && !TextUtils.isEmpty(item.title)) {
            return item.title;
        }
        if (userId == FOUNDER_USER_ID) {
            return MiogramLocale.get("Засновник & Архітектор Miogram ໒꒱", "Создатель и Архитектор Miogram ໒꒱", "Miogram Founder & Architect ໒꒱");
        }
        MiogramBadgeType type = item != null ? item.type : MiogramBadgeType.ORIGINAL;
        return MiogramLocale.get("Користувач Miogram", "Пользователь Miogram", "Miogram Community") + " (" + type.getCode() + ")";
    }

    @Override
    public void showBadgeSheet(Context context, long userId) {
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        MiogramBadgeBottomSheet.show(ctx, userId);
    }

    @Override
    public void openBadgesSettings(BaseFragment fragment) {
        if (fragment != null) {
            fragment.presentFragment(new app.amegram.settings.AmegramBadgesActivity());
        }
    }

    @Override
    public void refreshBadges(Runnable onComplete) {
        long now = System.currentTimeMillis();
        if (now - lastSyncTime < 15_000L) {
            if (onComplete != null) onComplete.run();
            return;
        }
        lastSyncTime = now;

        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                Context ctx = ApplicationLoader.applicationContext;
                SharedPreferences prefs = ctx != null ? ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
                String etag = prefs != null ? prefs.getString(KEY_ETAG, null) : null;

                URL url = new URL(GITHUB_BADGES_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                if (!TextUtils.isEmpty(etag)) {
                    conn.setRequestProperty("If-None-Match", etag);
                }

                int code = conn.getResponseCode();
                if (code == 304) {
                    // Файл на GitHub не змінювався, дані свіжі
                    if (onComplete != null) {
                        AndroidUtilities.runOnUIThread(onComplete);
                    }
                    return;
                }

                if (code >= 200 && code < 300) {
                    String newEtag = conn.getHeaderField("ETag");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String jsonStr = sb.toString();
                    parseAndApplyBadgesJson(jsonStr);

                    if (prefs != null) {
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putString(KEY_CACHE_JSON, jsonStr);
                        if (!TextUtils.isEmpty(newEtag)) {
                            ed.putString(KEY_ETAG, newEtag);
                        }
                        ed.apply();
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload);
                        if (onComplete != null) onComplete.run();
                    });
                    return;
                }
            } catch (Throwable e) {
                FileLog.e("BadgesModule: sync error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }

            if (onComplete != null) {
                AndroidUtilities.runOnUIThread(onComplete);
            }
        });
    }

    private void loadCachedBadges(Context context) {
        try {
            Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_CACHE_JSON, null);
            if (!TextUtils.isEmpty(json)) {
                parseAndApplyBadgesJson(json);
            } else {
                // Дефолтний запис засновника
                synchronized (badgeCache) {
                    badgeCache.put(FOUNDER_USER_ID, new BadgeItem(
                            FOUNDER_USER_ID,
                            MiogramBadgeType.ORIGINAL,
                            null,
                            "Засновник & Архітектор Miogram ໒꒱",
                            "Особиста відзнака засновника та головного архітектора екосистеми Miogram (@fuckramochka).",
                            "01.09.2026",
                            true
                    ));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void parseAndApplyBadgesJson(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONArray arr = root.optJSONArray("badges");
            if (arr == null) return;

            synchronized (badgeCache) {
                badgeCache.clear();
                // Завжди гарантувати запис засновника
                badgeCache.put(FOUNDER_USER_ID, new BadgeItem(
                        FOUNDER_USER_ID,
                        MiogramBadgeType.ORIGINAL,
                        null,
                        "Засновник & Архітектор Miogram ໒꒱",
                        "Особиста відзнака засновника та головного архітектора екосистеми Miogram (@fuckramochka).",
                        "01.09.2026",
                        true
                ));

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long uid = obj.optLong("user_id");
                    if (uid <= 0) continue;

                    String typeStr = obj.optString("type", "original");
                    MiogramBadgeType type = MiogramBadgeType.fromId(typeStr);
                    String svg = obj.optString("custom_svg", null);
                    String title = obj.optString("title", null);
                    String lore = obj.optString("lore", null);
                    String date = obj.optString("date", "2026");
                    boolean verified = obj.optBoolean("verified", uid == FOUNDER_USER_ID);

                    badgeCache.put(uid, new BadgeItem(uid, type, svg, title, lore, date, verified));
                }
            }
        } catch (Throwable e) {
            FileLog.e("BadgesModule: parse badges json error", e);
        }
    }

    private Drawable tryLoadSvgDrawable(long userId, String svgFileName, int sizeDp) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || TextUtils.isEmpty(svgFileName)) return null;

        File dir = new File(ctx.getFilesDir(), "badges_svg");
        File localSvg = new File(dir, svgFileName);

        if (!localSvg.exists() || localSvg.length() == 0) {
            downloadSvgAsync(svgFileName, localSvg);
            return null;
        }

        try {
            int px = AndroidUtilities.dp(sizeDp);
            Bitmap bitmap = SvgHelper.getBitmap(localSvg, px, px, false);
            if (bitmap != null) {
                return new BitmapDrawable(ctx.getResources(), bitmap);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private void downloadSvgAsync(String fileName, File targetFile) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                if (!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                }
                URL url = new URL(GITHUB_SVG_BASE_URL + fileName);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() == 200) {
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(targetFile);
                    byte[] buf = new byte[2048];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.close();
                    in.close();

                    AndroidUtilities.runOnUIThread(() -> {
                        synchronized (svgDrawableCache) {
                            svgDrawableCache.clear();
                        }
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload);
                    });
                }
            } catch (Throwable ignore) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
