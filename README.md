# GeoStamp Cam
Full-screen camera that prints a GPS Map Camera-style stamp (satellite thumbnail, place, address,
coordinates, date/time, app-name chip) onto the photo and writes GPS EXIF.

- Photos are stored privately in the app (NOT in the phone Gallery). Open them with the thumbnail
  button next to the shutter; Share/Delete inside the viewer.
- Tap the info box on the camera screen to correct the heading/pincode by hand.
- Change the top-right label: `APP_LABEL` in StampPainter.kt.

## Build
Push to GitHub; the Actions workflow builds `app-debug.apk` (Actions > run > Artifacts).
Or open in Android Studio and press Run.
