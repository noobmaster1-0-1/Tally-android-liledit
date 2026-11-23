package app.tally.expenses;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Registers Tally reminders with Android Telecom as self-managed incoming calls. */
public class TallyConnectionService extends ConnectionService {
    static volatile TallyConnection current;
    static volatile Context appContext;
    static volatile boolean activeTest;

    static boolean placeIncomingCall(Context context, boolean test) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            appContext = context.getApplicationContext();
            activeTest = test;
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom == null) return false;
            PhoneAccountHandle handle = new PhoneAccountHandle(
                    new ComponentName(context, TallyConnectionService.class), "tally_expense_reminder");
            PhoneAccount account = PhoneAccount.builder(handle, "Tally expense reminders")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build();
            telecom.registerPhoneAccount(account);
            Bundle extras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts("tel", "0000000000", null));
            Bundle callExtras = new Bundle();
            callExtras.putString("tally_caller", "Tally expense reminder");
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
        Bundle extras = request.getExtras();
        String caller = extras == null ? "Tally" : extras.getString("tally_caller", "Tally");
        c.setCallerDisplayName(caller, TelecomManager.PRESENTATION_ALLOWED);
        c.setAddress(Uri.fromParts("tel", "0000000000", null), TelecomManager.PRESENTATION_ALLOWED);
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
