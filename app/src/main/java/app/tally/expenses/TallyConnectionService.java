package app.tally.expenses;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.pm.PackageManager;
import android.provider.ContactsContract;
import java.util.ArrayList;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;

/** Registers Tally reminders with Android Telecom as self-managed incoming calls. */
public class TallyConnectionService extends ConnectionService {
    static volatile TallyConnection current;
    static volatile Context appContext;
    static volatile boolean activeTest;
    static final String CALLER_NUMBER = "0000000000";

    /** Creates a local contact so Bluetooth wearables that resolve numbers from contacts can label the call. */
    static boolean ensureCallerContact(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false;
        android.content.SharedPreferences prefs = context.getSharedPreferences("tally_call_identity", Context.MODE_PRIVATE);
        if (prefs.getBoolean("contact_created", false)) return true;
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Tally").build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, CALLER_NUMBER)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, "Tally reminder ID (not a real number)").build());
        try {
            context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            prefs.edit().putBoolean("contact_created", true).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean placeIncomingCall(Context context, boolean test) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            appContext = context.getApplicationContext();
            activeTest = test;
            ensureCallerContact(context);
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom == null) return false;
            ComponentName service = new ComponentName(context, TallyConnectionService.class);
            // Replace the earlier account registration so stale "Salah Reminder" metadata cannot be reused.
            telecom.unregisterPhoneAccount(new PhoneAccountHandle(service, "tally_expense_reminder"));
            PhoneAccountHandle handle = new PhoneAccountHandle(service, "tally_call_v2");
            PhoneAccount account = PhoneAccount.builder(handle, "Tally")
                    .setShortDescription("Tally")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build();
            telecom.registerPhoneAccount(account);
            Bundle extras = new Bundle();
            Bundle callExtras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", CALLER_NUMBER, null));
            callExtras.putString("tally_caller", "Tally");
            callExtras.putBoolean("tally_test_call", test);
            extras.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras);
            telecom.addNewIncomingCall(handle, extras);
            return true;
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle handle, ConnectionRequest request) {
        TallyConnection c = new TallyConnection();
        // Android forwards this exact display name to Bluetooth and wearable calling surfaces.
        c.setCallerDisplayName("Tally", TelecomManager.PRESENTATION_ALLOWED);
        c.setAddress(request.getAddress() == null ? Uri.fromParts("tel", CALLER_NUMBER, null) : request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        c.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        c.setAudioModeIsVoip(true);
        c.setRinging();
        current = c;
        return c;
    }

    static void endCurrent(boolean answered) {
        TallyConnection c = current;
        if (c == null) return;
        current = null;
        c.finish(answered, false);
    }

    static final class TallyConnection extends Connection {
        private boolean ended;

        @Override
        public void onAnswer() { finish(true, true); }

        @Override
        public void onAnswer(int videoState) { finish(true, true); }

        @Override
        public void onReject() { finish(false, true); }

        @Override
        public void onDisconnect() { finish(false, true); }

        @Override
        public void onAbort() { finish(false, true); }

        void finish(boolean answered, boolean fromTelecom) {
            if (ended) return;
            ended = true;
            if (current == this) current = null;
            setDisconnected(new DisconnectCause(answered ? DisconnectCause.LOCAL : DisconnectCause.REJECTED));
            destroy();
            Context ctx = appContext;
            if (fromTelecom && ctx != null) {
                if (activeTest) ReminderReceiver.finishTest(ctx);
                else ReminderReceiver.handleExpenseResponse(ctx,
                        answered ? ReminderReceiver.EXPENSE_ANSWER : ReminderReceiver.EXPENSE_DECLINE);
            }
        }
    }
}
