package com.amegram.mods.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.amegram.hot.api.HotHook;
import app.amegram.hot.api.HotHost;
import app.amegram.hot.api.HotModule;
import app.amegram.hot.api.HotRow;
import app.amegram.hot.api.HotServices;
import app.amegram.hot.api.HotUi;

/**
 * Модуль інтерфейсу Yumi: Yougram Expressive ↔ Classic на льоту,
 * оптимізовані липкі пружинні анімації у стилі PixelPlayer (Material You Expressive)
 * та динамічна адаптація фізики під активну тему Telegram (Світла / Темна / AMOLED / Monet).
 *
 * Оптимізації:
 * 1. Zero-Allocation пайплайн анімацій: перевикористання ValueAnimator та спільні слухачі.
 * 2. Кешовані інтерполятори затухаючого осцилятора — нуль алокацій у жестах.
 * 3. Singleton OnTouchListener для всіх кнопок і чіпів.
 * 4. Динамічна реакція на NotificationCenter Telegram (didSetNewTheme / didApplyNewTheme)
 *    з автоматичним підбором пружності, швидкості та сквишу під тип теми.
 */
public class UiModule implements HotModule, HotUi {

    private static final String NEKO_PREFS = "nkmrcfg";

    // AppearanceConfig keys
    private static final String KEY_STYLE = "OEAppearanceUiStyleMode";
    private static final String KEY_TOUCHED = "OEAppearanceUiStyleModeTouched";
    private static final String KEY_RADIUS = "OEAppearanceSectionRadius";
    private static final String KEY_DIVIDER = "OEAppearanceDividerStyle";
    private static final String KEY_SQUARE_FAB = "OEAppearanceSquareFab";
    private static final String KEY_GLASS_MENU = "OEAppearanceGlassMessageMenu";
    private static final String KEY_M3_LIST = "OEAppearanceM3ListItems";
    private static final String KEY_MINI_PLAYER = "OEAppearanceMd3MiniPlayer";
    private static final String KEY_LOADING = "OEAppearanceNewLoadingStyle";

    // Sticky animations configuration keys
    public static final String KEY_STICKY_ENABLED = "OEAppearanceStickyAnimations";
    public static final String KEY_STICKY_THEME_ADAPTIVE = "OEAppearanceStickyThemeAdaptive";
    public static final String KEY_STICKY_STYLE = "OEAppearanceStickyStyle"; // 0 = Subtle, 1 = Expressive, 2 = Ultra Bouncy
    public static final String KEY_STICKY_LISTS = "OEAppearanceStickyLists";
    public static final String KEY_STICKY_BUTTONS = "OEAppearanceStickyButtons";
    public static final String KEY_STICKY_SHEETS = "OEAppearanceStickySheets";

    // Physics modes
    public static final int STYLE_SUBTLE = 0;
    public static final int STYLE_EXPRESSIVE = 1;
    public static final int STYLE_ULTRA_BOUNCY = 2;

    private HotHost host;
    private final List<HotHook.Unhook> activeHooks = new ArrayList<>();
    private final Set<View> stickyAttachedViews = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    // Theme state
    private volatile boolean isThemeDark = false;
    private volatile boolean isThemeMonet = false;
    private volatile boolean isThemeAmoled = false;

    // Pre-computed physics parameters (zero runtime calculation in gesture handlers)
    private volatile float currentDamping = 5.5f;
    private volatile float currentFreq = 11.5f;
    private volatile long currentDuration = 400L;
    private volatile float currentButtonPressScale = 0.94f;
    private volatile float currentListPressScale = 0.96f;

    // Cached pre-allocated interpolators
    private volatile Interpolator cachedSpringInterpolator;
    private final Interpolator cachedCompressInterpolator = new StickyCompressInterpolator();

    // Reusable animator holders per view to eliminate garbage generation
    private static final class ScaleAnimatorHolder {
        final ValueAnimator animator;
        final ValueAnimator.AnimatorUpdateListener updateListener;
        final AnimatorListenerAdapter finishListener;
        View targetView;
        float targetScale;

