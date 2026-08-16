# Privacy

Weather Metro does not require an account and contains no advertising or
analytics SDK.

When precise location is enabled, Android provides the device coordinates to
the app so it can resolve a local street/district and choose nearby observation
and tide stations. Coordinates are sent to Open-Meteo to obtain local hourly
estimates. They are not sent to the project owner, Firebase, Apps Script, or the
Hong Kong Observatory. Disabling precise location uses a fixed central Hong
Kong fallback.

The app stores UI preferences and weather/tool caches in private local app
storage. Android backup is disabled. Clearing app storage removes these local
records; the in-app clear-cache command removes weather/tool caches.

When location heavy-rain notifications are enabled together with precise
location, Weather Metro also keeps the most recent successful precise fix in
private local app storage so Android can compare the user's district with HKO
past-hour district rainfall while the app is not open. This record contains the
coordinates, place/district label, accuracy and local update time. The
notification worker will not use a fix older than six hours. This location
record is not uploaded to the project owner, Firebase, Apps Script, or HKO, and
is cleared when precise location is disabled or app storage is cleared.

If notifications are enabled, Firebase Cloud Messaging registers the app
instance and subscribes it to the common `hko_alerts` topic. The server sends the
same HKO warning update to the topic and receives no device location. Firebase
processing is subject to Google's applicable privacy terms. Location heavy-rain
notifications are evaluated locally from HKO public rainfall data and do not
add device location to the FCM topic or Apps Script journal.

Official HKO tools opened from the `tools` Pivot run in the user's external web
browser and are subject to HKO website policies.
