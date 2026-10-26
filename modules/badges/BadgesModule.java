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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
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
import java.util.List;

import app.amegram.hot.api.HotBadges;
import app.amegram.hot.api.HotHost;
import app.amegram.hot.api.HotModule;
import app.amegram.hot.api.HotRow;
import app.amegram.hot.api.HotServices;
import app.miogram.bridge.MiogramLocale;
import app.miogram.bridge.badge.MiogramArrowDrawable;
import app.miogram.bridge.badge.MiogramBadgeBottomSheet;
import app.miogram.bridge.badge.MiogramBadgeType;
import app.miogram.bridge.badge.MiogramSupabaseBridge;

/**
 * Системний гарячий модуль бейджів та статусів спільноти (Гібридне подвійне джерело):
 * 1. Первинне джерело: Ноутбук-сервер (REST API + миттєві локальні оновлення).
 * 2. Резервне джерело: GitHub raw дзеркало (badges.json), якщо ноутбук офлайн.
 * 3. Локальний диск: offline-кеш з підтримкою ETag (304 Not Modified).
 * 4. Підтримка завантаження кастомних векторних SVG-файлів.
 */
public class BadgesModule implements HotModule, HotBadges {

    private static final String GITHUB_BADGES_URL =
            "https://raw.githubusercontent.com/fuckramochka/yuimodules/main/badges/badges.json";
    private static final String GITHUB_SVG_BASE_URL =
            "https://raw.githubusercontent.com/fuckramochka/yuimodules/main/badges/svg/";

    private static final String PREFS_NAME = "hotmod_badges_prefs";
    private static final String KEY_ETAG = "badges_etag";
    private static final String KEY_CACHE_JSON = "badges_cached_json";

    private static final String KEY_USE_LAPTOP = "badges_use_laptop";
    private static final String KEY_LAPTOP_URL = "badges_laptop_url";

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
    private volatile String currentSvgBaseUrl = GITHUB_SVG_BASE_URL;

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

        // 2. Асинхронно перевірити оновлення (ноутбук або GitHub фолбек)
        refreshBadges(null);

