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
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.auth.FirebaseAuth;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;

public class NfcFragment extends Fragment {

    private NfcAdapter nfcAdapter;
    private TextView statusTv, tvCodeLarge, tvCustomIdError;
    private Button btnWriteFlow, btnTest, btnLogout;
    private Spinner spinnerPassType;
    private TextInputEditText etNumber, etCustomId;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    private enum Mode { IDLE, WRITE_FLOW, TEST_ONLY }
    private Mode mode = Mode.IDLE;
    private String lastCode = null;

    // Enum per i tipi di pass
    private enum PassType {
        ONE_DAY("1 Giorno", "1Day", false),
        TWO_DAYS("2 Giorni", "2Day", false),
        THREE_DAYS("3 Giorni", "3Day", false);

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
            return ONE_DAY; // default (cambiato da RECHARGEABLE)
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
        btnLogout = view.findViewById(R.id.btnLogout);
        spinnerPassType = view.findViewById(R.id.spinnerPassType);
        etNumber = view.findViewById(R.id.etNumber);
        etCustomId = view.findViewById(R.id.etCustomId);
        tvCustomIdError = view.findViewById(R.id.tvCustomIdError);

        // Verifica se l'utente è già autenticato, altrimenti mostra il dialog di login
        if (auth.getCurrentUser() == null) {
            showLoginDialog();
        } else {
            android.util.Log.d("FIREBASE_AUTH", "Utente già autenticato: " + auth.getCurrentUser().getEmail());
        }

        // Configura lo Spinner con le opzioni
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                getDisplayNames()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPassType.setAdapter(adapter);

        // Setup Custom ID validation con verifica real-time
        etCustomId.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String customId = s.toString().trim().toUpperCase();

                // Se il campo è vuoto, abilita il pulsante
                if (customId.isEmpty()) {
                    tvCustomIdError.setVisibility(View.GONE);
                    btnWriteFlow.setEnabled(true);
                    return;
                }

                // Valida il formato dell'ID (6 caratteri alfanumerici)
                String error = validateCustomId(customId);
                if (error != null) {
                    tvCustomIdError.setText(error);
                    tvCustomIdError.setVisibility(View.VISIBLE);
                    btnWriteFlow.setEnabled(false);  // Disabilita il pulsante
                    return;
                }

                // ID valido - controlla se esiste nei database (ASINCRONO)
                tvCustomIdError.setVisibility(View.GONE);
                btnWriteFlow.setEnabled(false);  // Disabilita mentre controlla

                checkIfIdExistsAsync(customId, exists -> {
                    if (exists) {
                        tvCustomIdError.setText("ID già presente nei database");
                        tvCustomIdError.setVisibility(View.VISIBLE);
                        btnWriteFlow.setEnabled(false);
                    } else {
                        tvCustomIdError.setVisibility(View.GONE);
                        btnWriteFlow.setEnabled(true);  // Abilita il pulsante
                    }
                });
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

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

