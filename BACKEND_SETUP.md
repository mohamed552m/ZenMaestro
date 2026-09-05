# ZenMaestro backend connection

The Android UI continues to work from its local user-specific store when the backend is offline.
When the FastAPI service has been deployed over HTTPS, add this line to the existing untracked
`local.properties` file:

```properties
ZENMAESTRO_API_BASE_URL=https://your-api-host.example
```

Rebuild the app after changing the URL. Task changes are then queued with WorkManager and sent
using the signed-in user's Firebase ID token. No URL, API key, or Firebase service-account file is
committed to the Android repository.
