package app.tally.expenses;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Registers Tally reminders with Android Telecom as self-managed incoming calls. */
public class TallyConnectionService extends ConnectionService {
    static volatile TallyConnection current;

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
        c.finish(answered);
    }

    static final class TallyConnection extends Connection {
        private boolean ended;

        @Override
        public void onAnswer() {
            setActive();
        }

        @Override
        public void onAnswer(int videoState) {
            setActive();
        }

        @Override
        public void onReject() {
            finish(false);
        }

        @Override
        public void onDisconnect() {
            finish(false);
        }

        @Override
        public void onAbort() {
            finish(false);
        }

        void finish(boolean answered) {
            if (ended) return;
            ended = true;
            setDisconnected(new DisconnectCause(answered ? DisconnectCause.LOCAL : DisconnectCause.REJECTED));
            destroy();
        }
    }
}
