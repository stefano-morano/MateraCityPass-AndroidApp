package com.example.materacitypass;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;

public class NfcFragment extends Fragment {

    private NfcAdapter nfcAdapter;
    private TextView statusTv, tvCodeLarge;
    private Button btnWriteFlow, btnTest;
    private Spinner spinnerPassType;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    private enum Mode { IDLE, WRITE_FLOW, TEST_ONLY }
    private Mode mode = Mode.IDLE;
    private String lastCode = null;

    // Enum per i tipi di pass
    private enum PassType {
        RECHARGEABLE("🔃 Ricaricabile", "Pending", true),
        ONE_DAY("➡️ 1 Giorno", "1Day", false),
        TWO_DAYS("➡️ 2 Giorni", "2Day", false),
        THREE_DAYS("➡️ 3 Giorni", "3Day", false);

        private final String displayName;
        private final String userId;
        private final boolean validate;

        PassType(String displayName, String userId, boolean validate) {
            this.displayName = displayName;
            this.userId = userId;
            this.validate = validate;
        }

        public String getDisplayName() { return displayName; }
        public String getUserId() { return userId; }
        public boolean isValidate() { return validate; }

        public static PassType fromDisplayName(String name) {
            for (PassType type : values()) {
                if (type.displayName.equals(name)) {
                    return type;
                }
            }
            return RECHARGEABLE; // default
        }
    }

