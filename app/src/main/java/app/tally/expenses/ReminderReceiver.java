package app.tally.expenses;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Shows Tally's reminders. The page sends its reminder settings with Android.setReminders(json):
 *   {daily:{on,h,m}, lastEntry:"yyyy-MM-dd", check:{on,h,m,text,checked}, duesAt:{h,m},
 *    dues:[{id,date,kind:"borrow"|"lend",who,amount,total?} | {id,monthly,since,kind,who,amount}]}
 * - daily: at h:m, "Nothing written today" if lastEntry is not today; re-armed for the next day.
 * - check: at h:m, "Do your balances still match?" with the balances in text, unless checked (the day the balances
 *   were last confirmed) is today. Tapping it opens the morning check on Home.
 * - dues: on the due date at duesAt (and each day while overdue), with "Record payment", "+1 day", "+1 week".
 *   amount is what is due by date; total (only when larger) is the whole tab, shown after the date.
 *   The +N buttons move the stored date and queue {type:"extend",id,days} for the page (Android.takeActions()).
 *   A loan with no payback day comes with monthly (the day of the month it was lent) and since (that date) instead of
 *   a date: once a month at duesAt, with "Record payment" and "Set a due date" (see {@link #monthlyDate}).
 * Every alarm is exact when the phone allows it (see {@link #arm}); each daily notice shows at most once a day.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String A_DAILY = "app.tally.expenses.DAILY";
    static final String A_CHECK = "app.tally.expenses.CHECK";
    static final String A_DUE = "app.tally.expenses.DUE";
    static final String A_EXTEND = "app.tally.expenses.EXTEND";
    static final String A_EXPENSE_ACTION = "app.tally.expenses.EXPENSE_ACTION";
    static final String EXTRA_TEST = "expense_test";
    private static final int TEST_EXPENSE_ID = 4;
    static final String EXPENSE_ANSWER = "answer";
    static final String EXPENSE_DECLINE = "decline";
    private static final int EXPENSE_ID = 3;
    private static final String PREFS = "tally_reminders";
    /** Channels: loan due days keep the original id (and whatever the user set for it). */
    static final String CH_DUES = "reminders", CH_NUDGE = "nudge", CH_CHECK = "check", CH_BACKUP = "backup", CH_EXPENSE_CALL = "expense_call_silent_v4";
    private static final int DAILY_ID = 1, CHECK_ID = 2;
    /** The config (plus exact-alarm ability) this process last armed: an unchanged sync re-arms nothing. In memory
     *  only, so the first sync after a force-stop or reboot (when Android has dropped the alarms) always re-arms. */
    private static String armedFor;

    @Override
    public void onReceive(Context ctx, Intent in) {
        String a = in.getAction();
        SharedPreferences p = prefs(ctx);
        String t = today();
        if (A_EXPENSE_ACTION.equals(a)) {
            String response = in.getStringExtra("response");
            TallyConnectionService.endCurrent(EXPENSE_ANSWER.equals(response));
            if (in.getBooleanExtra(EXTRA_TEST, false)) finishTest(ctx);
            else handleExpenseResponse(ctx, response);
        } else if (A_DAILY.equals(a)) {
            JSONObject dl = config(ctx).optJSONObject("daily");
            if (expenseReminderApplies(ctx, dl, t)) {
                showExpenseCall(ctx, false);
                scheduleNextExpenseCall(ctx, dl);
            }
        } else if (A_CHECK.equals(a)) {
            JSONObject ck = config(ctx).optJSONObject("check");
            if (ck != null && ck.optBoolean("on") && reached(ck, 8) && !t.equals(ck.optString("checked"))
                    && !t.equals(p.getString("shown:check", ""))) {
                show(ctx, CH_CHECK, CHECK_ID, ctx.getString(R.string.n_check_title), ck.optString("text"),
                        openApp(ctx, "check", CHECK_ID), null);
                p.edit().putString("shown:check", t).apply();
            }
        } else if (A_DUE.equals(a)) {
            JSONObject d = findDue(ctx, in.getStringExtra("id"));
            if (d != null) showDue(ctx, d, false);
        } else if (A_EXTEND.equals(a)) {
            extend(ctx, in.getStringExtra("id"), in.getIntExtra("days", 1));
        }
        schedule(ctx);
    }

    /**
     * Whether today's h:m has come. An alarm delivered late (inexact, or the clock changed) after midnight must not
     * say "Nothing written today" about a day that has just begun; it only re-arms.
     */
    private static boolean reached(JSONObject o, int defH) {
        Calendar c = dayAt(0, o.optInt("h", defH), o.optInt("m", 0));
        return System.currentTimeMillis() >= c.getTimeInMillis() - 60_000; // a minute's slack for clock drift
    }

    // ---------- called from the page (MainActivity bridge) ----------

    /** The page's reminder settings (after every save and on resume): stored and armed, unless nothing changed. */
    static synchronized void save(Context ctx, String json) {
        String cfg;
        try {
            cfg = new JSONObject(json).toString();
        } catch (JSONException e) {
            return;
        }
        SharedPreferences p = prefs(ctx);
        String key = cfg + canExact(ctx);
        if (key.equals(armedFor) && cfg.equals(p.getString("config", null))) return;
        JSONObject oldDaily = config(ctx).optJSONObject("daily");
        JSONObject newDaily = config(cfg).optJSONObject("daily");
        String oldDailyJson = oldDaily == null ? "" : oldDaily.toString();
        String newDailyJson = newDaily == null ? "" : newDaily.toString();
        SharedPreferences.Editor edit = p.edit().putString("config", cfg)
                .putString("lastEntry", config(cfg).optString("lastEntry", ""));
        if (!oldDailyJson.equals(newDailyJson)) edit.remove("expense:next").remove("expense:day");
        edit.apply();
        schedule(ctx);
        armedFor = key;
        refreshShown(ctx, config(cfg));
    }

    /** A widget-side expense is a real spending entry: silence today's repeat call and cancel its next alarm. */
    static void widgetExpenseSaved(Context ctx) {
        prefs(ctx).edit().putString("lastEntry", today()).remove("expense:next").remove("expense:day").apply();
        AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(am!=null)am.cancel(broadcast(ctx,A_DAILY,null,DAILY_ID));
        NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null)nm.cancel(EXPENSE_ID);
    }
    static synchronized String takeActions(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String q = p.getString("actions", "[]");
        p.edit().putString("actions", "[]").apply();
        return q;
    }

    // ---------- alarms ----------

    /**
     * Arms one alarm: exact when allowed (always before Android 12; on 12+ once "Alarms & reminders" is allowed),
     * otherwise inexact, which Android may deliver minutes (or, for a rarely used app, hours) late.
     */
    static void arm(AlarmManager am, long when, PendingIntent pi) {
        if (canExact(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                return;
            } catch (SecurityException ignored) { } // permission withdrawn in between: fall back
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private static boolean canExact(AlarmManager am) {
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    private static boolean canExact(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && canExact(am);
    }

    /** h:m:00 local time, {@code days} after today; out-of-range values are clamped, never rolled over. */
    static Calendar dayAt(int days, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days); // the day first: a time set on a day that skips it (DST) isn't carried over
        c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, h)));
        c.set(Calendar.MINUTE, Math.max(0, Math.min(59, m)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /** The next h:m from now: today if still ahead, else tomorrow. */
    static long nextAt(int h, int m) {
        long at = dayAt(0, h, m).getTimeInMillis();
        return at > System.currentTimeMillis() + 1000 ? at : dayAt(1, h, m).getTimeInMillis();
    }

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONObject cfg = config(ctx);
        long now = System.currentTimeMillis();

        // nothing until the page has said what's on (a widget can arm this before Tally was ever opened)
        String t = today();
        JSONObject ck = cfg.optJSONObject("check");
        scheduleExpenseCall(ctx, am, cfg.optJSONObject("daily"), t);
        // The former one-shot evening nudge is superseded by the repeat-until-confirmed expense call.
        armDaily(ctx, am, ck, A_CHECK, CHECK_ID, 8, "check", ck != null && !t.equals(ck.optString("checked")));

        JSONObject at = cfg.optJSONObject("duesAt");
        int dh = at == null ? 9 : at.optInt("h", 9), dm = at == null ? 0 : at.optInt("m", 0); // dayAt clamps them
        SharedPreferences p = prefs(ctx);
        try {
            JSONArray armed = new JSONArray(p.getString("armed", "[]"));
            for (int i = 0; i < armed.length(); i++) am.cancel(broadcast(ctx, A_DUE, armed.getString(i), code(ctx, armed.getString(i))));
        } catch (JSONException ignored) { }
        JSONArray nowArmed = new JSONArray();
        Set<String> keep = new HashSet<>(Arrays.asList("shown:nudge", "shown:check"));
        JSONArray dues = cfg.optJSONArray("dues");
        for (int i = 0; dues != null && i < dues.length(); i++) {
            JSONObject d = dues.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id"), date = dueDate(p, d, t);
            Calendar c = parse(date);
            if (id.isEmpty() || c == null) continue;
            long when;
            if (date.compareTo(t) > 0) {
                c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, dh)));
                c.set(Calendar.MINUTE, Math.max(0, Math.min(59, dm)));
                when = c.getTimeInMillis();
            } else if (!t.equals(p.getString("shown:" + id, ""))) {
                // due today or overdue and not shown yet today: at the set time, or in a minute if that has passed
                when = Math.max(dayAt(0, dh, dm).getTimeInMillis(), now + 60_000);
            } else {
                // already reminded today: again tomorrow at the set time while it stays open
                when = dayAt(1, dh, dm).getTimeInMillis();
            }
            arm(am, when, broadcast(ctx, A_DUE, id, code(ctx, id)));
            nowArmed.put(id);
            keep.add("shown:" + id);
        }
        SharedPreferences.Editor e = p.edit().putString("armed", nowArmed.toString());
        // "shown today" marks of loans no longer reminded about (cleared, deleted, due date removed) are dropped
        for (String k : p.getAll().keySet()) {
            if (k.startsWith("shown:") && !keep.contains(k)) e.remove(k);
        }
        e.apply();
    }


    private static boolean expenseReminderApplies(Context ctx, JSONObject daily, String day) {
        if (daily == null || !daily.optBoolean("on")) return false;
        SharedPreferences p = prefs(ctx);
        if (day.equals(p.getString("lastEntry", "")) || day.equals(p.getString("expense:answered", ""))) return false;
        int startH=daily.optInt("h",19), startM=daily.optInt("m",0);
        long now=System.currentTimeMillis();
        return now>=dayAt(0,startH,startM).getTimeInMillis() && now<=expenseEnd(daily).getTimeInMillis();
    }
    private static int expenseInterval(JSONObject daily) { return Math.max(5,Math.min(240,daily==null?30:daily.optInt("interval",30))); }
    private static Calendar expenseEnd(JSONObject daily) {
        int[] hm=parseHm(daily==null?"22:00":daily.optString("end","22:00"),22,0);
        int sh=daily==null?19:daily.optInt("h",19), sm=daily==null?0:daily.optInt("m",0);
        if(hm[0]*60+hm[1]<=sh*60+sm) hm=new int[]{23,59};
        return dayAt(0,hm[0],hm[1]);
    }
    private static int[] parseHm(String value,int dh,int dm) {
        try { String[] p=value.split(":"); return new int[]{Math.max(0,Math.min(23,Integer.parseInt(p[0]))),Math.max(0,Math.min(59,Integer.parseInt(p[1])))}; }
        catch(Exception e){return new int[]{dh,dm};}
    }
    private static void scheduleExpenseCall(Context ctx,AlarmManager am,JSONObject daily,String today) {
        PendingIntent pi=broadcast(ctx,A_DAILY,null,DAILY_ID); SharedPreferences p=prefs(ctx);
        if(daily==null||!daily.optBoolean("on")||today.equals(p.getString("lastEntry",""))||today.equals(p.getString("expense:answered",""))) {
            am.cancel(pi); NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm!=null)nm.cancel(EXPENSE_ID); p.edit().remove("expense:next").remove("expense:day").apply(); return;
        }
        int sh=daily.optInt("h",19),sm=daily.optInt("m",0);
        long start=dayAt(0,sh,sm).getTimeInMillis(),end=expenseEnd(daily).getTimeInMillis(),now=System.currentTimeMillis();
        String armedDay=p.getString("expense:day",""); long next=p.getLong("expense:next",0);
        if(!today.equals(armedDay)){next=Math.max(start,now+1000);if(next>end)next=dayAt(1,sh,sm).getTimeInMillis();}
        else if(next<=0||next<start||next>end)next=dayAt(1,sh,sm).getTimeInMillis();
        if(next>now){arm(am,next,pi);p.edit().putLong("expense:next",next).putString("expense:day",today).apply();}
        else {am.cancel(pi);p.edit().remove("expense:next").remove("expense:day").apply();}
    }
    private static void scheduleNextExpenseCall(Context ctx, JSONObject daily) {
        if (daily == null || !daily.optBoolean("on") || !expenseReminderApplies(ctx, daily, today())) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(broadcast(ctx, A_DAILY, null, DAILY_ID));
            prefs(ctx).edit().remove("expense:next").remove("expense:day").apply();
            return;
        }
        long next=System.currentTimeMillis()+expenseInterval(daily)*60_000L;
        if(next<=expenseEnd(daily).getTimeInMillis()){
            AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
            if(am!=null){arm(am,next,broadcast(ctx,A_DAILY,null,DAILY_ID));prefs(ctx).edit().putLong("expense:next",next).putString("expense:day",today()).apply();}
        } else prefs(ctx).edit().putLong("expense:next",0).putString("expense:day",today()).apply();
    }
    /** User-initiated test uses the exact same notification and CallStyle path as the scheduled reminder. */
    static void testExpenseCall(Context ctx) {
        showExpenseCall(ctx, true);
    }

    static void finishTest(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(TEST_EXPENSE_ID);
    }
    /** Direct broadcast actions, matching the Salah Tracker call reminder pattern. */
    private static PendingIntent expenseAction(Context ctx, String response, boolean test, int requestCode) {
        Intent action = new Intent(ctx, ReminderReceiver.class).setAction(A_EXPENSE_ACTION)
                .putExtra("response", response).putExtra(EXTRA_TEST, test);
        return PendingIntent.getBroadcast(ctx, requestCode, action,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showExpenseCall(Context ctx, boolean test) {
        // Register this as an Android Telecom call as well as showing our own incoming-call UI.
        // CallStyle alone is only a notification and is not visible as a real call to Telecom/Bluetooth devices.
        TallyConnectionService.placeIncomingCall(ctx, test);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(expenseCallChannel(ctx));
        }

        PendingIntent answer = expenseAction(ctx, EXPENSE_ANSWER, test, test ? 306 : 304);
        PendingIntent decline = expenseAction(ctx, EXPENSE_DECLINE, test, test ? 305 : 303);
        PendingIntent openApp = PendingIntent.getActivity(ctx, test ? 302 : 301,
                MainActivity.openIntent(ctx, null),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent callScreen = new Intent(ctx, ExpenseReminderActivity.class)
                .putExtra(EXTRA_TEST, test)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreen = PendingIntent.getActivity(ctx, test ? 307 : 308, callScreen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH_EXPENSE_CALL) : new Notification.Builder(ctx);
        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ctx.getString(R.string.n_nudge_title))
                .setContentText(ctx.getString(R.string.n_nudge_text))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openApp)
                // The full-screen intent must be the incoming-call screen, not the ordinary Tally home page.
                .setFullScreenIntent(fullScreen, true);
        if (Build.VERSION.SDK_INT >= 31) {
            Person caller = new Person.Builder().setName("Tally").setImportant(true).build();
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(false));
        } else {
            builder.setPriority(Notification.PRIORITY_MAX)
                    .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.expense_call_decline), decline).build())
                    .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.expense_call_answer), answer).build());
        }
        try {
            // Android renders CallStyle in the notification shade; the full-screen intent opens Tally's
            // two-action incoming-call screen when Android permits full-screen notifications.
            nm.notify(test ? TEST_EXPENSE_ID : EXPENSE_ID, builder.build());
        } catch (SecurityException ignored) { }
    }
    static void handleExpenseResponse(Context ctx,String response) {
        SharedPreferences p=prefs(ctx);NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.cancel(EXPENSE_ID);
        if(EXPENSE_ANSWER.equals(response)){
            p.edit().putString("expense:answered",today()).remove("expense:next").remove("expense:day").apply();
            AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);if(am!=null)am.cancel(broadcast(ctx,A_DAILY,null,DAILY_ID));
        } else if(EXPENSE_DECLINE.equals(response)) scheduleNextExpenseCall(ctx,config(ctx).optJSONObject("daily"));
    }

    /**
     * A once-a-day notice (the nudge, the balance check): armed at its h:m while on, cancelled when off. Its time
     * passing today without the alarm (the phone was off, or re-arming replaced an inexact one still waiting) brings
     * it a minute later, while it still applies (applies: nothing written today / balances not confirmed today) and
     * hasn't shown today; switching it on after its time doesn't (only a time it was armed for counts).
     */
    private static void armDaily(Context ctx, AlarmManager am, JSONObject o, String action, int req, int defH,
                                 String kind, boolean applies) {
        PendingIntent pi = broadcast(ctx, action, null, req);
        SharedPreferences p = prefs(ctx);
        if (o == null || !o.optBoolean("on")) {
            am.cancel(pi);
            p.edit().remove("due:" + kind).remove("late:" + kind).apply();
            return;
        }
        long now = System.currentTimeMillis(), was = p.getLong("due:" + kind, 0);
        String t = today();
        boolean owed = was > 0 && t.equals(fmt().format(new Date(was))) && applies
                && !t.equals(p.getString("shown:" + kind, ""));
        boolean late = owed && (was <= now || p.getBoolean("late:" + kind, false)); // missed, or its catch-up waiting
        long at = !late ? nextAt(o.optInt("h", defH), o.optInt("m", 0)) : was <= now ? now + 60_000 : was;
        arm(am, at, pi);
        p.edit().putLong("due:" + kind, at).putBoolean("late:" + kind, late).apply();
    }

    // ---------- notifications ----------

    /** quiet: re-writes one already on screen (new amount, total or date), without a sound or a "shown" mark. */
    private static void showDue(Context ctx, JSONObject d, boolean quiet) {
        String id = d.optString("id"), date = d.optString("date"), who = d.optString("who"), amount = d.optString("amount");
        String total = d.optString("total");
        if (who.isEmpty()) who = ctx.getString(R.string.n_someone); // a loan without a name
        boolean lend = "lend".equals(d.optString("kind")), monthly = d.has("monthly");
        boolean dueToday = date.equals(today());
        int base = code(ctx, id);
        String title, text;
        Notification.Action[] actions;
        if (monthly) {
            title = ctx.getString(lend ? R.string.n_still_owes_you : R.string.n_you_still_owe, who, amount);
            text = ctx.getString(lend ? R.string.n_lent_on : R.string.n_borrowed_on, pretty(ctx, d.optString("since")));
            actions = new Notification.Action[] {
                    action(ctx, ctx.getString(R.string.n_record), openApp(ctx, "loan:" + id + ":pay", base + 1)),
                    action(ctx, ctx.getString(R.string.n_set_due), openApp(ctx, "loan:" + id + ":due", base + 2)),
            };
        } else {
            title = ctx.getString(lend ? R.string.n_owes_you : R.string.n_you_owe, who, amount);
            text = (lend
                    ? (dueToday ? ctx.getString(R.string.n_payback_today) : ctx.getString(R.string.n_payback_was, pretty(ctx, date)))
                    : (dueToday ? ctx.getString(R.string.n_due_today) : ctx.getString(R.string.n_due_was, pretty(ctx, date))))
                    + (total.isEmpty() ? "" : " · " + ctx.getString(R.string.n_in_all, total));
            actions = new Notification.Action[] {
                    action(ctx, ctx.getString(R.string.n_record), openApp(ctx, "loan:" + id + ":pay", base + 1)),
                    action(ctx, ctx.getString(R.string.n_plus_day), extendIntent(ctx, id, 1, base + 2)),
                    action(ctx, ctx.getString(R.string.n_plus_week), extendIntent(ctx, id, 7, base + 3)),
            };
        }
        show(ctx, CH_DUES, base, title, text, openApp(ctx, "loan:" + id, base), actions, quiet);
        if (!quiet) prefs(ctx).edit().putString("shown:" + id, today()).apply();
    }

    /**
     * Notices still on screen follow the page's latest sync: re-written quietly while they still apply (a spending
     * changes the balances in the check, a payment the amount due), removed once they don't (something written today,
     * "All match", the check switched off, a loan paid off or no longer due). One the user dismissed never comes back,
     * and the "shown today" marks stay as they are.
     */
    private static void refreshShown(Context ctx, JSONObject cfg) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Set<Integer> up = new HashSet<>();
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) up.add(s.getId());
        } catch (RuntimeException e) {
            return; // some ROMs refuse this; the notices then just stay as they were
        }
        String t = today();
        // The old one-shot evening nudge was replaced by the full-screen repeat-until-confirmed call.
        if (up.contains(DAILY_ID)) nm.cancel(DAILY_ID);
        if (up.contains(CHECK_ID)) {
            JSONObject ck = cfg.optJSONObject("check");
            if (ck == null || !ck.optBoolean("on") || t.equals(ck.optString("checked"))) nm.cancel(CHECK_ID);
            else show(ctx, CH_CHECK, CHECK_ID, ctx.getString(R.string.n_check_title), ck.optString("text"),
                    openApp(ctx, "check", CHECK_ID), null, true);
        }
        Set<Integer> due = new HashSet<>();
        JSONArray dues = cfg.optJSONArray("dues");
        for (int i = 0; dues != null && i < dues.length(); i++) {
            JSONObject d = dues.optJSONObject(i);
            String id = d == null ? "" : d.optString("id");
            // not due (any more); a monthly one still on screen applies until the loan is paid or gets a payback day
            if (id.isEmpty() || (!d.has("monthly") && d.optString("date").compareTo(t) > 0)) continue;
            due.add(code(ctx, id));
            if (up.contains(code(ctx, id))) showDue(ctx, d, true);
        }
        for (int id : up) {
            if (id >= 1000 && !due.contains(id)) nm.cancel(id); // loan notices (code() ≥ 1000) no longer due
        }
    }

    /** The notices on screen written again in the app's language (Lang.syncIfChanged), quietly. */
    static void refreshShown(Context ctx) {
        refreshShown(ctx, config(ctx));
    }

    /** The expense-call channel must be configured with ringtone sound on its first creation. */
    private static NotificationChannel expenseCallChannel(Context ctx) {
        NotificationChannel channel = new NotificationChannel(CH_EXPENSE_CALL,
                ctx.getString(R.string.ch_expense_call), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Persistent call-style expense reminders");
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        return channel;
    }

    /**
     * Creates (or renames, in the app's language) Tally's channels, so each kind can be silenced on its own in
     * Android's settings.
     */
    static void channels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int imp = NotificationManager.IMPORTANCE_DEFAULT;
        nm.createNotificationChannels(Arrays.asList(
                new NotificationChannel(CH_NUDGE, ctx.getString(R.string.ch_nudge), imp),
                expenseCallChannel(ctx),
                new NotificationChannel(CH_CHECK, ctx.getString(R.string.ch_check), imp),
                new NotificationChannel(CH_DUES, ctx.getString(R.string.ch_dues), imp),
                new NotificationChannel(CH_BACKUP, ctx.getString(R.string.ch_backup), imp)));
    }

    static void show(Context ctx, String channel, int nid, String title, String text, PendingIntent tap, Notification.Action[] actions) {
        show(ctx, channel, nid, title, text, tap, actions, false);
    }

    /** quiet: replaces the notice with this id in place, without sound or vibration (setOnlyAlertOnce). */
    static void show(Context ctx, String channel, int nid, String title, String text, PendingIntent tap,
                     Notification.Action[] actions, boolean quiet) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        channels(ctx);
        Notification.Builder b = builder(ctx, channel);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(tap).setOnlyAlertOnce(quiet)
                // on a lock screen that hides sensitive content: which kind of reminder, never names or amounts
                .setPublicVersion(builder(ctx, channel).setSmallIcon(R.drawable.ic_notif).setContentTitle("Tally")
                        .setContentText(publicText(ctx, channel)).build());
        if (actions != null) for (Notification.Action a : actions) b.addAction(a);
        try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { } // notifications not allowed
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context ctx, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, channel) : new Notification.Builder(ctx);
    }

    private static String publicText(Context ctx, String channel) {
        switch (channel) {
            case CH_CHECK: return ctx.getString(R.string.pub_check);
            case CH_DUES: return ctx.getString(R.string.pub_dues);
            case CH_BACKUP: return ctx.getString(R.string.pub_backup);
            default: return ctx.getString(R.string.pub_nudge);
        }
    }

    private static Notification.Action action(Context ctx, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), label, pi).build();
    }

    /** Synchronized with {@link #takeActions}: both rewrite the queued "actions" list. */
    private static synchronized void extend(Context ctx, String id, int days) {
        if (id == null) return;
        SharedPreferences p = prefs(ctx);
        JSONObject cfg = config(ctx);
        JSONArray dues = cfg.optJSONArray("dues");
        try {
            for (int i = 0; dues != null && i < dues.length(); i++) {
                JSONObject d = dues.getJSONObject(i);
                if (!id.equals(d.optString("id"))) continue;
                String due = d.optString("date"), t = today();
                Calendar c = parse(due.compareTo(t) > 0 ? due : t); // same rule as the page: from the later of due and today
                if (c == null) break;
                c.add(Calendar.DAY_OF_MONTH, days);
                d.put("date", fmt().format(c.getTime()));
            }
            JSONArray q = new JSONArray(p.getString("actions", "[]"));
            // the day it was tapped: the page counts from it, not from when Tally is next opened
            q.put(new JSONObject().put("type", "extend").put("id", id).put("days", days).put("on", today()));
            p.edit().putString("config", cfg.toString()).putString("actions", q.toString()).remove("shown:" + id).apply();
        } catch (JSONException ignored) { }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(code(ctx, id));
    }

    // ---------- helpers ----------

    static PendingIntent openApp(Context ctx, String open, int req) {
        return PendingIntent.getActivity(ctx, req, MainActivity.openIntent(ctx, open),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent extendIntent(Context ctx, String id, int days, int req) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(A_EXTEND).putExtra("id", id).putExtra("days", days);
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent broadcast(Context ctx, String action, String id, int req) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(action);
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static JSONObject findDue(Context ctx, String id) {
        JSONArray dues = config(ctx).optJSONArray("dues");
        for (int i = 0; id != null && dues != null && i < dues.length(); i++) {
            JSONObject d = dues.optJSONObject(i);
            if (d != null && id.equals(d.optString("id"))) return d;
        }
        return null;
    }

    /**
     * Four request codes per loan (notification/tap, Record payment, +1 day, +1 week), from a slot kept per loan id,
     * so two loans never share one: a hash alone can collide, and the second loan's reminder would replace the first's.
     * A loan's first slot is still its hash's, so alarms armed before this keep their codes.
     */
    private static synchronized int code(Context ctx, String id) {
        SharedPreferences p = prefs(ctx);
        JSONObject m;
        try { m = new JSONObject(p.getString("codes", "{}")); } catch (JSONException e) { m = new JSONObject(); }
        if (m.has(id)) return m.optInt(id);
        Set<Integer> used = new HashSet<>();
        for (Iterator<String> k = m.keys(); k.hasNext(); ) used.add(m.optInt(k.next()));
        int slot = id.hashCode() & 0xffff, c;
        while (used.contains(c = 1000 + slot * 4)) slot = (slot + 1) & 0xffff;
        try { m.put(id, c); } catch (JSONException ignored) { }
        p.edit().putString("codes", m.toString()).apply();
        return c;
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONObject config(Context ctx) {
        return config(prefs(ctx).getString("config", "{}"));
    }

    private static JSONObject config(String json) {
        try { return new JSONObject(json); }
        catch (JSONException e) { return new JSONObject(); }
    }

    private static SimpleDateFormat fmt() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US); }

    static String today() { return fmt().format(new Date()); }

    /** When a due reminder is next due: its date, or for a loan with no payback day, its next monthly day. */
    private static String dueDate(SharedPreferences p, JSONObject d, String t) {
        if (!d.has("monthly")) return d.optString("date");
        return monthlyDate(d.optInt("monthly"), d.optString("since"), t, t.equals(p.getString("shown:" + d.optString("id"), "")));
    }

    /**
     * A loan with no payback day is reminded once a month, on the day of the month it was lent ({@code day}; a month
     * without that day, such as the 31st in April, uses its last day), from a month after {@code since}: today if
     * that is such a day and it hasn't shown yet, else the next one. Worked out here, so it keeps coming every month
     * while Tally stays closed.
     */
    private static String monthlyDate(int day, String since, String t, boolean shownToday) {
        Calendar now = parse(t);
        if (day < 1 || day > 31 || now == null) return "";
        for (int k = 0; k < 24; k++) {
            Calendar o = (Calendar) now.clone();
            o.set(Calendar.DAY_OF_MONTH, 1);
            o.add(Calendar.MONTH, k);
            o.set(Calendar.DAY_OF_MONTH, Math.min(day, o.getActualMaximum(Calendar.DAY_OF_MONTH)));
            String s = fmt().format(o.getTime());
            if (s.compareTo(t) < 0 || (s.equals(t) && shownToday) || s.compareTo(since) <= 0) continue;
            return s;
        }
        return "";
    }

    private static Calendar parse(String s) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(fmt().parse(s));
            return c;
        } catch (ParseException | NullPointerException e) {
            return null;
        }
    }

    /** "Mon, Jun 15" / "Mon 15 Jun" / "6月15日(月)": weekday, day and month in the phone's own order */
    private static String pretty(Context ctx, String s) {
        Calendar c = parse(s);
        if (c == null) return s;
        Locale loc = Lang.fmt(ctx);
        return new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEdMMM"), loc)
                .format(c.getTime());
    }
}
