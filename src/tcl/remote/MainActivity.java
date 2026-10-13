package tcl.remote;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int UP = 19;
    private static final int DOWN = 20;
    private static final int LEFT = 21;
    private static final int RIGHT = 22;
    private static final int OK = 23;
    private static final int VOL_UP = 24;
    private static final int VOL_DOWN = 25;
    private static final int POWER = 26;
    private static final int BACK = 4;
    private static final int HOME = 3;
    private static final int PLAY = 85;
    private static final int MUTE = 91;

    private Tv tv;
    private TextView status;
    private EditText ip;
    private EditText pin;
    private LinearLayout pinRow;
    private Button voice;
    private boolean connected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
        }

        SharedPreferences prefs = getSharedPreferences("tcl", MODE_PRIVATE);
        tv = new Tv(getFilesDir(), new Tv.Listener() {
            @Override
            public void status(String text) {
                runOnUiThread(() -> status.setText(text));
            }

            @Override
            public void needPin() {
                runOnUiThread(() -> pinRow.setVisibility(View.VISIBLE));
            }

            @Override
            public void ready(boolean on) {
                runOnUiThread(() -> {
                    connected = on;
                    if (on) {
                        pinRow.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void hearing(boolean on) {
                runOnUiThread(() -> {
                    voice.setText(on ? "ouvindo..." : "segure para falar");
                    voice.setBackground(buttonBg(on ? 0xFFB42318 : 0xFF8B1E1E));
                });
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Controle TCL");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setText("toque em conectar");
        status.setTextColor(0xFFBBBBBB);
        status.setTextSize(14);
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);

        ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setText(prefs.getString("ip", "192.168.1.3"));
        ip.setHint("IP da TV");
        ip.setTextColor(Color.WHITE);
        ip.setHintTextColor(0xFF777777);
        styleField(ip);
        root.addView(ip, match());

        Button connect = button("Conectar", 0xFF2A2A2A);
        connect.setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            prefs.edit().putString("ip", host).apply();
            pinRow.setVisibility(View.GONE);
            status.setText("conectando...");
            tv.connect(host);
        });
        root.addView(connect, match());

        pinRow = new LinearLayout(this);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setVisibility(View.GONE);
        pin = new EditText(this);
        pin.setSingleLine(true);
        pin.setHint("código da TV");
        pin.setTextColor(Color.WHITE);
        pin.setHintTextColor(0xFF777777);
        pin.setFilters(new InputFilter[] {new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        pin.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        styleField(pin);
        pinRow.addView(pin, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button okPin = button("OK", 0xFF2A2A2A);
        okPin.setOnClickListener(v -> tv.submitPin(pin.getText().toString()));
        LinearLayout.LayoutParams pinBtn = new LinearLayout.LayoutParams(dp(72), dp(48));
        pinBtn.leftMargin = dp(8);
        pinRow.addView(okPin, pinBtn);
        root.addView(pinRow, match());

        root.addView(row(spacer(), key("^", UP), spacer()));
        root.addView(row(key("<", LEFT), key("OK", OK), key(">", RIGHT)));
        root.addView(row(spacer(), key("v", DOWN), spacer()));
        root.addView(row(key("Vol-", VOL_DOWN), key("Mudo", MUTE), key("Vol+", VOL_UP)));
        root.addView(row(key("Voltar", BACK), key("Home", HOME), key("Play", PLAY)));
        root.addView(key("Liga / desliga", POWER), match());

        voice = button("segure para falar", 0xFF8B1E1E);
        voice.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
                    status.setText("libere o microfone e segure de novo");
                    return true;
                }
                tv.voice(true);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                tv.voice(false);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams voiceLp = match();
        voiceLp.height = dp(84);
        voiceLp.topMargin = dp(12);
        root.addView(voice, voiceLp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        tv.shutdown();
        super.onDestroy();
    }

    private Button key(String label, int code) {
        Button button = button(label, 0xFF2A2A2A);
        button.setOnClickListener(v -> {
            if (!connected) {
                status.setText("conecte primeiro");
                return;
            }
            tv.key(code);
        });
        return button;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setBackground(buttonBg(color));
        return button;
    }

    private GradientDrawable buttonBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private void styleField(EditText field) {
        field.setBackground(buttonBg(0xFF1C1C1C));
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setGravity(Gravity.CENTER_VERTICAL);
    }

    private LinearLayout row(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View view : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(view, lp);
        }
        return row;
    }

    private View spacer() {
        View view = new View(this);
        return view;
    }

    private LinearLayout.LayoutParams match() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
