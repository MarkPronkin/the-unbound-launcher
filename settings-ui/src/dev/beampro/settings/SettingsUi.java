package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Phone settings only. The original views remain the source of state and actions. */
public final class SettingsUi {
    public static final String APP_NAME = "The Unbound Launcher";
    private static final String TAG = "PortSettingsUi";
    private static final int PAGE_ID = 0x00bea001;
    private static final int SCROLL_ID = 0x00bea002;
    private static final int GLASSES_IMAGE_ID = 0x00bea003;
    private static final int GLASSES_TYPE_TAG = 0x7fbea003;
    private static final String PREFERENCES = "beampro_settings_ui";

    private SettingsUi() {}

    public static void install(Activity activity) {
        DebugData.initialize(activity.getApplication());
        if (activity.findViewById(PAGE_ID) instanceof Page) return;
        try {
            State state = new State(activity);
            state.build(); // Complete binding before replacing the working screen.
            activity.setContentView(state.page);
            state.page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) {
                    state.observe(true);
                }
                public void onViewDetachedFromWindow(View v) {
                    state.observe(false);
                }
            });
            // setContentView can attach synchronously when the window already exists.
            if (state.page.isAttachedToWindow()) {
                state.observe(true);
            }
            state.sync();
            AdbEntry.install(activity);
        } catch (Exception error) {
            Log.e(TAG, "Keeping original settings after an unexpected binding", error);
        }
    }

    public static void refresh(Activity activity) {
        View view = activity.findViewById(PAGE_ID);
        if (!(view instanceof Page)) return;
        activity.runOnUiThread(() -> {
            State state = ((Page) view).state;
            state.sync();
            if (!state.permissions) state.detection.refresh();
        });
    }

    /** The callback's model ID is authoritative even while the SDK getter is stale. */
    public static void glassesConnected(Activity activity, int type) {
        DebugData.get(activity).event("Glasses connected: type=" + type);
        activity.runOnUiThread(() -> {
            activity.getWindow().getDecorView().setTag(GLASSES_TYPE_TAG, Integer.valueOf(type));
            refresh(activity);
        });
    }

    public static void glassesDisconnected(Activity activity) {
        DebugData.get(activity).event("Glasses disconnected");
        activity.runOnUiThread(() -> {
            activity.getWindow().getDecorView().setTag(GLASSES_TYPE_TAG, Integer.valueOf(0));
            refresh(activity);
        });
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> cls = owner.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception error) { throw new IllegalStateException(name, error); }
        }
        return null;
    }

    private static View view(Object owner, String name) { return (View) field(owner, name); }
    private static String text(Object owner, String name) {
        Object value = field(owner, name);
        return value instanceof TextView ? ((TextView) value).getText().toString() : "";
    }
    private static boolean visible(View view) { return view != null && view.getVisibility() == View.VISIBLE; }
    private static boolean checked(View view) {
        if (view == null) return false;
        try { return (Boolean) view.getClass().getMethod("isChecked").invoke(view); }
        catch (Exception error) { throw new IllegalStateException("Switch binding", error); }
    }

    private static final class Page extends FrameLayout {
        final State state;
        Page(State state) { super(state.activity); this.state = state; setId(PAGE_ID); }
        @Override protected void onConfigurationChanged(Configuration configuration) {
            super.onConfigurationChanged(configuration);
            state.onConfigurationChanged(configuration);
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            boolean wide = !state.permissions && AdaptiveLayout.expanded(getContext(), width);
            boolean horizontal = wide && AdaptiveLayout.landscape(getContext(), width, height);
            boolean shortWindow = horizontal && AdaptiveLayout.shortLandscape(getContext(), width, height);
            if (width > 0 && (state.expanded != wide || state.landscape != horizontal || state.shortLandscape != shortWindow)) {
                state.requestBuild();
            }
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (focused) SettingsUi.refresh(state.activity);
        }
        @Override protected Parcelable onSaveInstanceState() {
            Bundle saved = new Bundle();
            saved.putParcelable("view", super.onSaveInstanceState());
            saved.putBoolean("expanded", state.expanded);
            saved.putInt("deviceScroll", state.scroll == null ? 0 : state.scroll.getScrollY());
            saved.putInt("settingsScroll", state.settingsScroll());
            return saved;
        }
        @Override protected void onRestoreInstanceState(Parcelable saved) {
            if (!(saved instanceof Bundle)) { super.onRestoreInstanceState(saved); return; }
            Bundle values = (Bundle) saved;
            super.onRestoreInstanceState(values.getParcelable("view"));
            state.restoreScroll(values.getBoolean("expanded"), values.getInt("deviceScroll"), values.getInt("settingsScroll"));
        }
    }

    private static final class State implements ComponentCallbacks {
        final Activity activity;
        final Page page;
        final SharedPreferences preferences;
        final boolean permissions;
        final List<Binding> bindings = new ArrayList<>();
        final List<Section> sections = new ArrayList<>();
        final View originalInfo, originalLaunch, originalIcon;
        final ViewGroup originalRows;
        final View permissionRow;
        boolean syncing, observing, expanded, landscape, shortLandscape;
        ScrollView scroll, detailScroll;
        LinearLayout body, settingsBody, launchBody;
        TextView connectionTitle, connectionDescription;
        Status status;
        AboutPage aboutPage;
        HelpPage helpPage;
        final AppDetection detection;
        final Runnable detectionChanged = this::syncDetection;
        Row detectionRow;
        SignatureSwitch detectionToggle;
        boolean syncingDetection;
        SignatureSwitch stereoToggle;
        boolean syncingStereo;
        Button launch;
        ImageView glasses;
        FrameLayout hero;
        TextView signature;
        int artworkType = Integer.MIN_VALUE;
        final Runnable rebuild = () -> {
            build();
            if (aboutPage != null && aboutPage.isShowing()) aboutPage.render();
            if (helpPage != null && helpPage.isShowing()) helpPage.render();
        };

        State(Activity activity) {
            this.activity = activity;
            preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            permissions = field(activity, "mLlRoot") == null;
            detection = AppDetection.get(activity);
            originalRows = (ViewGroup) field(activity, "mLlRoot");
            originalInfo = view(activity, "mLlGlassesInfo");
            originalLaunch = view(activity, "mBtnGoLauncher");
            originalIcon = findOriginal("iv_app_icon");
            permissionRow = permissions ? findOriginal("ttv") : null;
            if (permissions && permissionRow == null) throw new IllegalStateException("No permission row");
            if (!permissions && (originalRows == null || originalLaunch == null)) {
                throw new IllegalStateException("No settings bindings");
            }
            page = new Page(this);
        }

        View findOriginal(String id) {
            int resource = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            return resource == 0 ? null : activity.findViewById(resource);
        }
        int dp(float value) { return Signature.dp(activity, value); }

        public void onConfigurationChanged(Configuration configuration) {
            // Application callbacks can precede the Activity's resource update.
            // Rebuild after configuration dispatch so new text sizes use current metrics.
            requestBuild();
        }
        void requestBuild() { page.removeCallbacks(rebuild); page.post(rebuild); }
        public void onLowMemory() {}
        void observe(boolean enabled) {
            if (observing == enabled) return;
            observing = enabled;
            if (enabled) {
                activity.getApplication().registerComponentCallbacks(this);
                if (!permissions) detection.observe(detectionChanged);
            } else {
                activity.getApplication().unregisterComponentCallbacks(this);
                detection.remove(detectionChanged);
                page.removeCallbacks(rebuild);
                if (aboutPage != null) aboutPage.dismiss();
                if (helpPage != null) helpPage.dismiss();
            }
        }

        void build() {
            int previousScroll = scroll == null ? 0 : scroll.getScrollY();
            boolean wasExpanded = expanded;
            int previousDetails = settingsScroll();
            int width = page.getWidth() > 0 ? page.getWidth()
                    : dp(activity.getResources().getConfiguration().screenWidthDp);
            int height = page.getHeight() > 0 ? page.getHeight()
                    : dp(activity.getResources().getConfiguration().screenHeightDp);
            expanded = !permissions && AdaptiveLayout.expanded(activity, width);
            landscape = expanded && AdaptiveLayout.landscape(activity, width, height);
            shortLandscape = landscape && AdaptiveLayout.shortLandscape(activity, width, height);
            launchBody = null;
            bindings.clear(); sections.clear();
            page.removeAllViews();
            page.setBackgroundColor(BACKGROUND);
            page.setContentDescription(permissions ? "Permissions" : "Settings");
            View navigation;
            if (permissions) {
                navigation = back(activity, "Back", v -> activity.finish());
            } else {
                navigation = brand(activity);
                if (originalIcon != null) navigation.setOnClickListener(v -> originalIcon.performClick());
            }
            if (expanded) {
                LinearLayout root = column(activity);
                AppBar bar = new AppBar(activity, navigation, AdaptiveLayout.MAX_WIDTH);
                root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout panes = bounded(activity, AdaptiveLayout.MAX_WIDTH);
                panes.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(panes, centred(0, 1));
                scroll = Signature.scroll(activity);
                detailScroll = Signature.scroll(activity);
                scroll.setContentDescription("Glasses and launch");
                detailScroll.setContentDescription("Settings options");
                LinearLayout devicePane = column(activity);
                panes.addView(devicePane, new LinearLayout.LayoutParams(0, -1, 1));
                devicePane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
                if (landscape) {
                    launchBody = gutters(activity);
                    launchBody.setTag("landscape-launch-actions");
                    launchBody.setPadding(dp(GUTTER), dp(8), dp(GUTTER), dp(12));
                    devicePane.addView(launchBody, centred(-2, 0));
                }
                View divider = new View(activity); divider.setBackgroundColor(DIVIDER);
                panes.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
                panes.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 1));
                body = gutters(activity); settingsBody = gutters(activity);
                for (LinearLayout column : new LinearLayout[]{body, settingsBody}) {
                    column.setPadding(column.getPaddingLeft(), dp(8), column.getPaddingRight(), dp(landscape ? 16 : 32));
                    column.setClipToPadding(false);
                }
                scroll.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
                detailScroll.addView(settingsBody, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
                TextView preferencesTitle = display(activity, "Preferences");
                if (shortLandscape) preferencesTitle.setTextSize(HEADLINE);
                add(settingsBody, preferencesTitle, 8, 0);
                if (!shortLandscape) add(settingsBody, Signature.text(activity, "Make AR Space your own.", BODY, TEXT_SECONDARY), 8, 0);
                bar.liftWith(detailScroll);
                page.addView(root, new FrameLayout.LayoutParams(-1, -1));
            } else {
                Signature.Page layout = new Signature.Page(activity, navigation);
                page.addView(layout.root, new FrameLayout.LayoutParams(-1, -1));
                scroll = layout.scroll; body = layout.body; settingsBody = body; detailScroll = null;
            }
            scroll.setId(SCROLL_ID);
            if (!landscape) add(body, display(activity, permissions ? "Permissions" : "Settings"), 8, 0);
            if (permissions) {
                add(body, Signature.text(activity, "Choose what apps can access in AR Space.", BODY, TEXT_SECONDARY), 8, 0);
                section("App access").add(new Binding(permissionRow, "capture", false));
            } else {
                connectionCard();
                Section experience = section("Experience");
                Section app = section("App");
                Section advanced = section("Advanced");
                Section experimental = section("Experimental");
                Section support = section("Support");
                appMenu(app);
                appPermissions(app);
                // The original permission screen belongs beside the new permission review.
                int related = app.card.getChildCount();
                if (AdbEntry.available()) adbSetup(app);
                appearance(app);
                appDetection(experimental);
                experimentalStereo(experimental);
                about(support);
                help(support);
                for (int i = 0; i < originalRows.getChildCount(); i++) {
                    View source = originalRows.getChildAt(i);
                    if (source == view(activity, "mPPView") || source == view(activity, "mTosView")) continue;
                    if (field(source, "mTvTitle") == null) {
                        // Keep the optional camera settings view and its warnings/actions intact.
                        if (source == view(activity, "mUVCHomeView")) {
                            experience.add(new Binding(source, "camera", true));
                        }
                        continue;
                    }
                    if (source == view(activity, "mSystemNotificationView")) {
                        experience.add(new Binding(source, "notifications", false));
                    } else if (source == view(activity, "mCastScreenView")) {
                        experience.add(new Binding(source, "mirror", false));
                    } else if (source == view(activity, "mScreenColorView")) {
                        experience.add(new Binding(source, "color", false));
                    } else if (source == view(activity, "mSleepTimeView")) {
                        experience.add(new Binding(source, "sleep", false));
                    } else if (source == view(activity, "mForceOtaView")) {
                        advanced.add(new Binding(source, "update", false));
                    } else if (source == view(activity, "mDevOptionView")) {
                        advanced.add(new Binding(source, "code", false));
                    } else {
                        app.insert(new Binding(source, "key", false, "Authority management",
                                "Control screen capture in AR Space"), related++);
                    }
                }
            }
            window(activity.getWindow());
            restoreScroll(wasExpanded, previousScroll, previousDetails);
            sync();
        }

        int settingsScroll() {
            if (detailScroll != null) return detailScroll.getScrollY();
            return scroll == null || sections.isEmpty() ? 0 : Math.max(0, scroll.getScrollY() - sections.get(0).wrapper.getTop());
        }

        void restoreScroll(boolean wasExpanded, int previousScroll, int previousDetails) {
            ScrollView currentScroll = scroll, currentDetails = detailScroll;
            currentScroll.post(() -> {
                if (currentDetails != null) {
                    currentScroll.scrollTo(0, wasExpanded ? previousScroll : 0);
                    currentDetails.scrollTo(0, previousDetails);
                } else {
                    int target = wasExpanded && previousDetails > 0 && !sections.isEmpty()
                            ? sections.get(0).wrapper.getTop() + previousDetails : previousScroll;
                    currentScroll.scrollTo(0, target);
                }
            });
        }

        void connectionCard() {
            LinearLayout top = Signature.row(activity);
            LinearLayout labels = column(activity);
            connectionTitle = Signature.text(activity, "", HEADLINE, TEXT);
            connectionTitle.setAccessibilityHeading(true);
            labels.addView(connectionTitle);
            status = new Status(activity, SUPPORTING);
            add(labels, status, 8, 0);
            top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            View info = iconButton(activity, "info", "Glasses details", v -> showDeviceDetails());
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            infoParams.setMarginEnd(-dp(12)); top.addView(info, infoParams);
            add(body, top, landscape ? 8 : 24, 0);
            hero = new FrameLayout(activity) {
                @Override protected void onMeasure(int width, int height) {
                    int photoHeight = Math.round(MeasureSpec.getSize(width) * 480f / 990);
                    if (shortLandscape) {
                        int windowHeight = page.getHeight() > 0 ? page.getHeight()
                                : dp(activity.getResources().getConfiguration().screenHeightDp);
                        int available = windowHeight - dp(153) - (launchBody == null ? 0 : launchBody.getMeasuredHeight());
                        photoHeight = Math.min(photoHeight, Math.max(dp(64), available));
                    }
                    super.onMeasure(width, MeasureSpec.makeMeasureSpec(photoHeight, MeasureSpec.EXACTLY));
                }
            };
            glasses = new ImageView(activity);
            glasses.setId(GLASSES_IMAGE_ID);
            glasses.setScaleType(shortLandscape ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.FIT_XY);
            glasses.setAdjustViewBounds(true);
            hero.addView(glasses, new FrameLayout.LayoutParams(-1, -1));
            signature = overline(activity, "Your space.\nUnbound.", TEXT_SECONDARY);
            signature.setGravity(Gravity.END); signature.setLineSpacing(dp(5), 1);
            signature.setVisibility(shortLandscape ? View.GONE : View.VISIBLE);
            FrameLayout.LayoutParams signatureParams = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
            signatureParams.setMarginEnd(dp(GUTTER + 6)); hero.addView(signature, signatureParams);
            // The product photo runs edge to edge, outside the page gutters.
            LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
            heroParams.setMargins(-dp(GUTTER), dp(landscape ? 4 : 12), -dp(GUTTER), 0); body.addView(hero, heroParams);
            launch = button(activity, "Enter AR Space", PRIMARY);
            launch.setOnClickListener(v -> {
                if (visible(originalLaunch) && AdbEntry.allowLaunch(activity)) originalLaunch.performClick();
            });
            LinearLayout actions = launchBody == null ? body : launchBody;
            add(actions, launch, 0, 0);
            connectionDescription = Signature.text(activity, "", CAPTION, TEXT_SECONDARY);
            connectionDescription.setGravity(Gravity.CENTER);
            add(actions, connectionDescription, landscape ? 8 : 12, 0);
            artworkType = Integer.MIN_VALUE;
        }

        void showDeviceDetails() {
            boolean connected = visible(originalInfo);
            String details = (text(activity, "mTvGlassSN") + "\n" + text(activity, "mTvGlassFw")).trim();
            Signature.show(dialog(activity)
                    .setTitle(connectionTitle.getText())
                    .setMessage(connected ? (details.isEmpty() ? "Connected via USB-C" : details) : "Plug in your XREAL glasses to view device details.")
                    .setPositiveButton("Done", null));
        }

        void appearance(Section section) {
            Row row = new Row(activity, "moon", "Appearance", "True black, edge to edge");
            row.value("OLED");
            row.setFocusable(true);
            row.setContentDescription("Appearance, OLED black");
            section.card.addView(row);
        }

        void link(Section section, String glyph, String title, String subtitle, View.OnClickListener action) {
            section.card.addView(new Row(activity, glyph, title, subtitle).chevron().onClick(action));
        }

        void appMenu(Section section) {
            link(section, "grid", "App menu", "Reorder and hide your apps", v -> AppMenuActivity.open(activity));
        }

        void appPermissions(Section section) {
            link(section, "permissions", "App permissions", "Review access and setup", v -> PermissionSetupActivity.review(activity));
        }

        void adbSetup(Section section) {
            link(section, "code", "On-device ADB", "Pair this phone and start AR Space", v -> AdbEntry.open(activity));
        }

        void appDetection(Section section) {
            detectionRow = new Row(activity, "game", "Detect as game", "Checking app detection…");
            detectionRow.setTag("experimental-app-detection");
            detectionToggle = new SignatureSwitch(activity);
            detectionToggle.setTag("detect-as-game");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(48));
            params.setMarginStart(dp(12)); detectionRow.addView(detectionToggle, params);
            detectionToggle.setOnCheckedChangeListener((button, enabled) -> {
                if (!syncingDetection) detection.setGame(enabled);
            });
            detectionRow.onClick(v -> { if (detectionToggle.isEnabled()) detectionToggle.toggle(); });
            detectionRow.title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            detectionRow.subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            detectionRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            section.card.addView(detectionRow);
            syncDetection();
        }

        void syncDetection() {
            if (detectionToggle == null) return;
            syncingDetection = true;
            try {
                detectionToggle.setChecked(detection.game);
                detectionToggle.setEnabled(detection.known && !detection.busy);
                String status = detection.busy ? "Applying…" : !detection.known ? "Checking app detection…"
                        : detection.game ? "Game" : "Normal app";
                String description = status + " · Classify Unbound as a game for Android. Gaming features vary by phone. Reopen the app after changing.";
                if (detection.error != null) description += "\n" + detection.error;
                detectionRow.subtitle.setText(description);
                detectionToggle.setContentDescription("Detect as game. " + description);
                detectionRow.setAlpha(detection.busy ? .6f : 1f);
            } finally { syncingDetection = false; }
        }

        void experimentalStereo(Section section) {
            String description = "Try 3D side-by-side (SBS) on glasses that default to 2D. "
                    + "Glasses already using 3D keep their current mode. Reconnect your glasses after changing.";
            Row row = new Row(activity, "mirror", "Force 3D on unsupported glasses", description);
            row.setTag("experimental-stereo");
            stereoToggle = new SignatureSwitch(activity);
            stereoToggle.setTag("force-3d-sbs");
            stereoToggle.setContentDescription("Force 3D on unsupported glasses. " + description);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(48));
            params.setMarginStart(dp(12)); row.addView(stereoToggle, params);
            stereoToggle.setChecked(ExperimentalStereo.enabled(activity));
            stereoToggle.setOnCheckedChangeListener((button, enabled) -> {
                if (syncingStereo) return;
                try {
                    ExperimentalStereo.save(activity, enabled);
                    Toast.makeText(activity, "Reconnect your glasses to apply the change.", Toast.LENGTH_LONG).show();
                } catch (Exception failure) {
                    syncingStereo = true;
                    try { stereoToggle.setChecked(ExperimentalStereo.enabled(activity)); }
                    finally { syncingStereo = false; }
                    Log.w(TAG, "Cannot save experimental 3D setting", failure);
                    Toast.makeText(activity, "Could not save experimental 3D. " + failure.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            row.onClick(v -> stereoToggle.toggle());
            row.title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            section.card.addView(row);
        }

        void about(Section section) {
            link(section, "info", "About", "Project information and legal", v -> {
                if (aboutPage == null) {
                    aboutPage = new AboutPage(activity,
                            () -> openLegal("mPPView"), () -> openLegal("mTosView"));
                }
                aboutPage.show();
            });
        }

        void help(Section section) {
            link(section, "help", "Help", "Support and debugging", v -> {
                if (helpPage == null) helpPage = new HelpPage(activity);
                helpPage.show();
            });
        }

        void openLegal(String fieldName) {
            View target = view(view(activity, fieldName), "mLlRoot");
            if (target != null) target.performClick();
        }

        void sync() {
            if (syncing) return;
            syncing = true;
            try {
                for (Binding binding : bindings) binding.sync();
                for (Section section : sections) section.sync();
                if (!permissions) {
                    boolean connected = visible(originalInfo);
                    status.set(connected ? "Connected via USB-C" : "Not connected",
                            connected ? POSITIVE : TEXT_TERTIARY, TEXT_SECONDARY);
                    Object reportedType = activity.getWindow().getDecorView().getTag(GLASSES_TYPE_TAG);
                    int type = !connected ? 0 : reportedType instanceof Integer
                            ? ((Integer)reportedType).intValue() : GlassesArtwork.connectedType();
                    GlassesArtwork model = GlassesArtwork.forType(type);
                    if (connected && model.asset != null) {
                        if (preferences.getInt("last_glasses_type", 0) != model.type) {
                            preferences.edit().putInt("last_glasses_type", model.type).apply();
                        }
                    } else if (!connected) {
                        model = GlassesArtwork.forType(preferences.getInt("last_glasses_type", 7));
                        if (model.asset == null) model = GlassesArtwork.forType(7);
                    }
                    connectionTitle.setText(model.name);
                    if (artworkType != model.type) {
                        glasses.setImageDrawable(model.asset == null ? null : GlassesArtwork.load(activity, model.asset + ".webp"));
                        glasses.setContentDescription(model.asset == null ? null : model.name + " glasses");
                        glasses.setScaleType(model.widget ? (shortLandscape ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_XY)
                                : ImageView.ScaleType.FIT_CENTER);
                        signature.setVisibility(model.widget ? View.VISIBLE : View.GONE);
                        hero.setVisibility(glasses.getDrawable() == null ? View.GONE : View.VISIBLE);
                        artworkType = model.type;
                    }
                    connectionDescription.setText(connected ? (model.asset == null ? "Connected · Model image unavailable." : "Your spatial workspace is ready.")
                            : "Plug in your glasses to open AR Space.");
                    // Without a photo, keep the button clear of the status line above it.
                    ((LinearLayout.LayoutParams) launch.getLayoutParams()).topMargin = landscape || hero.getVisibility() == View.VISIBLE ? 0 : dp(20);
                    launch.setEnabled(visible(originalLaunch));
                    launch.setText(connected ? "Enter AR Space" : "Connect your glasses");
                }
            } finally { syncing = false; }
        }

        Section section(String title) { return new Section(title); }
        final class Section {
            final LinearLayout wrapper = column(activity), card = group(activity);
            Section(String title) {
                wrapper.addView(heading(activity, title));
                wrapper.addView(card, new LinearLayout.LayoutParams(-1, -2));
                settingsBody.addView(wrapper, new LinearLayout.LayoutParams(-1, -2));
                sections.add(this);
            }
            void add(Binding binding) { insert(binding, card.getChildCount()); }
            void insert(Binding binding, int index) {
                bindings.add(binding);
                card.addView(binding.row, index, new LinearLayout.LayoutParams(-1, -2));
            }
            void sync() {
                int count = 0;
                for (int i = 0; i < card.getChildCount(); i++) if (visible(card.getChildAt(i))) count++;
                wrapper.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            }
        }

        final class Binding {
            final View source, sourceSwitch;
            final Row row;
            final TextView title, subtitle, right;
            final SignatureSwitch toggle;
            final boolean camera;
            final String titleOverride, subtitleOverride;
            Binding(View source, String glyph, boolean camera) {
                this(source, glyph, camera, null, null);
            }
            Binding(View source, String glyph, boolean camera, String titleOverride, String subtitleOverride) {
                this.source = source; this.camera = camera;
                this.titleOverride = titleOverride; this.subtitleOverride = subtitleOverride;
                sourceSwitch = camera ? null : view(source, "mIvSwitch");
                row = new Row(activity, glyph, camera ? "USB camera" : text(source, "mTvTitle"),
                        camera ? "Camera sharing and resolution" : text(source, "mTvSubTitle"));
                title = row.title; subtitle = row.subtitle;
                boolean hasToggle = !camera && visible(view(source, "mLlRightSwitch"));
                toggle = hasToggle ? new SignatureSwitch(activity) : null;
                if (toggle != null) {
                    right = null;
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(48));
                    params.setMarginStart(dp(12)); row.addView(toggle, params);
                    toggle.setContentDescription(title.getText());
                    // Initial state must be set before wiring user callbacks. A checked
                    // legacy default must not trigger refresh while the page is building.
                    toggle.setChecked(checked(sourceSwitch));
                    toggle.setOnCheckedChangeListener((button, value) -> {
                        if (syncing) return;
                        if (checked(sourceSwitch) != value) sourceSwitch.performClick();
                        State.this.sync();
                    });
                    // Expose a single accessible switch with the whole row as its touch target.
                    row.onClick(v -> toggle.performClick());
                    title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                } else {
                    right = row.value("");
                    row.chevron();
                    row.onClick(v -> {
                        View target = camera ? view(source, "mLlRightText") : view(source, "mLlRoot");
                        if (target != null) target.performClick();
                        State.this.sync();
                    });
                }
                sync();
            }
            void sync() {
                row.setVisibility(source.getVisibility());
                if (camera) {
                    // The camera UI has its own confirmation and nested switch. Keep it operable
                    // without replacing those callbacks or bypassing its confirmation dialog.
                    View original = view(source, "mUnselectView");
                    boolean enabled = visible(view(source, "mSelectedView"));
                    right.setText(enabled ? text(source, "mTvRightText") : "Off");
                    row.setOnClickListener(v -> {
                        if (enabled) showCameraActions(source);
                        else {
                            View target = view(original, "mLlRightSwitch");
                            if (target != null) target.performClick();
                        }
                    });
                    return;
                }
                boolean defaultMirroring = source == view(activity, "mCastScreenView");
                title.setText(titleOverride != null ? titleOverride
                        : defaultMirroring ? "Start in screen mirroring" : text(source, "mTvTitle"));
                subtitle.setText(subtitleOverride != null ? subtitleOverride : defaultMirroring
                        ? (checked(sourceSwitch) ? "On · Mirror your phone after reconnecting" : "Off · Open AR Space on connection")
                        : source == view(activity, "mSystemNotificationView") ? "Phone notifications in AR Space"
                        : text(source, "mTvSubTitle"));
                subtitle.setVisibility(subtitle.getText().length() == 0 ? View.GONE : View.VISIBLE);
                if (toggle != null) {
                    toggle.setChecked(checked(sourceSwitch));
                    toggle.setContentDescription(title.getText() + (subtitle.getText().length() == 0 ? "" : ", " + subtitle.getText())
                            + (defaultMirroring ? ". Unplug and reconnect your glasses to apply." : ""));
                } else {
                    right.setText(text(source, "mTvRightText"));
                    right.setVisibility(right.getText().length() == 0 ? View.GONE : View.VISIBLE);
                    row.setContentDescription(title.getText() + (right.getText().length() == 0 ? "" : ", " + right.getText()));
                }
            }
        }

        void showCameraActions(View source) {
            Signature.show(dialog(activity)
                    .setTitle("USB camera")
                    .setItems(new String[]{"Change resolution", "Turn off"}, (d, which) -> {
                        View target = view(source, which == 0 ? "mLlRightText" : "llRightSwitch");
                        if (target != null) target.performClick();
                        sync();
                    }).setNegativeButton(android.R.string.cancel, null));
        }
    }

    /** Monochrome switch with a 48dp touch target and native accessibility semantics. */
    static final class SignatureSwitch extends CompoundButton {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SignatureSwitch(Context context) {
            super(context); setButtonDrawable(null); setBackground(null);
            setMinWidth(0); setMinHeight(0); setPadding(0, 0, 0, 0);
            setFocusable(true); setClickable(true);
        }
        @Override public void setChecked(boolean checked) { super.setChecked(checked); invalidate(); }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            canvas.save(); canvas.translate((getWidth() - 39 * density) / 2f, (getHeight() - 23 * density) / 2f); canvas.scale(density, density);
            paint.setStyle(Paint.Style.FILL); paint.setColor(isChecked() ? TEXT : OUTLINE);
            canvas.drawRoundRect(0, 0, 39, 23, 12, 12, paint);
            paint.setColor(isChecked() ? ON_PRIMARY : TEXT_SECONDARY);
            canvas.drawCircle(isChecked() ? 27.5f : 11.5f, 11.5f, 8.5f, paint);
            if (isPressed() || isFocused()) {
                paint.setColor(0x28FFFFFF);
                canvas.drawCircle(isChecked() ? 27.5f : 11.5f, 11.5f, 12, paint);
            }
            canvas.restore();
        }
    }
}
