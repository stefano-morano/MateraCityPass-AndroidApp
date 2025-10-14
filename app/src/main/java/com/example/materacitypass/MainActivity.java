package com.example.materacitypass;

import android.content.Intent;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private NfcFragment nfcFragment;
    private UsbFragment usbFragment;
    private NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.setStatusBarColor(Color.parseColor("#F5F5F5"));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        nfcFragment = new NfcFragment();
        usbFragment = new UsbFragment();

        // Carica il fragment NFC di default
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, nfcFragment)
                .commitNow();  // ← CAMBIA DA .commit() a .commitNow()

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            if (item.getItemId() == R.id.nav_nfc) {
                selectedFragment = nfcFragment;
            } else if (item.getItemId() == R.id.nav_usb) {
                selectedFragment = usbFragment;
            }

            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
            }

            return true;
        });

        // ← SPOSTATO DOPO commitNow(), ora il fragment è pronto
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();

        // Verifica se è un intent NFC
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {

            // Passa l'intent al fragment NFC se è visibile
            Fragment currentFragment = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_container);

            if (currentFragment instanceof NfcFragment) {
                ((NfcFragment) currentFragment).handleNfcIntent(intent);
            } else {
                // Se non siamo nel fragment NFC, passa comunque l'intent
                // e salvalo per quando torneremo al fragment
                if (nfcFragment != null) {
                    nfcFragment.handleNfcIntent(intent);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Il foreground dispatch viene gestito dal fragment
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Il foreground dispatch viene gestito dal fragment
    }
}
