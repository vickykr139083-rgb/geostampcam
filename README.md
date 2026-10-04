# GeoStamp Cam
Full-screen camera that prints a GPS Map Camera-style stamp (satellite thumbnail, place, address,
coordinates, date/time, app-name chip) onto the photo and writes GPS EXIF.

- Photos are kept in the app ("My photos" via the thumbnail) and, if the switch on the camera screen is on, also copied to the phone Gallery (Pictures/GeoStampCam).
  Viewer: swipe, To Gallery, Share, Delete. The screen rotates with the phone (landscape supported).
- Tap the info box on the camera screen to correct the heading/pincode by hand.
- Change the top-right label: `APP_LABEL` in StampPainter.kt.

## Build
Push to GitHub; the Actions workflow builds `app-debug.apk` (Actions > run > Artifacts).
Or open in Android Studio and press Run.