    private static String[] getDisplayNames() {
        PassType[] types = PassType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].getDisplayName();
        }
        return names;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_nfc, container, false);
        statusTv = view.findViewById(R.id.status);
        tvCodeLarge = view.findViewById(R.id.tvCodeLarge);
        btnWriteFlow = view.findViewById(R.id.btnWriteFlow);
        btnTest = view.findViewById(R.id.btnTest);
        spinnerPassType = view.findViewById(R.id.spinnerPassType);

        // Configura lo Spinner con le opzioni
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                getDisplayNames()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPassType.setAdapter(adapter);

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext());
        if (nfcAdapter == null) {
            setStatus("❌ NFC non disponibile");
            btnWriteFlow.setEnabled(false);
            btnTest.setEnabled(false);
        } else if (!nfcAdapter.isEnabled()) {
            setStatus("⚠️ NFC disabilitato");
        } else {
            setStatus("✓ NFC pronto");
        }

        btnWriteFlow.setOnClickListener(v -> {
            mode = Mode.WRITE_FLOW;
            lastCode = null;
            setBigCode(null);
            setStatus("🔍 Avvicina un tag da inizializzare");
        });

        btnTest.setOnClickListener(v -> {
            mode = Mode.TEST_ONLY;
            setBigCode(null);
            setStatus("📖 Avvicina un tag da leggere");
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (nfcAdapter != null && getActivity() != null) {
            try {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        requireActivity(), 0,
                        new Intent(requireActivity(), requireActivity().getClass())
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                IntentFilter[] filters = new IntentFilter[]{
                        new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                        new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                        new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
                };
                String[][] techLists = new String[][]{
                        new String[]{Ndef.class.getName()},
                        new String[]{NdefFormatable.class.getName()}
                };
                nfcAdapter.enableForegroundDispatch(requireActivity(), pendingIntent, filters, techLists);
            } catch (Exception e) {
                android.util.Log.e("NFC", "Error enabling foreground dispatch", e);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (nfcAdapter != null && getActivity() != null) {
            try {
                nfcAdapter.disableForegroundDispatch(requireActivity());
            } catch (Exception e) {
                android.util.Log.e("NFC", "Error disabling foreground dispatch", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Shutdown ExecutorService per prevenire memory leak
        if (ioPool != null && !ioPool.isShutdown()) {
            ioPool.shutdown();
        }
    }

    public void handleNfcIntent(Intent intent) {
        if (!isAdded() || getActivity() == null) return;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            setStatus("❌ Tag non rilevato");
            return;
        }

        android.util.Log.d("NFC", "Tag ID: " + bytesToHex(tag.getId()));
        if (mode == Mode.WRITE_FLOW) {
            // Ottieni il tipo di pass sul thread UI prima di passare al background
            PassType passType = getSelectedPassTypeSync();
            ioPool.execute(() -> handleWriteFlow(tag, passType));
        } else if (mode == Mode.TEST_ONLY) {
            ioPool.execute(() -> handleTestOnly(tag));
        } else {
            setStatus("ℹ️ Tag rilevato - Seleziona un'azione");
        }
    }

    private PassType getSelectedPassTypeSync() {
        if (getActivity() == null) {
            return PassType.RECHARGEABLE;
        }
        // Ottieni il valore direttamente se siamo già sul thread UI
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            String selected = (String) spinnerPassType.getSelectedItem();
            return PassType.fromDisplayName(selected);
        }
        // Altrimenti usa un lock per sincronizzare
        final PassType[] result = new PassType[1];
        final Object lock = new Object();
        synchronized (lock) {
            requireActivity().runOnUiThread(() -> {
                synchronized (lock) {
                    String selected = (String) spinnerPassType.getSelectedItem();
                    result[0] = PassType.fromDisplayName(selected);
                    lock.notify();
                }
            });
            try {
                lock.wait(1000); // timeout di 1 secondo
            } catch (InterruptedException e) {
                android.util.Log.e("NFC", "Interrupted while waiting for UI thread", e);
            }
        }
        return result[0] != null ? result[0] : PassType.RECHARGEABLE;
    }

    private void handleWriteFlow(Tag tag, PassType passType) {
        setStatus("🎲 Generazione codice...");

        String code = generateUniqueCode(15);
        if (code == null) {
            setStatus("❌ Impossibile generare codice univoco");
            setBigCode(null);
            mode = Mode.IDLE;
            return;
        }

        setStatus("📝 Scrittura: " + code);
        // SCRIVI DIRETTAMENTE (sovrascrivi quello che c'è)
        if (!writeTextToTag(tag, code)) {
            setStatus("❌ Scrittura fallita");
            setBigCode(null);
            mode = Mode.IDLE;
            return;
        }

        setStatus("✓ Verifica lettura...");
        String readBack = readTextFromTag(tag);
        if (code.equals(readBack)) {
            setBigCode(code);
            setStatus("💾 Salvataggio su Airtable...");
            if (createAirtableRecord(code, passType)) {
                lastCode = code;
                setStatus("✅ Completato!");
            } else {
                setStatus("⚠️ Scritto ma errore Airtable");
            }

        } else {
            setStatus("❌ Verifica fallita");
            setBigCode(null);
        }

        mode = Mode.IDLE;
    }

    private void handleTestOnly(Tag tag) {
        String read = readTextFromTag(tag);
        if (read == null || read.isEmpty()) {
            setBigCode(null);
            setStatus("❌ Tag vuoto o illeggibile");
        } else {
            setBigCode(read);
            if (read.equals(lastCode)) {
                setStatus("✅ Verifica OK!");
            } else {
                setStatus("📖 Letto: " + read);
            }
        }
        mode = Mode.IDLE;
    }

    private boolean writeTextToTag(Tag tag, String text) {
        Ndef ndef = null;
        NdefFormatable formatable = null;
        try {
            NdefMessage msg = new NdefMessage(new NdefRecord[]{createTextRecord(text)});
            ndef = Ndef.get(tag);
            if (ndef != null) {
                // Tag NDEF - sovrascrivi
                ndef.connect();
                if (!ndef.isWritable()) {
                    setStatus("❌ Tag protetto");
                    return false;
                }

                ndef.writeNdefMessage(msg);
                ndef.close();
                android.util.Log.d("NFC", "✓ Sovrascritto: " + text);
                return true;
            } else {
                // Tag vergine - formatta
                formatable = NdefFormatable.get(tag);
                if (formatable != null) {
                    formatable.connect();
                    formatable.format(msg);
                    formatable.close();
                    android.util.Log.d("NFC", "✓ Formattato e scritto: " + text);
                    return true;
                }
                setStatus("❌ Tag non supportato");
                return false;
            }

        } catch (Exception e) {
            android.util.Log.e("NFC", "Errore: " + e.getMessage(), e);
            setStatus("❌ Errore scrittura");
            return false;
        } finally {
            try {
                if (ndef != null && ndef.isConnected()) {
                    ndef.close();
                }
                if (formatable != null && formatable.isConnected()) {
                    formatable.close();
                }
            } catch (Exception ignored) {}
        }
    }

    private String readTextFromTag(Tag tag) {
        Ndef ndef = null;
        try {
            ndef = Ndef.get(tag);
            if (ndef == null) {
                return null;
            }

            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            ndef.close();
            if (msg == null) {
                return null;
            }

            return parseFirstTextRecord(msg);
        } catch (Exception e) {
            android.util.Log.e("NFC", "Errore lettura: " + e.getMessage(), e);
            return null;
        } finally {
            try {
                if (ndef != null && ndef.isConnected()) {
                    ndef.close();
                }
            } catch (Exception ignored) {}
        }
    }

    private NdefRecord createTextRecord(String text) {
        // URI Record con prefisso 0x00 (no protocol) - universalmente leggibile
        byte[] textBytes = text.getBytes(Charset.forName("UTF-8"));
        byte[] payload = new byte[1 + textBytes.length];
        payload[0] = 0x00; // No URI prefix
        System.arraycopy(textBytes, 0, payload, 1, textBytes.length);
        return new NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_URI,
                new byte[0],
                payload
        );
    }

    private String parseFirstTextRecord(NdefMessage message) {
        for (NdefRecord r : message.getRecords()) {
            if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN) {
                // Supporta URI Record (nuovo formato)
                if (Arrays.equals(r.getType(), NdefRecord.RTD_URI)) {
                    byte[] payload = r.getPayload();
                    if (payload.length > 1) {
                        // Salta il primo byte (URI prefix, nel nostro caso 0x00)
                        return new String(payload, 1, payload.length - 1, Charset.forName("UTF-8"));
                    }
                }
                // Supporta anche Text Record (vecchio formato, per retrocompatibilità)
                if (Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                    byte[] payload = r.getPayload();
                    int langLen = payload[0] & 0x3F;
                    return new String(payload, 1 + langLen, payload.length - 1 - langLen, Charset.forName("UTF-8"));
                }
            }
        }
        return null;
    }

    private String generateUniqueCode(int maxTries) {
        for (int i = 0; i < maxTries; i++) {
            String code = randomCode6();
            android.util.Log.d("AIRTABLE", "Try " + (i + 1) + ": " + code);
            if (!airtableCodeExists(code)) {
                return code;
            }
        }
        // Restituisce null invece di generare un codice potenzialmente duplicato
        android.util.Log.e("AIRTABLE", "Failed to generate unique code after " + maxTries + " attempts");
        return null;
    }

    private boolean airtableCodeExists(String code) {
        try {
            String formula = URLEncoder.encode("{NFCID}='" + code + "'", "UTF-8");
            String url = "https://api.airtable.com/v0/" + BuildConfig.AIRTABLE_BASE_ID +
                    "/" + BuildConfig.AIRTABLE_TABLE_NAME + "?filterByFormula=" + formula;
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + BuildConfig.AIRTABLE_API_KEY)
                    .get()
                    .build();
            Response res = http.newCall(req).execute();
            String body = res.body().string();
            res.close();
            return body.contains("\"records\":[") && !body.contains("\"records\":[]");
        } catch (Exception e) {
            android.util.Log.e("AIRTABLE", "Check error", e);
            return false;
        }
    }

    private boolean createAirtableRecord(String code, PassType passType) {
        if (!hasInternetConnection()) {
            android.util.Log.w("AIRTABLE", "No internet connection");
            return false;
        }

        try {
            String url = "https://api.airtable.com/v0/" + BuildConfig.AIRTABLE_BASE_ID +
                    "/" + BuildConfig.AIRTABLE_TABLE_NAME;

            // Costruisci il JSON usando l'enum
            String json = String.format(
                    "{\"fields\":{\"NFCID\":\"%s\",\"UserId\":\"%s\",\"Validate\":%b}}",
                    code,
                    passType.getUserId(),
                    passType.isValidate()
            );

            android.util.Log.d("AIRTABLE", "JSON: " + json);

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + BuildConfig.AIRTABLE_API_KEY)
                    .post(RequestBody.create(json, JSON))
                    .build();
            Response res = http.newCall(req).execute();
            boolean success = res.isSuccessful();
            if (!success) {
                android.util.Log.e("AIRTABLE", "Request failed: " + res.code() + " " + res.message());
            }
            res.close();
            return success;
        } catch (Exception e) {
            android.util.Log.e("AIRTABLE", "Create error", e);
            return false;
        }
    }

    private boolean hasInternetConnection() {
        ConnectivityManager cm = (ConnectivityManager) requireContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private String randomCode6() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void setStatus(String s) {
        if (getActivity() != null) {
            requireActivity().runOnUiThread(() -> statusTv.setText(s));
        }
    }

    private void setBigCode(String codeOrNull) {
        if (getActivity() != null) {
            requireActivity().runOnUiThread(() -> {
                if (codeOrNull == null || codeOrNull.isEmpty()) {
                    tvCodeLarge.setText("");
                    tvCodeLarge.setVisibility(View.GONE);
                } else {
                    tvCodeLarge.setText(codeOrNull);
                    tvCodeLarge.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}