        try {
            host.log("badges system module attached with dual-source backend");
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
    public boolean hasSettings() {
        return true;
    }

    @Override
    public String settingsTitle() {
        return "Бейджики";
    }

    @Override
    public void fillSettings(List<HotRow> rows) {
        rows.add(HotRow.header("Джерело бейджів"));

        boolean useLaptop = host != null ? host.getBool(KEY_USE_LAPTOP, true) : true;
        String laptopUrl = host != null ? host.getString(KEY_LAPTOP_URL, "http://192.168.1.100:8765") : "http://192.168.1.100:8765";

        rows.add(HotRow.switchRow(KEY_USE_LAPTOP, "Ноутбук як первинний сервер",
                "Запитувати бейджі з ноутбука; якщо він вимкнений — авто-фолбек на GitHub", useLaptop));

        if (useLaptop) {
            rows.add(HotRow.inputRow(KEY_LAPTOP_URL, "Адреса ноутбука (IP або Cloudflare)", laptopUrl));
        }

        rows.add(HotRow.info("Гібридний режим: при роботі ноутбук автоматично синхронізує каталог з GitHub. При відсутності зв'язку клієнт одразу перемикається на GitHub-дзеркало."));
        rows.add(HotRow.button("badges_sync_now", "Оновити бейджі зараз", "Перевірити ноутбук або завантажити з GitHub"));
    }

    @Override
    public void onSettingsToggle(String key, boolean value) {
        if (KEY_USE_LAPTOP.equals(key) && host != null) {
            host.putBool(KEY_USE_LAPTOP, value);
            refreshBadges(null);
        }
    }

    @Override
    public void onSettingsInput(String key, String value) {
        if (KEY_LAPTOP_URL.equals(key) && host != null) {
            host.putString(KEY_LAPTOP_URL, value);
            refreshBadges(null);
        }
    }

    @Override
    public void onSettingsAction(String rowId) {
        if ("badges_sync_now".equals(rowId)) {
            refreshBadges(null);
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

        MiogramBadgeType localType = null;
        try {
            localType = MiogramSupabaseBridge.getExplicitSelectedBadge(ApplicationLoader.applicationContext, userId);
        } catch (Throwable ignored) {}

        if (localType != null) {
            return new MiogramArrowDrawable(sizeDp, localType);
        }

        if (item != null && !TextUtils.isEmpty(item.customSvg)) {
            Drawable cachedSvg;
            synchronized (svgDrawableCache) {
                cachedSvg = svgDrawableCache.get(userId);
            }
            if (cachedSvg != null) {
                return cachedSvg;
            }
            Drawable svg = tryLoadSvgDrawable(userId, item.customSvg, sizeDp);
            if (svg != null) {
                synchronized (svgDrawableCache) {
                    svgDrawableCache.put(userId, svg);
                }
                return svg;
            }
        }

        MiogramBadgeType type = item != null ? item.type : MiogramBadgeType.ORIGINAL;
        return new MiogramArrowDrawable(sizeDp, type);
    }

    @Override
    public String getBadgeTitle(long userId) {
        MiogramBadgeType localType = null;
        try {
            localType = MiogramSupabaseBridge.getExplicitSelectedBadge(ApplicationLoader.applicationContext, userId);
        } catch (Throwable ignored) {}

        BadgeItem item;
        synchronized (badgeCache) {
            item = badgeCache.get(userId);
        }
        if (item != null && !TextUtils.isEmpty(item.title)) {
            if (localType != null) {
                return item.title + " (" + localType.getTitle() + ")";
            }
            return item.title;
        }
        if (userId == FOUNDER_USER_ID) {
            String badgeName = localType != null ? (" (" + localType.getTitle() + ")") : "";
            return MiogramLocale.get("Засновник & Архітектор Miyumi ໒꒱", "Создатель и Архитектор Miyumi ໒꒱", "Miyumi Founder & Architect ໒꒱") + badgeName;
        }
        MiogramBadgeType type = localType != null ? localType : (item != null ? item.type : MiogramBadgeType.ORIGINAL);
        return MiogramLocale.get("Спільнота Miyumi ໒꒱", "Сообщество Miyumi ໒꒱", "Miyumi Community ໒꒱") + " (" + type.getCode() + ")";
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
        if (now - lastSyncTime < 1_500L) {
            if (onComplete != null) onComplete.run();
            return;
        }
        lastSyncTime = now;

        Utilities.globalQueue.postRunnable(() -> {
            Context ctx = ApplicationLoader.applicationContext;
            SharedPreferences prefs = ctx != null ? ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;

            boolean useLaptop = true;
            if (host != null) {
                useLaptop = host.getBool(KEY_USE_LAPTOP, true);
            } else if (prefs != null) {
                useLaptop = prefs.getBoolean(KEY_USE_LAPTOP, true);
            }

            String rawLaptopUrl = null;
            if (host != null) {
                rawLaptopUrl = host.getString(KEY_LAPTOP_URL, null);
            }
            if (TextUtils.isEmpty(rawLaptopUrl) && prefs != null) {
                rawLaptopUrl = prefs.getString(KEY_LAPTOP_URL, null);
            }
            if (TextUtils.isEmpty(rawLaptopUrl) && prefs != null) {
                rawLaptopUrl = prefs.getString("badges_discovered_server_url", null);
            }
            if (TextUtils.isEmpty(rawLaptopUrl)) {
                rawLaptopUrl = "http://192.168.1.100:8765";
            }

            boolean loaded = false;

            // 1. Спроба зв'язатися з сервером на ноутбуці (якщо увімкнено)
            if (useLaptop && !TextUtils.isEmpty(rawLaptopUrl)) {
                String baseUrl = rawLaptopUrl.trim();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                String endpoint = baseUrl + "badges.json";

                HttpURLConnection lConn = null;
                try {
                    URL url = new URL(endpoint);
                    lConn = (HttpURLConnection) url.openConnection();
                    lConn.setRequestMethod("GET");
                    lConn.setConnectTimeout(2500); // Швидкий таймаут 2.5с
                    lConn.setReadTimeout(2500);
                    lConn.setRequestProperty("Accept", "application/json");

                    String etag = prefs != null ? prefs.getString(KEY_ETAG, null) : null;
                    if (!TextUtils.isEmpty(etag)) {
                        lConn.setRequestProperty("If-None-Match", etag);
                    }

                    int code = lConn.getResponseCode();
                    if (code == 304) {
                        currentSvgBaseUrl = baseUrl + "svg/";
                        loaded = true;
                    } else if (code >= 200 && code < 300) {
                        String newEtag = lConn.getHeaderField("ETag");
                        BufferedReader reader = new BufferedReader(new InputStreamReader(lConn.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        parseAndApplyBadgesJson(sb.toString());
                        currentSvgBaseUrl = baseUrl + "svg/";
                        saveCache(prefs, sb.toString(), newEtag);
                        loaded = true;
                    }
                } catch (Throwable e) {
                    FileLog.e("BadgesModule: laptop server unreachable: " + e.getMessage() + ", falling back to GitHub mirror");
                } finally {
                    if (lConn != null) lConn.disconnect();
                }
            }

            // 2. Якщо ноутбук недоступний або вимкнений — автоматичний фолбек на GitHub
            if (!loaded) {
                currentSvgBaseUrl = GITHUB_SVG_BASE_URL;
                fetchFromGitHub(prefs);
            }

            AndroidUtilities.runOnUIThread(() -> {
                try {
                    int account = UserConfig.selectedAccount;
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                } catch (Throwable ignored) {}
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload);
                if (onComplete != null) onComplete.run();
            });
        });
    }

    private void fetchFromGitHub(SharedPreferences prefs) {
        HttpURLConnection conn = null;
        try {
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

                parseAndApplyBadgesJson(sb.toString());
                saveCache(prefs, sb.toString(), newEtag);
            }
        } catch (Throwable e) {
            FileLog.e("BadgesModule: GitHub fallback sync error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveCache(SharedPreferences prefs, String jsonStr, String etag) {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_CACHE_JSON, jsonStr);
        if (!TextUtils.isEmpty(etag)) {
            ed.putString(KEY_ETAG, etag);
        }
        ed.apply();
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
                synchronized (badgeCache) {
                    badgeCache.put(FOUNDER_USER_ID, new BadgeItem(
                            FOUNDER_USER_ID,
                            MiogramBadgeType.ORIGINAL,
                            null,
                            "Засновник & Архітектор Miyumi ໒꒱",
                            "Особиста відзнака засновника та головного архітектора спільноти Miyumi (@fuckramochka).",
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
            String discoveredUrl = root.optString("server_url", null);
            if (!TextUtils.isEmpty(discoveredUrl)) {
                try {
                    Context ctx = ApplicationLoader.applicationContext;
                    if (ctx != null) {
                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString("badges_discovered_server_url", discoveredUrl).apply();
                    }
                } catch (Throwable ignored) {}
            }
            JSONArray arr = root.optJSONArray("badges");
            if (arr == null) return;

            synchronized (badgeCache) {
                badgeCache.clear();
                badgeCache.put(FOUNDER_USER_ID, new BadgeItem(
                        FOUNDER_USER_ID,
                        MiogramBadgeType.DEVIL,
                        null,
                        "Засновник & Архітектор Miyumi ໒꒱",
                        "Особиста відзнака засновника та головного архітектора спільноти Miyumi (@fuckramochka).",
                        "01.09.2026",
                        true
                ));

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long uid = obj.optLong("user_id");
                    if (uid <= 0) continue;

                    String typeStr = obj.optString("type", "devil");
                    MiogramBadgeType type = MiogramBadgeType.fromId(typeStr);
                    String svg = obj.optString("custom_svg", null);
                    String title = obj.optString("title", null);
                    String lore = obj.optString("lore", null);
                    String date = obj.optString("date", "2026");
                    boolean verified = obj.optBoolean("verified", uid == FOUNDER_USER_ID);

                    badgeCache.put(uid, new BadgeItem(uid, type, svg, title, lore, date, verified));
                    try {
                        MiogramSupabaseBridge.setCloudBadgeRecord(uid, type, title, lore, date, verified);
                    } catch (Throwable ignored) {}
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
                String baseUrl = currentSvgBaseUrl != null ? currentSvgBaseUrl : GITHUB_SVG_BASE_URL;
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                URL url = new URL(baseUrl + fileName);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
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
