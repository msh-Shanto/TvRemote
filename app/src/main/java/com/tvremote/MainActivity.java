package com.tvremote;

import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ── UI refs ──────────────────────────────────────────────────────────────
    private EditText etIp;
    private Button btnConnect;
    private TextView tvLog, tvStatus;
    private View statusDot;
    private TextView tabRemote, tabMouse, tabKeyboard;
    private LinearLayout paneContainer;
    private TextView shiftKey, ctrlKey, altKey;

    // ── State ────────────────────────────────────────────────────────────────
    private boolean connected = false;
    private AdbClient adb = null;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float curX = 960, curY = 540;
    private float lastTx, lastTy;
    private int speed = 5;
    private boolean shiftOn, ctrlOn, altOn;

    // ═════════════════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp        = (EditText)      findViewById(R.id.etIp);
        btnConnect  = (Button)        findViewById(R.id.btnConnect);
        tvLog       = (TextView)      findViewById(R.id.tvLog);
        tvStatus    = (TextView)      findViewById(R.id.tvStatus);
        statusDot   = findViewById(R.id.statusDot);
        tabRemote   = (TextView)      findViewById(R.id.tabRemote);
        tabMouse    = (TextView)      findViewById(R.id.tabMouse);
        tabKeyboard = (TextView)      findViewById(R.id.tabKeyboard);
        paneContainer = (LinearLayout) findViewById(R.id.paneContainer);

        paneContainer.addView(buildRemotePane());
        paneContainer.addView(buildMousePane());
        paneContainer.addView(buildKeyboardPane());
        switchTab(0);

        btnConnect.setOnClickListener(v -> toggleConnect());
        tabRemote  .setOnClickListener(v -> switchTab(0));
        tabMouse   .setOnClickListener(v -> switchTab(1));
        tabKeyboard.setOnClickListener(v -> switchTab(2));
    }

    // ── Tab switching ─────────────────────────────────────────────────────────
    private void switchTab(int i) {
        paneContainer.getChildAt(0).setVisibility(i==0?View.VISIBLE:View.GONE);
        paneContainer.getChildAt(1).setVisibility(i==1?View.VISIBLE:View.GONE);
        paneContainer.getChildAt(2).setVisibility(i==2?View.VISIBLE:View.GONE);
        styleTab(tabRemote,   i==0);
        styleTab(tabMouse,    i==1);
        styleTab(tabKeyboard, i==2);
    }
    private void styleTab(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.tab_active : R.drawable.tab_inactive);
        t.setTextColor(on ? Color.WHITE : Color.parseColor("#6B6B8A"));
    }

    // ═════════════════ REMOTE PANE ══════════════════════════════════════════
    private LinearLayout buildRemotePane() {
        LinearLayout p = vPane();

        // ── top row: power / home / back / menu ──
        p.addView(label("Power & System"));
        GridLayout g1 = grid(4);
        addIconBtn(g1, "⏻",  "#FF6B6B", () -> key("KEYCODE_POWER"));
        addIconBtn(g1, "⌂",  null,       () -> key("KEYCODE_HOME"));
        addIconBtn(g1, "←",  null,       () -> key("KEYCODE_BACK"));
        addIconBtn(g1, "☰",  null,       () -> key("KEYCODE_MENU"));
        p.addView(g1);

        // ── D-Pad ──
        p.addView(label("Navigate"));
        p.addView(makeDpad());

        // ── Volume + Media ──
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        lpm(row, MATCH, WRAP, 0, 0, 0, dp(10));

        LinearLayout volBox = groupBox("Volume");
        GridLayout vg = grid(3);
        addIconBtn(vg, "🔉", null, () -> key("KEYCODE_VOLUME_DOWN"));
        addIconBtn(vg, "🔇", null, () -> key("KEYCODE_VOLUME_MUTE"));
        addIconBtn(vg, "🔊", null, () -> key("KEYCODE_VOLUME_UP"));
        volBox.addView(vg);

        LinearLayout medBox = groupBox("Media");
        GridLayout mg = grid(3);
        addIconBtn(mg, "⏮", null, () -> key("KEYCODE_MEDIA_REWIND"));
        addIconBtn(mg, "⏯", null, () -> key("KEYCODE_MEDIA_PLAY_PAUSE"));
        addIconBtn(mg, "⏭", null, () -> key("KEYCODE_MEDIA_FAST_FORWARD"));
        medBox.addView(mg);

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, WRAP, 1f);
        half.setMarginEnd(dp(6));
        row.addView(volBox, half);
        row.addView(medBox, new LinearLayout.LayoutParams(0, WRAP, 1f));
        p.addView(row);

        // ── Quick launch ──
        p.addView(label("Quick launch"));
        GridLayout ql = grid(4);
        addIconBtn(ql, "⚙", null,      () -> key("KEYCODE_SETTINGS"));
        addIconBtn(ql, "🔍", null,     () -> key("KEYCODE_SEARCH"));
        addTextBtn(ql, "YT",  "#FF4444", () -> launch("com.google.android.youtube.tv"));
        addTextBtn(ql, "N",   "#E50914", () -> launch("com.netflix.ninja"));
        p.addView(ql);

        return p;
    }

    private View makeDpad() {
        int sz  = dp(54);
        int gap = dp(4);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        lpm(wrap, MATCH, WRAP, 0, 0, 0, dp(10));

        String[][] labels  = {{"","▲",""},{"◀","OK","▶"},{"","▼",""}};
        Runnable[] actions = {
            null,                                          () -> key("KEYCODE_DPAD_UP"),   null,
            () -> key("KEYCODE_DPAD_LEFT"),  () -> key("KEYCODE_DPAD_CENTER"), () -> key("KEYCODE_DPAD_RIGHT"),
            null,                                          () -> key("KEYCODE_DPAD_DOWN"), null
        };

        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(WRAP, sz);
            if (r > 0) rlp.topMargin = gap;
            row.setLayoutParams(rlp);

            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                View btn;
                String lbl = labels[r][c];
                if (lbl.isEmpty()) {
                    btn = new View(this);
                } else if (lbl.equals("OK")) {
                    TextView tv = new TextView(this);
                    tv.setText("OK"); tv.setGravity(Gravity.CENTER);
                    tv.setTextColor(Color.WHITE); tv.setTextSize(14);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundResource(R.drawable.btn_ok);
                    tv.setOnClickListener(v -> actions[idx].run());
                    btn = tv;
                } else {
                    TextView tv = new TextView(this);
                    tv.setText(lbl); tv.setGravity(Gravity.CENTER);
                    tv.setTextColor(Color.parseColor("#E8E8F0")); tv.setTextSize(20);
                    tv.setBackgroundResource(R.drawable.btn_normal);
                    tv.setOnClickListener(v -> actions[idx].run());
                    btn = tv;
                }
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(sz, sz);
                if (c > 0) bp.leftMargin = gap;
                btn.setLayoutParams(bp);
                row.addView(btn);
            }
            wrap.addView(row);
        }
        return wrap;
    }

    // ═════════════════ MOUSE PANE ═══════════════════════════════════════════
    private LinearLayout buildMousePane() {
        LinearLayout p = vPane();

        // Trackpad
        p.addView(label("Trackpad  —  drag to move cursor"));
        p.addView(makeTrackpad());

        // Mouse buttons
        GridLayout mb = grid(3);
        addTextBtn(mb, "Left click",   null,      () -> tap(true));
        addTextBtn(mb, "Scroll btn",   "#6C63FF", () -> key("KEYCODE_DPAD_CENTER"));
        addTextBtn(mb, "Right click",  null,      () -> tap(false));
        p.addView(mb);

        // Scroll
        p.addView(label("Scroll"));
        GridLayout sg = grid(4);
        addIconBtn(sg, "▲▲", null, () -> scroll("up",   3));
        addIconBtn(sg, "▲",  null, () -> scroll("up",   1));
        addIconBtn(sg, "▼",  null, () -> scroll("down", 1));
        addIconBtn(sg, "▼▼", null, () -> scroll("down", 3));
        p.addView(sg);

        // Speed
        p.addView(label("Cursor speed"));
        p.addView(makeSpeedSlider());

        return p;
    }

    private View makeTrackpad() {
        FrameLayout pad = new FrameLayout(this);
        pad.setBackgroundResource(R.drawable.trackpad_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(160));
        lp.bottomMargin = dp(10);
        pad.setLayoutParams(lp);

        TextView hint = new TextView(this);
        hint.setText("Touch & drag to move cursor");
        hint.setTextColor(Color.parseColor("#6B6B8A"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        pad.addView(hint, new FrameLayout.LayoutParams(MATCH, MATCH));

        // cursor dot
        View dot = new View(this);
        dot.setBackgroundColor(Color.parseColor("#6C63FF"));
        FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(dp(14), dp(14));
        dp2.gravity = Gravity.CENTER;
        dot.setLayoutParams(dp2);
        dot.setVisibility(View.INVISIBLE);
        pad.addView(dot);

        pad.setOnTouchListener((v, e) -> {
            float x = e.getX(), y = e.getY();
            int w = v.getWidth(),  h = v.getHeight();
            int action = e.getAction();

            if (action == MotionEvent.ACTION_DOWN) {
                lastTx = x; lastTy = y;
                hint.setVisibility(View.INVISIBLE);
                dot.setVisibility(View.VISIBLE);
                moveDot(dot, x, y, w, h);

            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = (x - lastTx) * speed * 2.8f;
                float dy = (y - lastTy) * speed * 2.8f;
                lastTx = x; lastTy = y;
                curX = Math.max(0, Math.min(1920, curX + dx));
                curY = Math.max(0, Math.min(1080, curY + dy));
                moveDot(dot, x, y, w, h);
                if (connected && (Math.abs(dx) > 1.5f || Math.abs(dy) > 1.5f))
                    shell("input mouse move " + (int)curX + " " + (int)curY);

            } else if (action == MotionEvent.ACTION_UP) {
                dot.postDelayed(() -> dot.setVisibility(View.INVISIBLE), 600);
            }
            return true;
        });
        return pad;
    }

    private void moveDot(View dot, float x, float y, int w, int h) {
        int r = dp(7);
        dot.setX(Math.max(0, Math.min(w - r*2, x - r)));
        dot.setY(Math.max(0, Math.min(h - r*2, y - r)));
    }

    private View makeSpeedSlider() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        lpm(row, MATCH, WRAP, 0, 0, 0, dp(10));

        TextView slow = small("Slow"); slow.setPadding(0, 0, dp(8), 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(9); sb.setProgress(4);
        sb.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { speed = p+1; }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        TextView fast = small("Fast"); fast.setPadding(dp(8), 0, 0, 0);

        row.addView(slow); row.addView(sb); row.addView(fast);
        return row;
    }

    // ═════════════════ KEYBOARD PANE ════════════════════════════════════════
    private LinearLayout buildKeyboardPane() {
        LinearLayout p = vPane();

        // Text send bar
        LinearLayout txRow = new LinearLayout(this);
        txRow.setOrientation(LinearLayout.HORIZONTAL);
        lpm(txRow, MATCH, WRAP, 0, 0, 0, dp(10));

        EditText txIn = new EditText(this);
        txIn.setHint("Type text to send to TV…");
        txIn.setHintTextColor(Color.parseColor("#6B6B8A"));
        txIn.setTextColor(Color.parseColor("#E8E8F0"));
        txIn.setTextSize(13); txIn.setBackgroundResource(R.drawable.input_bg);
        txIn.setPadding(dp(12),0,dp(12),0);
        txIn.setInputType(InputType.TYPE_CLASS_TEXT); txIn.setSingleLine(true);
        LinearLayout.LayoutParams txlp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        txlp.setMarginEnd(dp(8)); txIn.setLayoutParams(txlp);

        Button sendBtn = new Button(this);
        sendBtn.setText("Send"); sendBtn.setTextColor(Color.WHITE);
        sendBtn.setTextSize(13); sendBtn.setTypeface(null, Typeface.BOLD);
        sendBtn.setBackgroundResource(R.drawable.btn_accent);
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(WRAP, dp(42)));
        sendBtn.setPadding(dp(18),0,dp(18),0);
        sendBtn.setOnClickListener(v -> {
            String t = txIn.getText().toString();
            if (!t.isEmpty()) { sendText(t); txIn.setText(""); }
        });
        txRow.addView(txIn); txRow.addView(sendBtn);
        p.addView(txRow);

        // ── Keyboard ──
        LinearLayout kbd = new LinearLayout(this);
        kbd.setOrientation(LinearLayout.VERTICAL);
        kbd.setBackgroundResource(R.drawable.surface_bg);
        kbd.setPadding(dp(8), dp(8), dp(8), dp(8));
        lpm(kbd, MATCH, WRAP, 0, 0, 0, dp(8));

        // Function keys
        kbd.addView(krow(
            new String[]{"Esc","F1","F2","F3","F4","F5","F6"},
            new String[]{"KEYCODE_ESCAPE","KEYCODE_F1","KEYCODE_F2","KEYCODE_F3","KEYCODE_F4","KEYCODE_F5","KEYCODE_F6"},
            true, false));

        // Number row + backspace
        kbd.addView(krow("1234567890", false, true));

        // QWERTY rows
        kbd.addView(krow("qwertyuiop", false, false));
        kbd.addView(krow("asdfghjkl",  false, false));

        // Shift row
        LinearLayout shiftRow = krow("zxcvbnm.", false, false);
        shiftKey = modKey("⇧ Shift");
        shiftKey.setOnClickListener(v -> toggleMod("shift"));
        shiftRow.addView(shiftKey, 0);
        kbd.addView(shiftRow);

        // Ctrl / Alt / Space / Arrows
        LinearLayout botRow = new LinearLayout(this);
        botRow.setOrientation(LinearLayout.HORIZONTAL);
        lpm(botRow, MATCH, WRAP, 0, dp(3), 0, 0);

        ctrlKey = modKey("Ctrl");
        ctrlKey.setOnClickListener(v -> toggleMod("ctrl"));
        LinearLayout.LayoutParams mklp = new LinearLayout.LayoutParams(dp(50), dp(34));
        mklp.setMarginEnd(dp(3));
        ctrlKey.setLayoutParams(mklp);

        altKey = modKey("Alt");
        altKey.setOnClickListener(v -> toggleMod("alt"));
        LinearLayout.LayoutParams aklp = new LinearLayout.LayoutParams(dp(45), dp(34));
        aklp.setMarginEnd(dp(3));
        altKey.setLayoutParams(aklp);

        TextView space = keyView("Space");
        LinearLayout.LayoutParams sklp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        sklp.setMarginEnd(dp(3));
        space.setLayoutParams(sklp);
        space.setOnClickListener(v -> key("KEYCODE_SPACE"));

        TextView arL = keyView("◀"); arL.setLayoutParams(kLP(0, dp(3)));
        arL.setOnClickListener(v -> key("KEYCODE_DPAD_LEFT"));
        TextView arR = keyView("▶"); arR.setLayoutParams(kLP(0, 0));
        arR.setOnClickListener(v -> key("KEYCODE_DPAD_RIGHT"));

        botRow.addView(ctrlKey); botRow.addView(altKey);
        botRow.addView(space); botRow.addView(arL); botRow.addView(arR);
        kbd.addView(botRow);

        // Extras row
        LinearLayout extRow = new LinearLayout(this);
        extRow.setOrientation(LinearLayout.HORIZONTAL);
        lpm(extRow, MATCH, WRAP, 0, dp(3), 0, 0);
        String[] elbl = {",","/","-","=","Tab","⌫","↵"};
        String[] ekc  = {"KEYCODE_COMMA","KEYCODE_SLASH","KEYCODE_MINUS","KEYCODE_EQUALS",
                          "KEYCODE_TAB","KEYCODE_DEL","KEYCODE_ENTER"};
        for (int i = 0; i < elbl.length; i++) {
            TextView k = keyView(elbl[i]);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(32), 1f);
            if (i > 0) klp.leftMargin = dp(3);
            k.setLayoutParams(klp);
            final String kc = ekc[i];
            k.setOnClickListener(v -> key(kc));
            extRow.addView(k);
        }
        kbd.addView(extRow);
        p.addView(kbd);

        // Arrow cluster
        p.addView(label("Arrow keys"));
        GridLayout ag = grid(3);
        addIconBtn(ag, "",  null, null);
        addIconBtn(ag, "▲", null, () -> key("KEYCODE_DPAD_UP"));
        addIconBtn(ag, "",  null, null);
        addIconBtn(ag, "◀", null, () -> key("KEYCODE_DPAD_LEFT"));
        addIconBtn(ag, "▼", null, () -> key("KEYCODE_DPAD_DOWN"));
        addIconBtn(ag, "▶", null, () -> key("KEYCODE_DPAD_RIGHT"));
        p.addView(ag);

        return p;
    }

    // ── Keyboard helpers ─────────────────────────────────────────────────────
    private LinearLayout krow(String chars, boolean small, boolean backspace) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        lpm(row, MATCH, WRAP, 0, dp(3), 0, 0);
        for (char c : chars.toCharArray()) {
            TextView k = keyView(String.valueOf(c));
            k.setLayoutParams(kLP(0, dp(2)));
            if (small) k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            final char fc = c;
            k.setOnClickListener(v -> {
                String s = shiftOn ? String.valueOf(fc).toUpperCase() : String.valueOf(fc);
                sendText(s);
                if (shiftOn) { shiftOn = false; shiftKey.setBackgroundResource(R.drawable.key_bg); shiftKey.setTextColor(Color.parseColor("#E8E8F0")); }
            });
            row.addView(k);
        }
        if (backspace) {
            TextView bk = keyView("⌫");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(32), 1.5f);
            blp.leftMargin = dp(3); bk.setLayoutParams(blp);
            bk.setOnClickListener(v -> key("KEYCODE_DEL"));
            row.addView(bk);
        }
        return row;
    }

    private LinearLayout krow(String[] labels, String[] codes, boolean small, boolean bs) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        lpm(row, MATCH, WRAP, 0, 0, 0, dp(6));
        for (int i = 0; i < labels.length; i++) {
            TextView k = keyView(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            if (i > 0) lp.leftMargin = dp(3);
            k.setLayoutParams(lp);
            k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            final String kc = codes[i];
            k.setOnClickListener(v -> key(kc));
            row.addView(k);
        }
        return row;
    }

    private LinearLayout.LayoutParams kLP(int top, int side) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        lp.topMargin = top; lp.leftMargin = side; lp.rightMargin = side;
        return lp;
    }

    private TextView keyView(String lbl) {
        TextView k = new TextView(this);
        k.setText(lbl); k.setGravity(Gravity.CENTER);
        k.setTextColor(Color.parseColor("#E8E8F0")); k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        k.setBackgroundResource(R.drawable.key_bg);
        return k;
    }

    private TextView modKey(String lbl) {
        TextView k = keyView(lbl); k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(34)));
        return k;
    }

    private void toggleMod(String mod) {
        switch (mod) {
            case "shift":
                shiftOn = !shiftOn;
                shiftKey.setBackgroundResource(shiftOn ? R.drawable.key_mod_active : R.drawable.key_bg);
                shiftKey.setTextColor(shiftOn ? Color.WHITE : Color.parseColor("#E8E8F0"));
                break;
            case "ctrl":
                ctrlOn = !ctrlOn;
                ctrlKey.setBackgroundResource(ctrlOn ? R.drawable.key_mod_active : R.drawable.key_bg);
                ctrlKey.setTextColor(ctrlOn ? Color.WHITE : Color.parseColor("#E8E8F0"));
                break;
            case "alt":
                altOn = !altOn;
                altKey.setBackgroundResource(altOn ? R.drawable.key_mod_active : R.drawable.key_bg);
                altKey.setTextColor(altOn ? Color.WHITE : Color.parseColor("#E8E8F0"));
                break;
        }
    }

    // ═════════════════ ADB COMMANDS ═════════════════════════════════════════
    private void toggleConnect() {
        if (connected) { doDisconnect(); return; }
        String ip = etIp.getText().toString().trim();
        if (ip.isEmpty()) { log("Enter TV IP address first"); return; }
        doConnect(ip);
    }

    private void doConnect(String ip) {
        log("Connecting to " + ip + "…");
        btnConnect.setEnabled(false);
        String host = ip.contains(":") ? ip.split(":")[0] : ip;
        int port = ip.contains(":") ? Integer.parseInt(ip.split(":")[1]) : 5555;
        pool.execute(() -> {
            try {
                AdbClient client = new AdbClient(host, port);
                client.connect();
                String r = client.shell("echo ping");
                if (r != null && r.contains("ping")) {
                    adb = client;
                    ui.post(() -> { connected = true; setConnUI(true); log("Connected — TV ready!"); });
                } else {
                    client.close();
                    ui.post(() -> { btnConnect.setEnabled(true); log("Handshake failed — accept ADB prompt on TV"); });
                }
            } catch (Exception e) {
                ui.post(() -> { btnConnect.setEnabled(true); log("Error: " + e.getMessage()); });
            }
        });
    }

    private void doDisconnect() {
        if (adb != null) { AdbClient c = adb; adb = null; pool.execute(c::close); }
        connected = false; setConnUI(false); log("Disconnected");
    }

    private void setConnUI(boolean on) {
        statusDot.setBackgroundResource(on ? R.drawable.dot_green : R.drawable.dot_shape);
        tvStatus.setText(on ? "Connected" : "Disconnected");
        tvStatus.setTextColor(Color.parseColor(on ? "#4ADE80" : "#6B6B8A"));
        btnConnect.setText(on ? "Disconnect" : "Connect");
        btnConnect.setBackgroundResource(on ? R.drawable.btn_red : R.drawable.btn_accent);
        btnConnect.setEnabled(true);
    }

    private void key(String kc) {
        if (!chk()) return;
        String cmd;
        if (ctrlOn) {
            cmd = "input keyevent 113 " + kc;   // META_CTRL_ON
            ctrlOn = false; ui.post(() -> { ctrlKey.setBackgroundResource(R.drawable.key_bg); ctrlKey.setTextColor(Color.parseColor("#E8E8F0")); });
        } else if (altOn) {
            cmd = "input keyevent 57 " + kc;    // META_ALT_ON
            altOn = false; ui.post(() -> { altKey.setBackgroundResource(R.drawable.key_bg); altKey.setTextColor(Color.parseColor("#E8E8F0")); });
        } else {
            cmd = "input keyevent " + kc;
        }
        log(kc); shell(cmd);
    }

    private void sendText(String text) {
        if (!chk()) return;
        String escaped = text.replace("\\","\\\\").replace("\"","\\\"")
                             .replace("'","\\'").replace(" ","%s")
                             .replace("(","\\(").replace(")","\\)").replace("&","\\&");
        log("text: " + text); shell("input text " + escaped);
    }

    private void tap(boolean left) {
        if (!chk()) return;
        log((left?"Left":"Right") + " click (" + (int)curX + "," + (int)curY + ")");
        if (left) shell("input tap " + (int)curX + " " + (int)curY);
        else      shell("input keyevent KEYCODE_SOFT_RIGHT");
    }

    private void scroll(String dir, int n) {
        if (!chk()) return;
        String kc = dir.equals("up") ? "KEYCODE_PAGE_UP" : "KEYCODE_PAGE_DOWN";
        log("scroll " + dir + " x" + n);
        for (int i=0;i<n;i++) shell("input keyevent " + kc);
    }

    private void launch(String pkg) {
        if (!chk()) return;
        log("launch " + pkg.substring(pkg.lastIndexOf('.')+1));
        shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
    }

    private void shell(String cmd) {
        if (adb == null) return;
        pool.execute(() -> {
            try { adb.shell(cmd); }
            catch (Exception e) { ui.post(() -> log("err: " + e.getMessage())); }
        });
    }

    private boolean chk() {
        if (!connected || adb == null) { log("Not connected — tap Connect first"); return false; }
        return true;
    }

    private void log(String m) { ui.post(() -> tvLog.setText("> " + m)); }

    // ═════════════════ LAYOUT HELPERS ═══════════════════════════════════════
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT;

    private LinearLayout vPane() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
        return p;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t.toUpperCase()); tv.setTextColor(Color.parseColor("#6B6B8A"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(null, Typeface.BOLD); tv.setLetterSpacing(0.1f);
        lpm(tv, MATCH, WRAP, 0, dp(4), 0, dp(6));
        return tv;
    }

    private TextView small(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextColor(Color.parseColor("#6B6B8A")); tv.setTextSize(11);
        return tv;
    }

    private GridLayout grid(int cols) {
        GridLayout g = new GridLayout(this); g.setColumnCount(cols);
        lpm(g, MATCH, WRAP, 0, 0, 0, dp(10));
        return g;
    }

    private void addIconBtn(GridLayout g, String icon, String color, Runnable r) {
        TextView btn = new TextView(this);
        btn.setText(icon); btn.setGravity(Gravity.CENTER); btn.setTextSize(18);
        btn.setTextColor(color!=null?Color.parseColor(color):Color.parseColor("#E8E8F0"));
        btn.setBackgroundResource(R.drawable.btn_normal);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width=0; lp.height=dp(46);
        lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);
        lp.setMargins(dp(2),dp(2),dp(2),dp(2)); btn.setLayoutParams(lp);
        if (r != null) btn.setOnClickListener(v -> r.run());
        g.addView(btn);
    }

    private void addTextBtn(GridLayout g, String text, String color, Runnable r) {
        TextView btn = new TextView(this);
        btn.setText(text); btn.setGravity(Gravity.CENTER); btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(color!=null?Color.parseColor(color):Color.parseColor("#E8E8F0"));
        btn.setBackgroundResource(R.drawable.btn_normal);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width=0; lp.height=dp(46);
        lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);
        lp.setMargins(dp(2),dp(2),dp(2),dp(2)); btn.setLayoutParams(lp);
        if (r != null) btn.setOnClickListener(v -> r.run());
        g.addView(btn);
    }

    private LinearLayout groupBox(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.surface_bg);
        box.setPadding(dp(8),dp(8),dp(8),dp(8));
        TextView lbl = new TextView(this); lbl.setText(title.toUpperCase());
        lbl.setTextColor(Color.parseColor("#6B6B8A")); lbl.setTextSize(9);
        lbl.setTypeface(null,Typeface.BOLD); lbl.setLetterSpacing(0.1f);
        lbl.setGravity(Gravity.CENTER_HORIZONTAL);
        lpm(lbl, MATCH, WRAP, 0, 0, 0, dp(5));
        box.addView(lbl);
        return box;
    }

    private void lpm(View v, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w,h);
        lp.leftMargin=l; lp.topMargin=t; lp.rightMargin=r; lp.bottomMargin=b;
        v.setLayoutParams(lp);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (adb != null) adb.close();
        pool.shutdown();
    }

    // ═════════════════ ADB CLIENT ════════════════════════════════════════════
    // Implements the ADB wire protocol directly — no root, no PC bridge needed.
    // Works with Android 5.1.1 (API 22) over TCP port 5555.
    static class AdbClient {
        private static final int A_CNXN = 0x4e584e43;
        private static final int A_OPEN = 0x4e45504f;
        private static final int A_OKAY = 0x59414b4f;
        private static final int A_CLSE = 0x45534c43;
        private static final int A_WRTE = 0x45545257;
        private static final int VERSION = 0x01000000;
        private static final int MAXDATA = 4096;

        private final String host;
        private final int port;
        private Socket sock;
        private OutputStream out;
        private java.io.InputStream in;
        private int localId = 1;

        AdbClient(String host, int port) { this.host = host; this.port = port; }

        void connect() throws IOException {
            sock = new Socket(host, port);
            sock.setSoTimeout(6000);
            sock.setTcpNoDelay(true);
            out = sock.getOutputStream();
            in  = sock.getInputStream();
            // Send CNXN
            byte[] banner = "host::features=shell_v2".getBytes("UTF-8");
            writeMsg(A_CNXN, VERSION, MAXDATA, banner);
            // Read CNXN from device
            byte[] hdr = readExact(24);
            if (hdr == null) throw new IOException("No response");
            int cmd = le32(hdr, 0);
            int len = le32(hdr, 12);
            if (len > 0) readExact(len); // skip device banner
            if (cmd != A_CNXN) throw new IOException("Expected CNXN, got " + Integer.toHexString(cmd));
        }

        String shell(String cmd) throws IOException {
            int myId = localId++;
            byte[] svc = ("shell:" + cmd).getBytes("UTF-8");
            writeMsg(A_OPEN, myId, 0, svc);

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 4000;

            while (System.currentTimeMillis() < deadline) {
                byte[] hdr = readExact(24);
                if (hdr == null) break;
                int c   = le32(hdr, 0);
                int rId = le32(hdr, 4);
                int len = le32(hdr, 12);
                byte[] data = len > 0 ? readExact(len) : new byte[0];

                if (c == A_OKAY) {
                    writeMsg(A_OKAY, myId, rId, new byte[0]);
                } else if (c == A_WRTE) {
                    if (data != null) sb.append(new String(data, "UTF-8"));
                    writeMsg(A_OKAY, myId, rId, new byte[0]);
                } else if (c == A_CLSE) {
                    writeMsg(A_CLSE, myId, rId, new byte[0]);
                    break;
                }
            }
            return sb.toString();
        }

        private void writeMsg(int cmd, int a0, int a1, byte[] data) throws IOException {
            int len = data.length;
            int crc = 0; for (byte b : data) crc += (b & 0xFF);
            byte[] h = new byte[24];
            wle32(h, 0, cmd); wle32(h, 4, a0); wle32(h, 8, a1);
            wle32(h, 12, len); wle32(h, 16, crc); wle32(h, 20, cmd ^ 0xFFFFFFFF);
            out.write(h);
            if (len > 0) out.write(data);
            out.flush();
        }

        private byte[] readExact(int n) throws IOException {
            byte[] buf = new byte[n]; int total = 0;
            while (total < n) {
                int r = in.read(buf, total, n - total);
                if (r < 0) return null;
                total += r;
            }
            return buf;
        }

        private int le32(byte[] b, int o) {
            return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24);
        }
        private void wle32(byte[] b, int o, int v) {
            b[o]=(byte)v; b[o+1]=(byte)(v>>8); b[o+2]=(byte)(v>>16); b[o+3]=(byte)(v>>24);
        }

        void close() { try { if (sock!=null) sock.close(); } catch (IOException ignored) {} }
    }
}
