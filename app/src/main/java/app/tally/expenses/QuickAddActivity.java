package app.tally.expenses;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

/** Compact native quick-entry form launched from the home-screen widget, without opening MainActivity. */
public class QuickAddActivity extends Activity {
    static final String EXTRA_TYPE = "widget_entry_type";
    private static final class Choice {
        final String id, name, currency;
        Choice(String id, String name, String currency) { this.id=id; this.name=name; this.currency=currency; }
        @Override public String toString() { return name; }
    }
    private final ArrayList<Choice> accounts=new ArrayList<>(), categories=new ArrayList<>();
    private String type;
    private EditText amount, note, received;
    private Spinner account, category, from, to;
    private Button save;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);type=getIntent().getStringExtra(EXTRA_TYPE);
        if(!"income".equals(type)&&!"transfer".equals(type))type="expense";
        JSONObject notebook=readNotebook();
        if(notebook==null||!loadChoices(notebook)){
            TextView message=new TextView(this);message.setText("Open Tally once and create an account before using widget quick entry.");
            message.setPadding(dp(24),dp(24),dp(24),dp(24));setContentView(message);return;
        }
        setContentView(form());getWindow().setLayout(dp(360),-2);
    }
    private JSONObject readNotebook(){
        String raw=SavedCopy.read(this);if(raw==null)return null;int nl=raw.indexOf('\n');if(nl<1)return null;
        try{return new JSONObject(raw.substring(nl+1));}catch(JSONException e){return null;}
    }
    private boolean loadChoices(JSONObject data){
        JSONArray aa=data.optJSONArray("accounts");
        for(int i=0;aa!=null&&i<aa.length();i++){JSONObject a=aa.optJSONObject(i);
            if(a!=null&&!a.optBoolean("archived")&&!a.optString("id").isEmpty())accounts.add(new Choice(a.optString("id"),a.optString("name"),a.optString("currency")));}
        if(accounts.isEmpty())return false;
        if(!"transfer".equals(type)){String kind="income".equals(type)?"in":"out";JSONArray cc=data.optJSONArray("cats");
            for(int i=0;cc!=null&&i<cc.length();i++){JSONObject c=cc.optJSONObject(i);if(c!=null&&kind.equals(c.optString("kind")))categories.add(new Choice(c.optString("id"),c.optString("name"),""));}
            if(categories.isEmpty())return false;}
        return true;
    }
    private LinearLayout root(){LinearLayout r=new LinearLayout(this);r.setOrientation(1);r.setPadding(dp(22),dp(18),dp(22),dp(16));return r;}
    private void label(LinearLayout r,String text){TextView v=new TextView(this);v.setText(text);v.setTextSize(13);v.setPadding(0,dp(9),0,dp(3));r.addView(v);}
    private EditText edit(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(17);e.setHint(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return e;}
    private LinearLayout.LayoutParams lp(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);return p;}
    private Spinner spinner(ArrayList<Choice> values){Spinner s=new Spinner(this);ArrayAdapter<Choice>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);return s;}
    private View form(){
        ScrollView scroll=new ScrollView(this);LinearLayout r=root();scroll.addView(r);
        TextView title=new TextView(this);title.setText("expense".equals(type)?getString(R.string.qa_out):"income".equals(type)?getString(R.string.qa_in):getString(R.string.qa_tr));title.setTextSize(21);title.setTypeface(null,1);r.addView(title,lp(6));
        label(r,"Amount");amount=edit("Amount");r.addView(amount,lp(5));
        if("transfer".equals(type)){
            label(r,"From account");from=spinner(accounts);r.addView(from,lp(4));label(r,"To account");to=spinner(accounts);r.addView(to,lp(4));
            label(r,"Amount received (different currencies only)");received=edit("Amount received");r.addView(received,lp(5));
            android.widget.AdapterView.OnItemSelectedListener listener=new android.widget.AdapterView.OnItemSelectedListener(){
                public void onItemSelected(android.widget.AdapterView<?>p,View v,int pos,long id){updateReceived();}
                public void onNothingSelected(android.widget.AdapterView<?>p){updateReceived();}};
            from.setOnItemSelectedListener(listener);to.setOnItemSelectedListener(listener);
        }else{label(r,"Category");category=spinner(categories);r.addView(category,lp(4));label(r,"Account");account=spinner(accounts);r.addView(account,lp(5));}
        label(r,"Note (optional)");note=new EditText(this);note.setSingleLine(true);note.setHint("Note");r.addView(note,lp(8));
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.END);Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());
        save=new Button(this);save.setText("Save");save.setOnClickListener(v->saveEntry());buttons.addView(cancel);buttons.addView(save);r.addView(buttons);return scroll;
    }
    private void updateReceived(){if(received==null||from==null||to==null)return;Choice a=(Choice)from.getSelectedItem(),b=(Choice)to.getSelectedItem();received.setVisibility(a!=null&&b!=null&&!a.currency.equals(b.currency)?View.VISIBLE:View.GONE);}
    private void saveEntry(){
        double value;try{value=Double.parseDouble(amount.getText().toString().trim());}catch(Exception e){value=0;}
        if(!(value>0)||!Double.isFinite(value)){amount.setError("Enter a valid amount");return;}
        Choice a,c=null,b=null;Double got=null;
        if("transfer".equals(type)){a=(Choice)from.getSelectedItem();b=(Choice)to.getSelectedItem();
            if(a==null||b==null||a.id.equals(b.id)){Toast.makeText(this,"Choose two different accounts",Toast.LENGTH_SHORT).show();return;}
            if(!a.currency.equals(b.currency)){try{got=Double.parseDouble(received.getText().toString().trim());}catch(Exception e){got=null;}
                if(got==null||!(got>0)||!Double.isFinite(got)){received.setError("Enter amount received");return;}}
        }else{a=(Choice)account.getSelectedItem();c=(Choice)category.getSelectedItem();}
        save.setEnabled(false);final Choice fa=a,fc=c,fb=b;final Double fg=got;final double amountValue=value;final String memo=note.getText().toString().trim();
        new Thread(()->{boolean ok=SavedCopy.addWidgetEntry(this,type,amountValue,fa.id,fc==null?null:fc.id,fb==null?null:fb.id,fg,memo);
            runOnUiThread(()->{if(!ok){save.setEnabled(true);Toast.makeText(this,"Could not save. Open Tally and try again.",Toast.LENGTH_LONG).show();return;}
                if ("expense".equals(type)) ReminderReceiver.widgetExpenseSaved(this);
                TallyWidget.refresh(this);MainActivity.deliver("window.tallyWidgetRefresh&&window.tallyWidgetRefresh()");
                Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();finish();});
        },"tally-widget-entry").start();
    }
}