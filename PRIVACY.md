# Privacy

Rescale processes images only on your device.

- **No network access.** The app doesn't request the `INTERNET` permission, so Android won't let it connect anywhere. (Optional model downloads are planned. If they arrive, they will only happen when you tap Download, and an offline build without `INTERNET` will remain available.)
- **No analytics, telemetry, ads, or crash reporting.**
- **Permissions:**
  - Images are picked with the system Photo Picker, so no storage-read permission is needed.
  - `POST_NOTIFICATIONS` (Android 13+) shows job progress. It's requested the first time you start a job.
  - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` keep a long upscale running while the app is in the background.
  - `WRITE_EXTERNAL_STORAGE` is requested only on Android 8–9, to save into `Pictures/Rescale`.
- **Files:** the image you pick is copied into the app's private storage so the background job can read it. Results stay in private storage until you save or share them. Both are deleted when you start a new image.
- **Metadata:** output is PNG with no EXIF, so location and camera data are not carried over.
