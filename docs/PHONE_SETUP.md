# Android phone setup — free workflow

## Build the app
1. Download and extract the SPLED source ZIP.
2. Create a free GitHub repository and upload the extracted project files, including `.github/workflows/android-build.yml`.
3. Open **Actions** and run **SPLED INTELLIGENCE validation and Android build**.
4. Wait for both the Python tests and Android build jobs to pass.
5. Download `spled-intelligence-0.8.0-debug-apk`, extract `app-debug.apk`, and install it privately on Android.
6. Open the app, allow internet access, keep it open, and wait for initial symbol/candle warm-up.

## Train the model using free Colab from a phone
1. Open Google Colab in your browser and upload `SPLED_Colab_Train.ipynb` from `backend/research/notebooks/`.
2. Run all cells. When prompted, upload the SPLED source ZIP.
3. The notebook downloads public Binance history, labels seven-strategy candidates, trains/calibrates the model, evaluates a chronological holdout and runs tests.
4. Download the returned `SPLED_INTELLIGENCE_v0.8.0_trained_shadow_candidate.zip`.
5. Upload that updated source to GitHub and run the Android build workflow again.
6. The app will display a shadow-model estimate only if the historical screening gate passes. If it fails, the model stays disabled; do not manually change the flag.

No Binance API key, paid AI API, paid hosting or cloud server is needed. Colab's free availability/compute quotas can vary.

## Safety
The app never places orders. A historical model gate pass is not real-money approval. Review model metrics, verify Python/Android feature parity, and complete forward shadow testing before considering manual live trades. The scanner stops when Android kills/stops the app, the phone is off or connectivity is lost.
