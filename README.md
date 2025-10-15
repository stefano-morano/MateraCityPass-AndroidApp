# Matera City Pass – Android App

> App Android per la gestione del Sistema NFC per il Matera City Pass

## Caratteristiche
- **USB**: discovery + permessi, apertura seriale **115200 8N1**, I/O asincrono, monitor, riconnessione.
- **NFC**: lettura payload, **inizializzazione codice 6 caratteri**, reset, conferma via rilettura.
- **UI**: stato chiaro, log sintetici, passaggio rapido **USB ↔ NFC**.

## Requisiti
- Android **SDK 24+**.
- Dispositivo con **USB Host/OTG** (USB) e **NFC** (NFC).
- Tag **NDEF-capable** (NTAG/MIFARE Ultralight).

## Setup rapido
1. Clona e apri in **Android Studio**.
2. Aggiungi dipendenze (AppCompat, Material, Fragment, libreria **UsbSerial**).
3. Manifest: `NFC` + feature `usb.host`; opzionale `device_filter.xml` (CH340: `0x1A86:0x7523`).
4. Build & Run su device fisico.

## Uso
- **Tab USB**: premi **Connetti**, accetta permesso, usa **Test** e comandi per salvare parametri.
- **Tab NFC**: avvicina il tag per leggere; inserisci **codice 6** → **Inizializza**; **Reset** se previsto.

## Roadmap
- **iOS (pianificata)** con funzioni analoghe (limiti USB possibili).

## Crediti
@Matera City Pass 2025

> Per dettagli tecnici completi, vedi la **documentazione** del progetto.
