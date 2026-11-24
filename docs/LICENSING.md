# Selling Pocket Guardian: 7-day trial, one-time payment

## What the buyer experiences
1. First launch starts a **7-day free trial** with every feature.
2. After 7 days the **guardian side locks**: changing settings and contacts, the screen-off shortcut, and app updates. The unlock screen (Guardian settings > Unlock) shows:
   - a **device code** (like `A1B2-C3D4`, derived from the phone, no personal data),
   - your **payment QR** (InstaPay, QR only, no name),
   - a **Send proof on Messenger** button (opens `https://m.me/RwinSoliman`),
   - a box to paste the **activation code**.
3. The buyer pays, sends the proof and their device code on Messenger, you reply with the activation code, they paste it and tap **Activate**. Unlocked for good on that phone.

## What never locks (safety rule)
The child's **SOS button** and the **volume shortcut with the settings already saved** keep working after the trial, with or without payment. A child should never lose emergency help because a payment wasn't made. Only guardian-side features lock. To change this you would edit `LICENSED_SCREENS` in `MainActivity.kt` and the service check in `ShortcutService.kt`, but this is not recommended.

## Making an activation code (you, the seller)
Use **either**:
- **Android:** install `PG-KeyMaker.apk` (saved in `Documents\PocketGuardian-signing`) on **your own phone only**. First run: paste the contents of `license_private.b64`. Then for each sale: paste the buyer's device code, tap **Make activation code**, then Copy or Share.
- **Computer:** `java tools/LicenseGen.java <path>\license_private.b64 A1B2-C3D4`

Both produce the same code. A code works only for the device code it was made for.

## Keep these safe
- `license_private.b64` is your signing key. Anyone who has it can create free activation codes. **Never** commit it, email it, or put it on customers' phones. Back it up (password manager or USB). If it is lost you cannot make codes that match the app already in customers' hands.
- `PG-KeyMaker.apk` holds that key once set up. Don't publish it or attach it to a GitHub release (the release workflow only builds the main app, on purpose).
- The public half of the key is inside the app (`License.kt`). That is safe: it can only check codes, not make them.

## Setting the price shown in the app
Add `licensePrice=PHP 299` (your amount) to `gradle.properties` and publish a new version. If blank, the screen says to ask for the amount when sending proof.

## Honest limits
- The trial start is stored on the phone. A buyer who clears the app's data or reinstalls restarts the trial. There is no server to prevent it. Setting the clock back does not extend it.
- Payment verification is manual (you check the proof). The code is bound to the device code, so one payment unlocks one phone.
- The device code can change if the phone is factory-reset; the buyer then needs a new code.
- The QR is inside the app, so anyone with the app can see it. That is the intended use.
