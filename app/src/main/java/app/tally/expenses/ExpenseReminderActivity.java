package app.tally.expenses;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen incoming-call-style expense reminder. */
public class ExpenseReminderActivity extends Activity {
    private boolean test;
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        test = getIntent().getBooleanExtra(ReminderReceiver.EXTRA_TEST, false);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(20,25,35)); getWindow().setNavigationBarColor(Color.rgb(20,25,35));
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28),dp(32),dp(28),dp(36)); root.setBackgroundColor(Color.rgb(20,25,35));
        TextView brand=text("TALLY",14,Color.LTGRAY,Typeface.BOLD); brand.setLetterSpacing(.18f); root.addView(brand);
        TextView title=text(getString(R.string.expense_call_title),29,Color.WHITE,Typeface.BOLD); title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-1,-2); tlp.topMargin=dp(34); root.addView(title,tlp);
        TextView sub=text(getString(R.string.expense_call_text),16,Color.LTGRAY,Typeface.NORMAL); sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2); slp.topMargin=dp(12); root.addView(sub,slp);
        TextView hint=text(getString(R.string.expense_call_hint),14,Color.rgb(180,190,205),Typeface.NORMAL); hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(-1,-2); hlp.topMargin=dp(10); root.addView(hint,hlp);
        LinearLayout actions=new LinearLayout(this); actions.setGravity(Gravity.CENTER); actions.setOrientation(0);
        LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,-2); alp.topMargin=dp(76); root.addView(actions,alp);
        actions.addView(action(getString(R.string.expense_call_decline),"×",Color.rgb(220,55,65),ReminderReceiver.EXPENSE_DECLINE),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(action(getString(R.string.expense_call_answer),"✓",Color.rgb(35,175,105),ReminderReceiver.EXPENSE_ANSWER),new LinearLayout.LayoutParams(0,-2,1));
        setContentView(root);
    }
    private TextView text(String value,float size,int color,int style){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,style);return v;}
    private LinearLayout action(String label,String symbol,int color,String response){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(1);wrap.setGravity(Gravity.CENTER);
        TextView circle=text(symbol,36,Color.WHITE,Typeface.BOLD);circle.setGravity(Gravity.CENTER);
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setShape(GradientDrawable.OVAL);circle.setBackground(bg);circle.setElevation(dp(6));
        circle.setOnClickListener(v -> respond(response));
        wrap.addView(circle,new LinearLayout.LayoutParams(dp(76),dp(76)));
        TextView caption=text(label,14,Color.WHITE,Typeface.BOLD);caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(14);wrap.addView(caption,cp);
        wrap.setOnClickListener(v -> respond(response));return wrap;
    }
    private void respond(String response) {
        if (test) ReminderReceiver.finishTest(this);
        else ReminderReceiver.handleExpenseResponse(this, response);
        finish();
    }
    @Override public void onBackPressed() {
        if (test) ReminderReceiver.finishTest(this);
        else ReminderReceiver.handleExpenseResponse(this, ReminderReceiver.EXPENSE_DECLINE);
        super.onBackPressed();
    }
}
