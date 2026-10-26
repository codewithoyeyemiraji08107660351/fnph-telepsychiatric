# Remita: going live

The backend is set up for live Remita. Test and live differ in two settings only.

| Setting | Test | Live |
|---|---|---|
| `REMITA_API_BASE_URL` | `https://api-demo.systemspecsng.com` | The Connect Gateway host in Remita's live credentials email. Host only, no path. |
| `REMITA_SECRET_KEY` | `sk_test_...` | `sk_live_...` from the same email |

Everything else stays the same: the charge and check paths, the callback URL, the amounts (sent in kobo), and the reference (the FNPH reference is the `paymentIdentifier`).

## Until the live keys arrive: payment bypass

Set `REMITA_BYPASS=true` in the backend environment and redeploy. Remita is not called at all, so the demo host being down does not matter.

- Every consultation payment is confirmed at once and the patient goes straight to the calendar.
- A payment left pending from before is confirmed the next time the patient's screen checks it.
- These payments are marked **Bypassed**. They do not count as money collected in Finance reports or the Excel export, and the nightly reconciliation does not ask Remita about them.
- The patient is told no payment is needed. HIM is told the booking went ahead without payment.
- Start-up logs `PAYMENT BYPASS IS ON` as an error so it is not forgotten.
- The backend refuses to start with the bypass on and a live `REMITA_API_BASE_URL`, so live keys cannot go in while it is still on.

To go live: remove `REMITA_BYPASS` (or set it to `false`) in the same change that sets the live host and key.

## Switch to live

1. In the production environment (Dokploy > backend > Environment), set `REMITA_API_BASE_URL` and `REMITA_SECRET_KEY` to the live values.
2. Make sure `REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID` and `REMITA_BYPASS` are not set, or are `false`. The backend refuses to start with it on against a live host.
3. Make sure `REMITA_WEBHOOK_SECRET` is set (`openssl rand -hex 24`) and is not the value from any example file.
4. In the Remita live profile, set the notification (callback) URL to
   `https://<api host>/api/v1/webhooks/remita/<REMITA_WEBHOOK_SECRET>`.
   If the profile has a return (redirect) URL, set it to `https://app.fnphkaduna.cloud/portal/payment/return`. The Android app opens on that address after payment.
5. Redeploy. The start-up log shows `Remita: live mode, host ...` and no `Remita:` errors.

## First live payment (before patients)

Use a staff test patient and the lowest fee Finance agrees to, then refund it.

1. Book up to payment and pay with a real card.
2. Return to the app. The payment should show as confirmed and the calendar should open.
3. If it stays pending, search the backend log for `Remita check for FNPH-`. An error line there carries Remita's exact reply. Send it to the developer: the check reads the transaction state and amount from that reply, and a different shape is fixed in one place (`RemitaClient.parseVerification`).
4. Run a reconciliation for today in Finance. The payment should count as matched.

## What protects patients and the hospital

- **Paid means Remita says paid.** A payment is confirmed only when Remita's check returns a success state and an amount for that exact reference. A reply that cannot be read keeps the payment pending; it is never counted as paid and never marked failed on a guess.
- **Wrong amount goes to Finance.** If the amount differs from the fee, the payment is held as unmatched and Finance is notified.
- **No double charges.** Starting a charge is never retried automatically. Checking a payment is.
- **Lost callbacks are caught.** The patient's return to the app checks the payment, and the 01:00 reconciliation checks every payment from the last day by its reference.
- **The callback cannot be used to make the server call Remita.** A callback with the wrong secret is ignored.
- **The patient's own phone number.** Remita receives the number on the patient's record. A patient with no number is asked to contact the helpdesk.

## Start-up messages

| Message | Meaning |
|---|---|
| `REMITA_API_BASE_URL is not set` / `REMITA_SECRET_KEY is not set` | Payments cannot start. The rest of the service works. |
| `REMITA_API_BASE_URL must be the host only` | The value has a path. Remove everything after the host. |
| `points at Remita's demo host` | Production is still on the demo. No real money moves. |
| `TEST MODE IS ON` | Demo only. Every created charge counts as paid. |
| `REMITA_WEBHOOK_SECRET is missing or shorter than 24 characters` | Callbacks are ignored until it is set. Payments still confirm on return and overnight. |
| `PAYMENT BYPASS IS ON` | Patients book without paying. Turn it off when the live keys arrive. |
| Start-up stops: `REMITA_BYPASS is true while REMITA_API_BASE_URL is a live Remita host` | Remove `REMITA_BYPASS` and redeploy. |
| Start-up stops: `REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID is true while REMITA_API_BASE_URL is a live Remita host` | Remove that setting and redeploy. |
