package com.lamplitisles.herdrmobile;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(HerdrSshPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
