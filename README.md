# Measura Android SDK

Install attribution, deep links, event and revenue tracking for Android, with an offline queue.

```gradle
dependencies {
    implementation 'dev.measura:measura-sdk:0.1.1'
}
```

```kotlin
Measura.init(this, "msr_...")
```

Full guide: https://measura.dev/docs

## Advertising ID

The SDK reads the Google Advertising ID by default and declares the
`com.google.android.gms.permission.AD_ID` permission. It skips the ID when
the user has limited ad tracking. To turn it off, pass
`MeasuraConfig(collectAdvertisingId = false)` and remove the permission with
`tools:node="remove"`. Declare what you collect in your Play Data Safety form.

## Source

This repository mirrors the SDK source published to Maven Central.
Issues and questions: hello@measura.dev

## License

MIT, see [LICENSE](LICENSE).
