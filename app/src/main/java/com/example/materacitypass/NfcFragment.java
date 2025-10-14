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
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;

public class NfcFragment extends Fragment {

    private NfcAdapter nfcAdapter;
    private TextView statusTv, tvCodeLarge;
    private Button btnWriteFlow, btnTest;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    private enum Mode { IDLE, WRITE_FLOW, TEST_ONLY }
    private Mode mode = Mode.IDLE;
    private String lastCode = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_nfc, container, false);

        statusTv = view.findViewById(R.id.status);
        tvCodeLarge = view.findViewById(R.id.tvCodeLarge);
        btnWriteFlow = view.findViewById(R.id.btnWriteFlow);
        btnTest = view.findViewById(R.id.btnTest);

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
            setStatus("📝 Avvicina un tag da inizializzare");
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

    public void handleNfcIntent(Intent intent) {
        if (!isAdded() || getActivity() == null) return;

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            setStatus("❌ Tag non rilevato");
            return;
        }

        android.util.Log.d("NFC", "Tag ID: " + bytesToHex(tag.getId()));

        if (mode == Mode.WRITE_FLOW) {
            ioPool.execute(() -> handleWriteFlow(tag));
        } else if (mode == Mode.TEST_ONLY) {
            ioPool.execute(() -> handleTestOnly(tag));
        } else {
            setStatus("ℹ️ Tag rilevato - Seleziona un'azione");
        }
    }

    private void handleWriteFlow(Tag tag) {
        setStatus("🎲 Generazione codice...");
        String code = generateUniqueCode(15);

        if (code == null) {
            setStatus("❌ Impossibile generare codice");
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

            if (createAirtableRecord(code)) {
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
        byte[] langBytes = "en".getBytes(Charset.forName("US-ASCII"));
        byte[] textBytes = text.getBytes(Charset.forName("UTF-8"));
        byte[] payload = new byte[1 + langBytes.length + textBytes.length];
        payload[0] = (byte) langBytes.length;
        System.arraycopy(langBytes, 0, payload, 1, langBytes.length);
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.length, textBytes.length);
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, new byte[0], payload);
    }

    private String parseFirstTextRecord(NdefMessage message) {
        for (NdefRecord r : message.getRecords()) {
            if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN &&
                    Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                byte[] payload = r.getPayload();
                int langLen = payload[0] & 0x3F;
                return new String(payload, 1 + langLen, payload.length - 1 - langLen, Charset.forName("UTF-8"));
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
        // FALLBACK: genera comunque un codice
        return randomCode6();
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
            return false; // In caso di errore, assume non esiste
        }
    }

    private boolean createAirtableRecord(String code) {
        if (!hasInternetConnection()) {
            android.util.Log.w("AIRTABLE", "No internet connection");
            return false;
        }
        try {
            String url = "https://api.airtable.com/v0/" + BuildConfig.AIRTABLE_BASE_ID +
                    "/" + BuildConfig.AIRTABLE_TABLE_NAME;
            String json = "{\"fields\":{\"NFCID\":\"" + code + "\",\"UserId\":\"Pending\"}}";

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + BuildConfig.AIRTABLE_API_KEY)
                    .post(RequestBody.create(json, JSON))
                    .build();

            Response res = http.newCall(req).execute();
            boolean success = res.isSuccessful();
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
