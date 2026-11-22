package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    /** Opens the inputs drawer (the remote's Input key while the stock inputs panel is off, RemoteKeys). */
    static final String EXTRA_INPUTS = "io.freemytv.INPUTS";

    /** The Apps key reaches us as ACTION_ALL_APPS (the system's way) or as our extra (RemoteKeys). */
    private static boolean wantsAllApps(Intent intent) {
        return intent != null && (intent.getBooleanExtra(EXTRA_ALL_APPS, false)
                || Intent.ACTION_ALL_APPS.equals(intent.getAction()));
    }
    /** Chooses the home screen's look: true = Left 4 Dead 2's (L4dView), false = the original (adb shell am start ... --ez io.freemytv.l4d2 true). */
    static final String EXTRA_L4D2 = "io.freemytv.l4d2";

    private HomeView home;
    private L4dView l4d;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        applyLook(getIntent());
        if (Prefs.l4d2()) {
            try {
                l4d = new L4dView(this);
            } catch (RuntimeException e) {   // never leave the TV without a home screen: the original one
                AppLog.w("the L4D2 look failed, back to the original: " + e);
                Prefs.setL4d2(false);
            }
        }
        if (l4d != null) setContentView(l4d);
        else {
            home = new HomeView(this);
            setContentView(home);
        }
        getWindow().setBackgroundDrawable(null);
        if (wantsAllApps(getIntent())) showAllApps();
        else if (getIntent().getBooleanExtra(EXTRA_INPUTS, false)) showInputs();
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            liveWaits = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
            if (liveWaits) {
                AppLog.w("at power-on: Live TV in 10 s, unless a key comes first");
                screen().postDelayed(powerOnLive, 10_000);
            }
        }
    }

    /** The look asked for by the intent (read once: recreate() hands the same intent over again). */
    private static void applyLook(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_L4D2)) return;
        Prefs.setL4d2(intent.getBooleanExtra(EXTRA_L4D2, false));
        intent.removeExtra(EXTRA_L4D2);
    }

    // The two looks answer the same calls.
    private android.view.View screen() { return l4d != null ? l4d : home; }
    private void showAllApps() { if (l4d != null) l4d.showAllApps(); else if (home != null) home.showAllApps(); }
    private void showInputs() { if (l4d != null) l4d.showInputs(); else if (home != null) home.showInputs(); }
    private void showRecents() { if (l4d != null) l4d.showRecents(); else if (home != null) home.showRecents(); }
    private void resetHome() { if (l4d != null) l4d.reset(true); else if (home != null) home.reset(true); }
    private void resumeHome() { if (l4d != null) l4d.resume(); else if (home != null) home.resume(); }
    private void pauseHome() { if (l4d != null) l4d.pause(); else if (home != null) home.pause(); }
    private boolean homeKey(KeyEvent e) { return l4d != null ? l4d.onKey(e) : home != null && home.onKey(e); }

    /**
     * liveWaits: At power-on "Live TV" is still to come in this start. liveDark: the screen went off while it waited (a
     * TV that starts in standby after a power cut, as Android's quiescent boot does, #489): the 10 s start again when
     * the screen comes on with the home screen in front.
     */
    private boolean resumed, liveWaits, liveDark;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!liveWaits) return;
        if (!resumed) {
            if (liveDark) {
                AppLog.w("at power-on: the screen is off, Live TV 10 s after it comes on");
                return;
            }
            liveWaits = false;
            AppLog.w("at power-on: another screen is in front, no Live TV");
            return;
        }
        liveWaits = false;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        resumeHome();
        if (liveDark && screen() != null) {   // the screen came on with the home screen in front
            liveDark = false;
            screen().removeCallbacks(powerOnLive);
            screen().postDelayed(powerOnLive, 10_000);
        }
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        if (liveWaits) {   // the screen going off, not another screen coming to the front
            PowerManager power = getSystemService(PowerManager.class);
            liveDark = power != null && !power.isInteractive();
        }
        pauseHome();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (liveDark) {   // Home while Live TV waits for the screen: it counts as a key
            liveWaits = liveDark = false;
            AppLog.w("at power-on: Home came first, no Live TV");
        }
        if (intent.hasExtra(EXTRA_L4D2)) {   // another look was asked for: draw everything again
            applyLook(intent);
            recreate();
            return;
        }
        if (screen() == null) return;
        if (wantsAllApps(intent)) {
            showAllApps();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_INPUTS, false)) {
            showInputs();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) showRecents();
        else resetHome();
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        if (screen() != null) screen().removeCallbacks(powerOnLive);
        if (l4d != null) l4d.destroy();
        else if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    /** The key is one of Remote Buttons' rows on this TV. */
    private static boolean remoteButton(int keyCode) {
        if (!RemoteKeys.supported()) return false;
        for (int key : Prefs.KEYS) if (key == keyCode) return true;
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (liveWaits) {
            liveWaits = liveDark = false;
            AppLog.w("at power-on: a key came first (" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + (event.getAction() == KeyEvent.ACTION_UP ? " up" : "") + "), no Live TV");
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (HomeApps.sourceKey(this, event)) return true;
        // The Apps key, unless it has a row in Remote Buttons on this TV (Hisense): there the row decides, and on
        // Nothing the key is left to the TV, so it must not open the sheet here either. Not by whether the key service
        // is on: a TCL's is on for its app buttons, and its Apps key then did nothing.
        if (event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS && !remoteButton(KeyEvent.KEYCODE_ALL_APPS)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) showAllApps();
            return true;
        }
        if (homeKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
