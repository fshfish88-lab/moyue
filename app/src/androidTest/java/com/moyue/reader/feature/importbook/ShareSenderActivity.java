package com.moyue.reader.feature.importbook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** The sender APK runs independently, without borrowing the target's Kotlin runtime. */
public class ShareSenderActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent outgoing = new Intent(getIntent());
        outgoing.setComponent(new ComponentName("com.moyue.reader", "com.moyue.reader.MainActivity"));
        outgoing.setFlags(getIntent().getIntExtra("share_flags", Intent.FLAG_ACTIVITY_NEW_TASK));
        outgoing.removeExtra("share_flags");
        startActivity(outgoing);
        finish();
    }
}
