package app.tally.expenses;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Tally-styled quick entry sheet launched from the home-screen widget. */
public class QuickAddActivity extends Activity {
    static final String EXTRA_TYPE = "widget_entry_type";

    private static final class Choice {
        final String id, name, currency;
        Choice(String id, String name, String currency) {
            this.id = id; this.name = name; this.currency = currency;
        }
        @Override public String toString() { return name; }
    }

    private final ArrayList<Choice> accounts = new ArrayList<>(), categories = new ArrayList<>();
    private String type;
    private EditText amount, note, received;
    private Spinner account, category, from, to;
    private Button save;
    private int on, muted, surface, field, buttonBg, buttonFg, accent;

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (!"income".equals(type) && !"transfer".equals(type)) type = "expense";

        on = getColor(R.color.w_on);
        muted = getColor(R.color.w_muted);
        surface = getColor(R.color.surface);
        field = getColor(R.color.w_bg);
        buttonBg = getColor(R.color.w_btn_bg);
        buttonFg = getColor(R.color.w_btn_fg);
        accent = getColor("income".equals(type) ? R.color.w_got :
                "transfer".equals(type) ? R.color.w_xfer : R.color.w_spent);

        JSONObject notebook = readNotebook();
        if (notebook == null || !loadChoices(notebook)) {
            TextView message = new TextView(this);
            message.setText("Open Tally once and create an account before using widget quick entry.");
            message.setTextColor(on);
            message.setPadding(dp(24), dp(24), dp(24), dp(24));
            setContentView(message);
            return;
        }

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = w.getAttributes();
            p.width = WindowManager.LayoutParams.MATCH_PARENT;
            p.height = WindowManager.LayoutParams.WRAP_CONTENT;
            p.gravity = Gravity.BOTTOM;
            p.dimAmount = .38f;
            w.setAttributes(p);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        setFinishOnTouchOutside(true);
        setContentView(form());
    }

    private JSONObject readNotebook() {
        String raw = SavedCopy.read(this);
        if (raw == null) return null;
        int nl = raw.indexOf('\\n');
        if (nl < 1) return null;
        try { return new JSONObject(raw.substring(nl + 1)); }
        catch (JSONException e) { return null; }
    }

    private boolean loadChoices(JSONObject data) {
        JSONArray aa = data.optJSONArray("accounts");
        for (int i = 0; aa != null && i < aa.length(); i++) {
            JSONObject a = aa.optJSONObject(i);
            if (a != null && !a.optBoolean("archived") && !a.optString("id").isEmpty())
                accounts.add(new Choice(a.optString("id"), a.optString("name"), a.optString("currency")));
        }
        if (accounts.isEmpty()) return false;
        if (!"transfer".equals(type)) {
            String kind = "income".equals(type) ? "in" : "out";
            JSONArray cc = data.optJSONArray("cats");
            for (int i = 0; cc != null && i < cc.length(); i++) {
                JSONObject c = cc.optJSONObject(i);
                if (c != null && kind.equals(c.optString("kind")))
                    categories.add(new Choice(c.optString("id"), c.optString("name"), ""));
            }
            if (categories.isEmpty()) return false;
        }
        return true;
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout.LayoutParams params(int width, int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void sectionLabel(LinearLayout parent, String value) {
        TextView t = text(value, 12, muted, true);
        t.setLetterSpacing(.035f);
        t.setPadding(dp(2), 0, 0, dp(7));
        parent.addView(t, params(-1, -2, 7));
    }

    private EditText edit(String hint, boolean large) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(large ? 27 : 16);
        e.setTextColor(on);
        e.setHintTextColor(muted);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(dp(16), dp(large ? 14 : 12), dp(16), dp(large ? 14 : 12));
        e.setBackground(shape(field, 16));
        if (large) e.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return e;
    }

    private Spinner spinner(ArrayList<Choice> values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<Choice> adapter = new ArrayAdapter<Choice>(this, android.R.layout.simple_spinner_item, values) {
            private TextView row(View convert, android.view.ViewGroup parent) {
                TextView t = convert instanceof TextView ? (TextView) convert : new TextView(QuickAddActivity.this);
                t.setTextColor(on);
                t.setTextSize(15);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(15), dp(11), dp(15), dp(11));
                return t;
            }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView t = row(convertView, parent);
                t.setText(getItem(position).name);
                return t;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView t = row(convertView, parent);
                t.setText(getItem(position).name);
                t.setBackgroundColor(field);
                return t;
            }
        };
        s.setAdapter(adapter);
        s.setBackground(shape(field, 15));
        s.setPadding(dp(5), dp(2), dp(10), dp(2));
        s.setMinimumHeight(dp(52));
        return s;
    }

    private View form() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout sheet = column();
        sheet.setPadding(dp(22), dp(12), dp(22), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadii(new float[]{dp(28),dp(28),dp(28),dp(28),dp(0),dp(0),dp(0),dp(0)});
        sheet.setBackground(bg);
        scroll.addView(sheet);

        View handle = new View(this);
        handle.setBackground(shape(getColor(R.color.w_muted), 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(18);
        sheet.addView(handle, hp);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams headerP = params(-1, -2, 20);
        sheet.addView(header, headerP);

        TextView symbol = text("expense".equals(type) ? "−" : "income".equals(type) ? "+" : "↔", 25, accent, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(shape(field, 16));
        header.addView(symbol, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("expense".equals(type) ? getString(R.string.qa_out) :
                "income".equals(type) ? getString(R.string.qa_in) : getString(R.string.qa_tr), 19, on, true));
        titles.addView(text("Quick entry · Tally", 12, muted, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        TextView close = text("×", 28, muted, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(44)));

        sectionLabel(sheet, "AMOUNT");
        amount = edit("0.00", true);
        sheet.addView(amount, params(-1, -2, 16));

        if ("transfer".equals(type)) {
            sectionLabel(sheet, "FROM ACCOUNT");
            from = spinner(accounts);
            sheet.addView(from, params(-1, -2, 12));
            sectionLabel(sheet, "TO ACCOUNT");
            to = spinner(accounts);
            sheet.addView(to, params(-1, -2, 12));
            sectionLabel(sheet, "AMOUNT RECEIVED");
            received = edit("Only for different currencies", false);
            sheet.addView(received, params(-1, -2, 12));
            AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updateReceived(); }
                public void onNothingSelected(AdapterView<?> p) { updateReceived(); }
            };
            from.setOnItemSelectedListener(listener);
            to.setOnItemSelectedListener(listener);
        } else {
            sectionLabel(sheet, "CATEGORY");
            category = spinner(categories);
            sheet.addView(category, params(-1, -2, 12));
            sectionLabel(sheet, "ACCOUNT");
            account = spinner(accounts);
            sheet.addView(account, params(-1, -2, 12));
        }

        sectionLabel(sheet, "NOTE");
        note = new EditText(this);
        note.setSingleLine(true);
        note.setTextSize(15);
        note.setTextColor(on);
        note.setHintTextColor(muted);
        note.setHint("Add a note (optional)");
        note.setPadding(dp(16), dp(12), dp(16), dp(12));
        note.setBackground(shape(field, 15));
        sheet.addView(note, params(-1, -2, 18));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setTextColor(muted);
        cancel.setBackground(shape(field, 18));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(0, dp(52), 1.6f);
        saveP.leftMargin = dp(10);
        save = new Button(this);
        save.setText("Save entry");
        save.setTextColor(buttonFg);
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setBackground(shape(buttonBg, 18));
        actions.addView(save, saveP);
        sheet.addView(actions);

        cancel.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> saveEntry());
        return scroll;
    }

    private void updateReceived() {
        if (received == null || from == null || to == null) return;
        Choice a = (Choice) from.getSelectedItem(), b = (Choice) to.getSelectedItem();
        boolean different = a != null && b != null && !a.currency.equals(b.currency);
        received.setVisibility(different ? View.VISIBLE : View.GONE);
    }

    private void saveEntry() {
        double value;
        try { value = Double.parseDouble(amount.getText().toString().trim().replace(",", "")); }
        catch (Exception e) { value = 0; }
        if (!(value > 0) || !Double.isFinite(value)) {
            amount.setError("Enter a valid amount");
            return;
        }

        Choice a, c = null, b = null;
        Double got = null;
        if ("transfer".equals(type)) {
            a = (Choice) from.getSelectedItem();
            b = (Choice) to.getSelectedItem();
            if (a == null || b == null || a.id.equals(b.id)) {
                Toast.makeText(this, "Choose two different accounts", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!a.currency.equals(b.currency)) {
                try { got = Double.parseDouble(received.getText().toString().trim().replace(",", "")); }
                catch (Exception e) { got = null; }
                if (got == null || !(got > 0) || !Double.isFinite(got)) {
                    received.setError("Enter amount received");
                    return;
                }
            }
        } else {
            a = (Choice) account.getSelectedItem();
            c = (Choice) category.getSelectedItem();
        }

        save.setEnabled(false);
        final Choice fa = a, fc = c, fb = b;
        final Double fg = got;
        final double amountValue = value;
        final String memo = note.getText().toString().trim();
        new Thread(() -> {
            boolean ok = SavedCopy.addWidgetEntry(this, type, amountValue, fa.id,
                    fc == null ? null : fc.id, fb == null ? null : fb.id, fg, memo);
            runOnUiThread(() -> {
                if (!ok) {
                    save.setEnabled(true);
                    Toast.makeText(this, "Could not save. Open Tally and try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                if ("expense".equals(type)) ReminderReceiver.widgetExpenseSaved(this);
                TallyWidget.refresh(this);
                MainActivity.deliver("window.tallyWidgetRefresh&&window.tallyWidgetRefresh()");
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                finish();
            });
        }, "tally-widget-entry").start();
    }
}