        btnLogout.setOnClickListener(v -> {
            auth.signOut();
            android.util.Log.d("FIREBASE_AUTH", "Logout completato");
            android.widget.Toast.makeText(requireContext(), "Logout completato", android.widget.Toast.LENGTH_SHORT).show();
            showLoginDialog();
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
            return PassType.ONE_DAY;
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
        return result[0] != null ? result[0] : PassType.ONE_DAY;
    }

    private void handleWriteFlow(Tag tag, PassType passType) {
        setStatus("🎲 Generazione codice...");

        // Controlla se l'utente ha specificato un ID personalizzato
        String customId = etCustomId.getText().toString().trim().toUpperCase();
        String code;

        if (!customId.isEmpty()) {
            // Valida l'ID personalizzato
            String error = validateCustomId(customId);
            if (error != null) {
                setStatus("❌ " + error);
                setBigCode(null);
                mode = Mode.IDLE;
                return;
            }

            // Controlla se l'ID esiste già nei database
            if (checkIfIdExists(customId)) {
                setStatus("❌ ID già presente in database");
                setBigCode(null);
                mode = Mode.IDLE;
                tvCustomIdError.setText("ID già presente");
                tvCustomIdError.setVisibility(View.VISIBLE);
                return;
            }

            code = customId;
        } else {
            // Genera un codice casuale
            code = generateUniqueCode(15);
            if (code == null) {
                setStatus("❌ Impossibile generare codice univoco");
                setBigCode(null);
                mode = Mode.IDLE;
                return;
            }
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
            setStatus("💾 Salvataggio nei database...");

            // Salva su Airtable
            boolean airtableSuccess = createAirtableRecord(code, passType);

            // Salva su Firestore
            boolean firestoreSuccess = createFirestoreRecord(code, passType);

            if (airtableSuccess && firestoreSuccess) {
                lastCode = code;
                setStatus("✅ Completato!");
            } else if (airtableSuccess || firestoreSuccess) {
                lastCode = code;
                setStatus("⚠️ Salvataggio parziale");
            } else {
                setStatus("⚠️ Scritto ma errore nel salvataggio");
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

            // Ottieni il numero dalla textbox, oppure 0 se vuota
            int lottoNumber = getNumberAsInt();

            // Costruisci il JSON usando l'enum
            String json = String.format(
                    "{\"fields\":{\"NFCID\":\"%s\",\"UserId\":\"%s\",\"Validate\":%b,\"Lotto\":%d}}",
                    code,
                    passType.getUserId(),
                    passType.isValidate(),
                    lottoNumber
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

    private boolean createFirestoreRecord(String code, PassType passType) {
        try {
            // Ottieni il numero dalla textbox, oppure 0 se vuota
            int lottoNumber = getNumberAsInt();

            // Crea una mappa con i dati
            Map<String, Object> record = new HashMap<>();
            record.put("nfcid", code);
            record.put("userId", passType.getUserId());
            record.put("validate", passType.isValidate());
            record.put("lotto", lottoNumber);
            record.put("timestamp", System.currentTimeMillis());

            // Salva su Firestore nella collection "NFC"
            // Usa il codice come ID del documento
            DocumentReference docRef = db.collection("NFC").document(code);

            docRef.set(record).addOnSuccessListener(aVoid -> {
                android.util.Log.d("FIRESTORE", "Record salvato con successo: " + code);
            }).addOnFailureListener(e -> {
                android.util.Log.e("FIRESTORE", "Errore salvataggio", e);
            });

            return true; // Ritorna true perché la richiesta è stata inviata (il risultato sarà asincrono)
        } catch (Exception e) {
            android.util.Log.e("FIRESTORE", "Create error", e);
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

    /**
     * Ottiene il numero inserito nella NumberBox
     * @return il numero come String, o String vuota se non presente
     */
    public String getNumber() {
        if (etNumber != null) {
            return etNumber.getText().toString().trim();
        }
        return "";
    }

    /**
     * Ottiene il numero inserito come integer
     * @return il numero come int, o 0 se non valido
     */
    public int getNumberAsInt() {
        String num = getNumber();
        try {
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Imposta il numero nella NumberBox
     * @param number il numero da inserire
     */
    public void setNumber(String number) {
        if (etNumber != null) {
            etNumber.setText(number);
        }
    }

    /**
     * Pulisce la NumberBox
     */
    public void clearNumber() {
        if (etNumber != null) {
            etNumber.setText("");
        }
    }

    /**
     * Valida l'ID personalizzato
     * @param id l'ID da validare
     * @return null se valido, altrimenti il messaggio di errore
     */
    private String validateCustomId(String id) {
        // Controlla lunghezza
        if (id.length() != 6) {
            return "L'ID deve essere esattamente 6 caratteri";
        }

        // Controlla se è alfanumerico
        if (!id.matches("^[A-Z0-9]{6}$")) {
            return "L'ID deve contenere solo lettere (A-Z) e numeri (0-9)";
        }

        return null; // ID valido
    }

    /**
     * Controlla se l'ID esiste già su Airtable o Firestore
     * @param id l'ID da controllare
     * @return true se l'ID esiste, false altrimenti
     */
    private boolean checkIfIdExists(String id) {
        // Controlla su Airtable (sincronamente)
        return airtableCodeExists(id);
    }

    /**
     * Mostra un dialog di login email/password moderno
     */
    private void showLoginDialog() {
        // Crea il dialog
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());

        // Infla il layout custom
        android.view.View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_login, null);

        // Riferimenti ai componenti del dialog
        com.google.android.material.textfield.TextInputLayout emailLayout = dialogView.findViewById(R.id.emailLayout);
        com.google.android.material.textfield.TextInputLayout passwordLayout = dialogView.findViewById(R.id.passwordLayout);
        com.google.android.material.textfield.TextInputEditText emailInput = dialogView.findViewById(R.id.emailInput);
        com.google.android.material.textfield.TextInputEditText passwordInput = dialogView.findViewById(R.id.passwordInput);
        android.widget.TextView errorMessage = dialogView.findViewById(R.id.errorMessage);
        android.widget.ProgressBar loadingProgress = dialogView.findViewById(R.id.loadingProgress);
        com.google.android.material.button.MaterialButton btnLogin = dialogView.findViewById(R.id.btnLogin);

        builder.setView(dialogView);
        builder.setCancelable(false);

        android.app.AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;

        // Event listener per il pulsante Login
        btnLogin.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();

            // Validazione
            if (email.isEmpty()) {
                emailLayout.setError("Email richiesta");
                return;
            }
            if (password.isEmpty()) {
                passwordLayout.setError("Password richiesta");
                return;
            }

            // Reset errori
            emailLayout.setError(null);
            passwordLayout.setError(null);
            errorMessage.setVisibility(View.GONE);

            // Mostra loading
            loadingProgress.setVisibility(View.VISIBLE);
            btnLogin.setEnabled(false);

            // PRIMA: Tenta il login su Firebase Auth
            auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            // ✅ Utente autenticato! Ora possiamo leggere da Firestore
                            android.util.Log.d("FIREBASE_AUTH", "Utente autenticato: " + auth.getCurrentUser().getEmail());
                            
                            // DOPO: Controlla se l'email è autorizzata (ASINCRONO)
                            checkEmailAllowed(email, isAllowed -> {
                                loadingProgress.setVisibility(View.GONE);
                                
                                if (!isAllowed) {
                                    // Email non autorizzata - fai logout
                                    auth.signOut();
                                    errorMessage.setText("Email non autorizzata per l'accesso");
                                    errorMessage.setVisibility(View.VISIBLE);
                                    btnLogin.setEnabled(true);
                                    return;
                                }

                                // ✅ Email autorizzata e login riuscito!
                                android.util.Log.d("FIREBASE_AUTH", "Login completato per: " + email);
                                dialog.dismiss();
                                android.widget.Toast.makeText(requireContext(), "Benvenuto!", android.widget.Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            // ❌ Login fallito
                            loadingProgress.setVisibility(View.GONE);
                            errorMessage.setText("Email o password non validi");
                            errorMessage.setVisibility(View.VISIBLE);
                            btnLogin.setEnabled(true);
                        }
                    });
        });

        dialog.show();

        // Personalizza il dialog per avere larghezza massima
        android.view.WindowManager.LayoutParams layoutParams = new android.view.WindowManager.LayoutParams();
        layoutParams.copyFrom(dialog.getWindow().getAttributes());
        layoutParams.width = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85);
        dialog.getWindow().setAttributes(layoutParams);
    }

    /**
     * Interfaccia per callback del controllo ID
     */
    private interface IdCheckCallback {
        void onResult(boolean exists);
    }

    /**
     * Controlla asincrono se l'ID esiste su Airtable o Firestore
     * Non blocca il main thread
     * Il callback è sempre eseguito sul main thread
     */
    private void checkIfIdExistsAsync(String id, IdCheckCallback callback) {
        // Controlla su Airtable (sincronamente in background thread)
        ioPool.execute(() -> {
            try {
                boolean existsOnAirtable = airtableCodeExists(id);

                if (existsOnAirtable) {
                    // Callback dal main thread
                    requireActivity().runOnUiThread(() -> callback.onResult(true));
                    return;
                }

                // Controlla su Firestore (asincrono)
                db.collection("NFC").document(id).get()
                        .addOnCompleteListener(task -> {
                            try {
                                if (task.isSuccessful()) {
                                    com.google.firebase.firestore.DocumentSnapshot doc = task.getResult();
                                    boolean existsOnFirestore = doc != null && doc.exists();
                                    android.util.Log.d("ID_CHECK", "ID " + id + " esiste su Firestore: " + existsOnFirestore);
                                    callback.onResult(existsOnFirestore);
                                } else {
                                    android.util.Log.w("ID_CHECK", "Errore controllo Firestore", task.getException());
                                    callback.onResult(false);  // Se errore, consenti il caricamento
                                }
                            } catch (Exception e) {
                                android.util.Log.e("ID_CHECK", "Errore nel callback Firestore", e);
                                callback.onResult(false);
                            }
                        });
            } catch (Exception e) {
                android.util.Log.e("ID_CHECK", "Errore nel controllo ID", e);
                requireActivity().runOnUiThread(() -> callback.onResult(false));
            }
        });
    }

    /**
     * Interfaccia per callback del controllo email
     */
    private interface EmailCheckCallback {
        void onResult(boolean isAllowed);
    }

    /**
     * Controlla se l'email è autorizzata (nella whitelist admin da Firestore)
     * Legge la lista di email admin dalla collection "config" documento "admin_emails"
     * SICURO: Le email non sono hardcoded nel codice
     * ASINCRONO: Usa callback, non blocca il thread principale
     */
    private void checkEmailAllowed(String email, EmailCheckCallback callback) {
        db.collection("config").document("admin_emails").get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        com.google.firebase.firestore.DocumentSnapshot adminDoc = task.getResult();

                        if (adminDoc != null && adminDoc.exists()) {
                            // Ottiene l'array di email dal campo "emails"
                            java.util.List<String> allowedEmails =
                                    (java.util.List<String>) adminDoc.get("emails");

                            if (allowedEmails != null) {
                                android.util.Log.d("AUTH", "Email autorizzate caricate da Firestore: " + allowedEmails);
                                callback.onResult(allowedEmails.contains(email.toLowerCase()));
                            } else {
                                android.util.Log.w("AUTH", "Campo 'emails' non trovato in config/admin_emails");
                                callback.onResult(false);
                            }
                        } else {
                            android.util.Log.w("AUTH", "Documento config/admin_emails non trovato");
                            callback.onResult(false);
                        }
                    } else {
                        android.util.Log.e("AUTH", "Errore nel controllo email admin", task.getException());
                        callback.onResult(false);
                    }
                });
    }
}