# GeoStamp Cam
Takes a photo and prints location, coordinates and date/time onto the image (and writes GPS EXIF).

## Build
- Android Studio: File > Open this folder > Run (let it sync/create the Gradle wrapper).
- No Android Studio: push to GitHub; the Actions workflow builds `app-debug.apk` (Actions tab > Artifacts).

Address text uses Android's built-in Geocoder (needs internet; offline it prints coordinates only).
Photos save to Pictures/GeoStampCam.
