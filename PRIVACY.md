# Privacy

Rescale processes images only on your device.

- **Network only on request.** The app holds the `INTERNET` permission solely to download optional model packs from this project's GitHub releases, and only when you tap Download. Nothing else is ever sent or fetched: no update checks, no telemetry. Packs can also be imported from a file instead.
- **No analytics, telemetry, ads, or crash reporting.**
- **Permissions:**
  - Images are picked with the system Photo Picker, so no storage-read permission is needed.
  - `POST_NOTIFICATIONS` (Android 13+) shows job progress. It's requested the first time you start a job.
  - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` keep a long upscale running while the app is in the background.
  - `WRITE_EXTERNAL_STORAGE` is requested only on Android 8–9, to save into `Pictures/Rescale`.
- **Files:** the image you pick is copied into the app's private storage so the background job can read it. Results stay in private storage until you save or share them. Both are deleted when you start a new image.
- **Metadata:** output is PNG with no EXIF, so location and camera data are not carried over.