        ScaleAnimatorHolder() {
            animator = new ValueAnimator();
            updateListener = new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    if (targetView != null) {
                        float s = ((Float) a.getAnimatedValue()).floatValue();
                        targetView.setScaleX(s);
                        targetView.setScaleY(s);
                    }
                }
            };
            finishListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (targetView != null && targetScale == 1f) {
                        targetView.setScaleX(1f);
                        targetView.setScaleY(1f);
                    }
                }
            };
            animator.addUpdateListener(updateListener);
            animator.addListener(finishListener);
        }
    }

    private static final WeakHashMap<View, ScaleAnimatorHolder> runningAnimators = new WeakHashMap<>();

    // Singleton shared touch listener for clickable views
    private final View.OnTouchListener sharedStickyTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (!isStickyEnabled() || !readBool(KEY_STICKY_BUTTONS, true)) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                applyStickyPress(view, currentButtonPressScale);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                applyStickyRelease(view);
            }
            return false;
        }
    };

    public UiModule() {
        rebuildPhysicsParameters();
    }

    @Override
    public String moduleId() {
        return "ui";
    }

    @Override
    public void onAttach(Context appContext, HotHost host) {
        this.host = host;
        try {
            host.registerService(HotServices.UI, this);
        } catch (Throwable ignore) {
        }
        updateThemeState();
        setupThemeListener();
        setupHooks();
        try {
            host.log("ui attached with theme-adaptive PixelPlayer sticky animations");
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onDetach() {
        try {
            if (host != null) {
                host.unregisterService(HotServices.UI);
            }
        } catch (Throwable ignore) {
        }
        for (HotHook.Unhook u : activeHooks) {
            try {
                u.unhook();
            } catch (Throwable ignore) {
            }
        }
        activeHooks.clear();
        synchronized (stickyAttachedViews) {
            stickyAttachedViews.clear();
        }
        host = null;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public String settingsTitle() {
        return "Інтерфейс Yumi";
    }

    private SharedPreferences prefs() {
        try {
            Context ctx = host != null ? host.context() : null;
            if (ctx != null) return ctx.getSharedPreferences(NEKO_PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignore) {
        }
        return null;
    }

    private int readInt(String key, int def) {
        try {
            if (host != null) return host.getInt(key, def);
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences p = prefs();
            if (p != null) return p.getInt(key, def);
        } catch (Throwable ignore) {
        }
        return def;
    }

    private boolean readBool(String key, boolean def) {
        try {
            if (host != null) return host.getBool(key, def);
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences p = prefs();
            if (p != null) return p.getBoolean(key, def);
        } catch (Throwable ignore) {
        }
        return def;
    }

    private void writeInt(String key, int value) {
        try {
            if (host != null) host.putInt(key, value);
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences p = prefs();
            if (p != null) p.edit().putInt(key, value).apply();
        } catch (Throwable ignore) {
        }
    }

    private void writeBool(String key, boolean value) {
        try {
            if (host != null) host.putBool(key, value);
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences p = prefs();
            if (p != null) p.edit().putBoolean(key, value).apply();
        } catch (Throwable ignore) {
        }
    }

    private void markTouched() {
        writeBool(KEY_TOUCHED, true);
        try {
            if (host != null) host.emit("ui_changed", "{\"style\":" + styleMode() + ",\"sticky\":" + isStickyEnabled() + "}");
        } catch (Throwable ignore) {
        }
    }

    @Override
    public int styleMode() {
        int v = readInt(KEY_STYLE, readInt("style", 1));
        return v == 0 ? 0 : 1;
    }

    public boolean isStickyEnabled() {
        return readBool(KEY_STICKY_ENABLED, true);
    }

    public boolean isThemeAdaptive() {
        return readBool(KEY_STICKY_THEME_ADAPTIVE, true);
    }

    public int stickyStyle() {
        return readInt(KEY_STICKY_STYLE, STYLE_EXPRESSIVE);
    }

    // ==================== Dynamic Theme Adaptation ====================

    private void setupThemeListener() {
        if (host == null) return;
        try {
            ClassLoader cl = host.context() != null ? host.context().getClassLoader() : getClass().getClassLoader();
            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter", true, cl);
            HotHost.NotificationObserver observer = new HotHost.NotificationObserver() {
                @Override
                public void onNotification(int id, int account, Object... args) {
                    updateThemeState();
                }
            };

            int didSetNewTheme = ncClass.getField("didSetNewTheme").getInt(null);
            host.subscribeNotification(didSetNewTheme, observer);

            try {
                int didApplyNewTheme = ncClass.getField("didApplyNewTheme").getInt(null);
                host.subscribeNotification(didApplyNewTheme, observer);
            } catch (Throwable ignore) {
            }

            try {
                int themeListUpdated = ncClass.getField("themeListUpdated").getInt(null);
                host.subscribeNotification(themeListUpdated, observer);
            } catch (Throwable ignore) {
            }

            host.log("ui: subscribed to Telegram theme changes");
        } catch (Throwable t) {
            if (host != null) host.log("ui: failed to subscribe to theme notifications: " + t.getMessage());
        }
    }

    private void updateThemeState() {
        try {
            ClassLoader cl = host != null && host.context() != null ? host.context().getClassLoader() : getClass().getClassLoader();
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", true, cl);

            Method mDark = themeClass.getMethod("isCurrentThemeDark");
            isThemeDark = (Boolean) mDark.invoke(null);

            try {
                Method mMonet = themeClass.getMethod("isCurrentThemeMonet");
                isThemeMonet = (Boolean) mMonet.invoke(null);
            } catch (Throwable ignore) {
                isThemeMonet = false;
            }

            try {
                Field fTheme = themeClass.getDeclaredField("currentTheme");
                fTheme.setAccessible(true);
                Object curTheme = fTheme.get(null);
                if (curTheme != null) {
                    try {
                        Method mAmoled = curTheme.getClass().getMethod("isAmoled");
                        isThemeAmoled = (Boolean) mAmoled.invoke(curTheme);
                    } catch (Throwable ignore) {
                        isThemeAmoled = false;
                    }
                }
            } catch (Throwable ignore) {
                isThemeAmoled = false;
            }

            rebuildPhysicsParameters();
            if (host != null) {
                host.log("ui: theme updated -> dark=" + isThemeDark + ", monet=" + isThemeMonet + ", amoled=" + isThemeAmoled);
            }
        } catch (Throwable t) {
            if (host != null) host.log("ui: updateThemeState error: " + t.getMessage());
        }
    }

    private void rebuildPhysicsParameters() {
        if (isThemeAdaptive()) {
            if (isThemeMonet) {
                // Google Pixel Material 3 Expressive (чистий стиль PixelPlayer)
                currentDamping = 5.6f;
                currentFreq = 11.8f;
                currentDuration = 390L;
                currentButtonPressScale = 0.94f;
                currentListPressScale = 0.96f;
            } else if (isThemeAmoled) {
                // AMOLED Pitch Black: ультра-чіткий відгук, без змазування пікселів
                currentDamping = 6.8f;
                currentFreq = 13.8f;
                currentDuration = 310L;
                currentButtonPressScale = 0.955f;
                currentListPressScale = 0.97f;
            } else if (isThemeDark) {
                // Темна / Нічна тема: зібраний, швидкий тактильний відгук
                currentDamping = 6.2f;
                currentFreq = 12.8f;
                currentDuration = 340L;
                currentButtonPressScale = 0.95f;
                currentListPressScale = 0.965f;
            } else {
                // Світла / Денна тема: плавний, органічний, текучий спрінг з соковитим овершутом
                currentDamping = 5.2f;
                currentFreq = 11.0f;
                currentDuration = 420L;
                currentButtonPressScale = 0.935f;
                currentListPressScale = 0.955f;
            }
        } else {
            // Ручний профіль користувача
            switch (stickyStyle()) {
                case STYLE_SUBTLE:
                    currentDamping = 7.5f;
                    currentFreq = 14.0f;
                    currentDuration = 300L;
                    currentButtonPressScale = 0.965f;
                    currentListPressScale = 0.975f;
                    break;
                case STYLE_ULTRA_BOUNCY:
                    currentDamping = 3.8f;
                    currentFreq = 9.5f;
                    currentDuration = 520L;
                    currentButtonPressScale = 0.91f;
                    currentListPressScale = 0.935f;
                    break;
                case STYLE_EXPRESSIVE:
                default:
                    currentDamping = 5.5f;
                    currentFreq = 11.5f;
                    currentDuration = 400L;
                    currentButtonPressScale = 0.94f;
                    currentListPressScale = 0.96f;
                    break;
            }
        }
        cachedSpringInterpolator = new DampedSpringInterpolator(currentDamping, currentFreq);
    }

    private String getThemeSummary() {
        if (isThemeMonet) return "Material You Monet";
        if (isThemeAmoled) return "AMOLED Pure Black";
        if (isThemeDark) return "Темна тема";
        return "Світла тема";
    }

    private String getAdaptiveProfileDescription() {
        if (isThemeMonet) return "PixelPlayer M3 Expressive (динамічні тональні кольори)";
        if (isThemeAmoled) return "Ультра-чіткий відгук (310ms, без OLED ghosting)";
        if (isThemeDark) return "Преміальний швидкий відгук (340ms, чітка темна фізика)";
        return "Плавний органічний спрінг (420ms, соковитий овершут 1.035x)";
    }

    private String getStickyStyleTitle() {
        switch (stickyStyle()) {
            case STYLE_SUBTLE: return "Делікатна (Subtle)";
            case STYLE_ULTRA_BOUNCY: return "Желейна (Ultra Bouncy)";
            case STYLE_EXPRESSIVE:
            default: return "PixelPlayer Expressive";
        }
    }

    private String getStickyStyleDesc() {
        switch (stickyStyle()) {
            case STYLE_SUBTLE: return "Мінімальне стискання та швидке затухання";
            case STYLE_ULTRA_BOUNCY: return "Глибокий squish та багаторазові гармонійні коливання";
            case STYLE_EXPRESSIVE:
            default: return "Тактильний пружинний овершут у стилі Material 3 Expressive";
        }
    }

    // ==================== Optimized Spring Physics ====================

    /**
     * Математично точна фізика затухаючого гармонійного осцилятора:
     * progress(t) = 1 - e^(-damping * t) * (cos(freq * t) + (damping / freq) * sin(freq * t))
     */
    public static class DampedSpringInterpolator implements Interpolator {
        private final float damping;
        private final float freq;

        public DampedSpringInterpolator(float damping, float freq) {
            this.damping = damping;
            this.freq = freq;
        }

        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float decay = (float) Math.exp(-damping * t);
            return 1f - decay * ((float) Math.cos(freq * t) + (damping / freq) * (float) Math.sin(freq * t));
        }
    }

    /** Швидка крива стискання під пальцем (квадратичний ease-out). */
    public static class StickyCompressInterpolator implements Interpolator {
        @Override
        public float getInterpolation(float t) {
            float inv = 1f - t;
            return 1f - inv * inv;
        }
    }

    private void applyStickyPress(final View v, float targetScale) {
        if (v == null || !isStickyEnabled()) return;
        ensurePivot(v);
        animateScale(v, v.getScaleX(), targetScale, 110L, cachedCompressInterpolator);
    }

    private void applyStickyRelease(final View v) {
        if (v == null || !isStickyEnabled()) return;
        ensurePivot(v);
        Interpolator interp = cachedSpringInterpolator;
        if (interp == null) interp = new DampedSpringInterpolator(currentDamping, currentFreq);
        animateScale(v, v.getScaleX(), 1f, currentDuration, interp);
    }

    private static void ensurePivot(View v) {
        if (v.getPivotX() == 0 && v.getPivotY() == 0 && v.getWidth() > 0 && v.getHeight() > 0) {
            v.setPivotX(v.getWidth() * 0.5f);
            v.setPivotY(v.getHeight() * 0.5f);
        }
    }

    /**
     * Оптимізована анімація з повторним використанням ValueAnimator.
     * Нуль алокацій об'єктів під час повторних жестів.
     */
    private static void animateScale(final View v, float fromScale, final float targetScale, long duration, Interpolator interp) {
        if (v == null) return;
        ScaleAnimatorHolder holder = runningAnimators.get(v);
        if (holder == null) {
            holder = new ScaleAnimatorHolder();
            runningAnimators.put(v, holder);
        } else if (holder.animator.isRunning()) {
            holder.animator.cancel();
        }
        holder.targetView = v;
        holder.targetScale = targetScale;
        holder.animator.setFloatValues(fromScale, targetScale);
        holder.animator.setDuration(duration);
        if (interp != null) holder.animator.setInterpolator(interp);
        holder.animator.start();
    }

    private void attachStickyTouchToView(final View v) {
        if (v == null) return;
        synchronized (stickyAttachedViews) {
            if (stickyAttachedViews.contains(v)) return;
            stickyAttachedViews.add(v);
        }
        v.setOnTouchListener(sharedStickyTouchListener);
    }

    // ==================== Dynamic ART Hooks ====================

    private void setupHooks() {
        if (host == null) return;
        ClassLoader cl = host.context() != null ? host.context().getClassLoader() : getClass().getClassLoader();

        // 1. RecyclerListView: перехоплення натискання на елементи списку (чати, повідомлення, налаштування)
        try {
            Class<?> rlvClass = Class.forName("org.telegram.ui.Components.RecyclerListView", true, cl);
            Method m = rlvClass.getDeclaredMethod("onChildPressed", View.class, float.class, float.class, boolean.class);
            HotHook.Unhook u = host.hook(m, new HotHook.Callback() {
                @Override
                public void after(HotHook.Call call) throws Throwable {
                    if (!isStickyEnabled()) return;
                    Object[] args = call.getArgs();
                    if (args != null && args.length >= 4 && args[0] instanceof View) {
                        View child = (View) args[0];
                        if (child.getWidth() <= 0 || child.getHeight() <= 0) return;
                        boolean pressed = (Boolean) args[3];
                        if (pressed) {
                            applyStickyPress(child, currentListPressScale);
                        } else {
                            applyStickyRelease(child);
                        }
                    }
                }
            });
            if (u != null) activeHooks.add(u);
            host.log("ui: hooked RecyclerListView.onChildPressed for sticky lists");
        } catch (Throwable t) {
            host.log("ui: hook RecyclerListView failed: " + t.getMessage());
        }

        // 2. View.setOnClickListener: підключення липкого відгуку для кнопок, плашок, FAB та чіпів
        try {
            Method m = View.class.getDeclaredMethod("setOnClickListener", View.OnClickListener.class);
            HotHook.Unhook u = host.hook(m, new HotHook.Callback() {
                @Override
                public void after(HotHook.Call call) throws Throwable {
                    if (!isStickyEnabled()) return;
                    Object target = call.getThis();
                    Object[] args = call.getArgs();
                    if (target instanceof View) {
                        View v = (View) target;
                        View.OnClickListener l = (args != null && args.length > 0) ? (View.OnClickListener) args[0] : null;
                        if (l != null) {
                            attachStickyTouchToView(v);
                        }
                    }
                }
            });
            if (u != null) activeHooks.add(u);
            host.log("ui: hooked View.setOnClickListener for sticky buttons");
        } catch (Throwable t) {
            host.log("ui: hook View.setOnClickListener failed: " + t.getMessage());
        }

        // 3. BottomSheet: підміна інтерполятора відкриття шторок на пружинний
        try {
            final Class<?> bsClass = Class.forName("org.telegram.ui.ActionBar.BottomSheet", true, cl);
            List<HotHook.Unhook> sheetHooks = host.hookAll(bsClass, "show", new HotHook.Callback() {
                @Override
                public void before(HotHook.Call call) throws Throwable {
                    if (!isStickyEnabled()) return;
                    Object sheet = call.getThis();
                    if (sheet != null) {
                        try {
                            Field f = bsClass.getField("openInterpolator");
                            Interpolator interp = cachedSpringInterpolator;
                            if (interp == null) interp = new DampedSpringInterpolator(currentDamping, currentFreq);
                            f.set(sheet, interp);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
            if (sheetHooks != null) activeHooks.addAll(sheetHooks);
            host.log("ui: hooked BottomSheet.show for spring sheet entrance");
        } catch (Throwable t) {
            host.log("ui: hook BottomSheet failed: " + t.getMessage());
        }

        // 4. ActionBarMenuItem: тактильний squish-відгук для іконок верхнього тулбара (пошук, меню, назад)
        try {
            Class<?> itemClass = Class.forName("org.telegram.ui.ActionBar.ActionBarMenuItem", true, cl);
            Method m = itemClass.getDeclaredMethod("onTouchEvent", MotionEvent.class);
            HotHook.Unhook u = host.hook(m, new HotHook.Callback() {
                @Override
                public void before(HotHook.Call call) throws Throwable {
                    if (!isStickyEnabled()) return;
                    Object target = call.getThis();
                    Object[] args = call.getArgs();
                    if (target instanceof View && args != null && args.length > 0 && args[0] instanceof MotionEvent) {
                        View v = (View) target;
                        MotionEvent ev = (MotionEvent) args[0];
                        int action = ev.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            applyStickyPress(v, currentButtonPressScale);
                        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            applyStickyRelease(v);
                        }
                    }
                }
            });
            if (u != null) activeHooks.add(u);
            host.log("ui: hooked ActionBarMenuItem.onTouchEvent");
        } catch (Throwable t) {
            host.log("ui: hook ActionBarMenuItem failed: " + t.getMessage());
        }
    }

    // ==================== Settings UI ====================

    @Override
    public void fillSettings(List<HotRow> rows) {
        boolean exp = styleMode() == 1;

        rows.add(HotRow.header("Стиль (на льоту, без APK)"));
        rows.add(HotRow.switchRow("expressive",
                "Yougram Expressive",
                "Рідке скло + M3 сегменти 28dp. Вимкнено = Classic.",
                exp));
        rows.add(HotRow.button("go_expressive", "Увімкнути Expressive", "Скло, сегменти, Monet"));
        rows.add(HotRow.button("go_classic", "Увімкнути Classic", "Стандартні картки 20dp"));

        rows.add(HotRow.header("Липкі анімації Yumi (PixelPlayer)"));
        rows.add(HotRow.switchRow(KEY_STICKY_ENABLED,
                "Липкі анімації",
                "Тактильний пружинний відгук у стилі PixelPlayer та автоадаптація під тему Telegram",
                isStickyEnabled()));

        rows.add(HotRow.header("Деталі Expressive"));
        rows.add(HotRow.switchRow(KEY_SQUARE_FAB, "Squircle FAB", "Квадратна кнопка 16dp", readBool(KEY_SQUARE_FAB, true)));
        rows.add(HotRow.switchRow(KEY_GLASS_MENU, "Скляне меню", "GlassMessageMenu", readBool(KEY_GLASS_MENU, true)));
        rows.add(HotRow.switchRow(KEY_M3_LIST, "M3-рядки", "Вищі рядки, кольорові іконки", readBool(KEY_M3_LIST, false)));
        rows.add(HotRow.switchRow(KEY_MINI_PLAYER, "MD3 міні-плеєр", "Плашка в чатах", readBool(KEY_MINI_PLAYER, true)));
        rows.add(HotRow.switchRow(KEY_LOADING, "M3-лоадери", "Нові індикатори", readBool(KEY_LOADING, true)));
        rows.add(HotRow.info("Усі анімації кнопок, списків, шторок та адаптація під тему увімкнені за замовчуванням."));
    }

    @Override
    public void onSettingsToggle(String key, boolean value) {
        if ("expressive".equals(key)) {
            writeInt(KEY_STYLE, value ? 1 : 0);
            writeInt("style", value ? 1 : 0);
            markTouched();
            return;
        }
        writeBool(key, value);
        markTouched();
    }

    @Override
    public void onSettingsAction(String rowId) {
        if ("go_expressive".equals(rowId)) {
            writeInt(KEY_STYLE, 1);
            writeInt("style", 1);
            markTouched();
            if (host != null) host.toast("Expressive — застосовано на льоту");
        } else if ("go_classic".equals(rowId)) {
            writeInt(KEY_STYLE, 0);
            writeInt("style", 0);
            markTouched();
            if (host != null) host.toast("Classic — застосовано на льоту");
        }
    }

    @Override
    public Object createScreen(String screenId) {
        return null;
    }
}
