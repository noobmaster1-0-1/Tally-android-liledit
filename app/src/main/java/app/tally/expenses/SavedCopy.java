package app.tally.expenses;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The shell's own copy of the page's saved data: files/saved.json, the save number on its first line and the JSON
 * after it. WebView writes localStorage to disk a few seconds after a change, so a save made just before Android ends
 * Tally (swiped away from Recents) could be missing from it. This copy is on disk moments after each save, and the
 * page's start-up (state.js loadState) takes whichever copy has the higher save number.
 */
final class SavedCopy {
    private static final Object LOCK = new Object();
    private static String next; // the newest save not written yet
    private static int nextSeq;
    private static boolean writing;
    /** The save numbers last written and last refused (a full disk, out of memory), for {@link #status}. */
    private static volatile int okSeq, failSeq;

    private SavedCopy() { }

    /** Returns at once; a background thread writes it. Saves that arrive meanwhile are merged: only the newest is written. */
    static void keep(Context ctx, int seq, String json) {
        if (json == null) return;
        final File f = file(ctx);
        synchronized (LOCK) {
            next = json;
            nextSeq = seq;
            if (writing) return;
            writing = true;
        }
        try {
            new Thread(() -> {
                while (true) {
                    String j;
                    int s;
                    synchronized (LOCK) {
                        if (next == null) {
                            writing = false;
                            LOCK.notifyAll();
                            return;
                        }
                        j = next;
                        s = nextSeq;
                        next = null;
                    }
                    write(f, s, j);
                }
            }, "tally-saved-copy").start();
        } catch (Throwable e) { // no thread to be had (out of memory): the next save tries again
            synchronized (LOCK) {
                writing = false;
                LOCK.notifyAll();
            }
            failSeq = seq;
        }
    }

    /**
     * Whether save {@code seq} is on disk: 1 written (or a newer one that has it), -1 refused, 0 not yet. The page asks
     * when localStorage refused a save (state.js save()), so a save in neither copy is said.
     */
    static int status(int seq) {
        return okSeq >= seq ? 1 : failSeq >= seq ? -1 : 0;
    }

    /** The copy, once any write under way has finished (2 s at most); null when there is none. */
    static String read(Context ctx) {
        synchronized (LOCK) {
            long until = SystemClock.uptimeMillis() + 2000;
            while (writing && SystemClock.uptimeMillis() < until) {
                try {
                    LOCK.wait(200);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return BackupReceiver.read(file(ctx));
    }

    /** Flushed to the disk before it replaces the old copy, so an interrupted write leaves the old one whole. */
    private static void write(File f, int seq, String json) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write((seq + "\n").getBytes(StandardCharsets.UTF_8));
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        } catch (Throwable e) { // a full disk, or out of memory: the old copy stays, its lower number loses to localStorage
            tmp.delete();
            failSeq = seq;
            return;
        }
        if (tmp.renameTo(f)) okSeq = seq;
        else {
            tmp.delete();
            failSeq = seq;
        }
    }

    /** Atomic native quick-entry write to the same saved notebook used by the WebView. */
    static boolean addWidgetEntry(Context ctx, String type, double amount, String account, String category,
                                  String to, Double toAmount, String note) {
        synchronized (LOCK) {
            long until=SystemClock.uptimeMillis()+2500;
            while(writing&&SystemClock.uptimeMillis()<until){
                try{LOCK.wait(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            }
            if(writing)return false;
            String raw=BackupReceiver.read(file(ctx));if(raw==null)return false;
            int nl=raw.indexOf('\n');if(nl<1)return false;
            try{
                int seq=Integer.parseInt(raw.substring(0,nl).trim())+1;
                JSONObject state=new JSONObject(raw.substring(nl+1));
                JSONArray txns=state.optJSONArray("txns");if(txns==null)txns=new JSONArray();
                JSONObject tx=new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-",""))
                    .put("ts",System.currentTimeMillis()).put("type","transfer".equals(type)?"transfer":"income".equals(type)?"income":"expense")
                    .put("amount",amount).put("account",account)
                    .put("date",new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(new java.util.Date()))
                    .put("note",note==null?"":note);
                if("transfer".equals(type)){tx.put("to",to);if(toAmount!=null)tx.put("toAmount",toAmount);}
                else tx.put("cat",category);
                txns.put(tx);state.put("txns",txns);
                JSONObject settings=state.optJSONObject("settings");if(settings==null)settings=new JSONObject();
                settings.put("income".equals(type)?"lastAccIn":"lastAcc",account);state.put("settings",settings);
                write(file(ctx),seq,state.toString());return okSeq>=seq;
            }catch(Exception e){return false;}
        }
    }
    private static File file(Context ctx) { return new File(ctx.getFilesDir(), "saved.json"); }
}
