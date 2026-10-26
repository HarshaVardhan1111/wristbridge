# WristBridge Phase 1 (Feasibility Spike) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The iPhone pairs with the watch, and one real iPhone notification (app id, title, message) is logged in Logcat.

**Architecture:** A pure-Kotlin included build `:protocol` holds all ANCS byte rules, the per-session ANCS logic and a one-at-a-time GATT queue. It is fully unit-tested. The Wear OS `:app` module is a thin Android shell: it advertises the ANCS solicitation, accepts the iPhone's connection, bonds, subscribes, and feeds bytes into `:protocol`.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.14.3 (wrapper), AGP 8.7.3, JUnit 4.13.2, Jetpack Compose for Wear OS 1.4.0.

**Spec:** `docs/superpowers/specs/2026-10-26-wristbridge-design.md`

## Global Constraints

- Package `com.wristbridge`. `:protocol` packages: `com.wristbridge.protocol.ancs`, `com.wristbridge.protocol.gatt`.
- `minSdk 33`, `targetSdk 34`, `compileSdk 35`. JVM target 17 for all Kotlin code.
- `:protocol` must not depend on Android. It is an included build (`includeBuild("protocol")`) with group `com.wristbridge`, so it builds and tests without the Android SDK: `./gradlew -p protocol test`.
- All ANCS numbers are little endian; all strings are UTF-8 (spec §3). Use the UUIDs and IDs in spec §3 exactly.
- Logcat tag for everything: `WristBridge`.
- Phase 1 requests these attributes for each new notification: AppIdentifier (0), Title (1) max 64, Message (3) max 256.
- Timeouts: Data Source response 5 000 ms; GATT operation 10 000 ms.
- Requested MTU: 185.
- The app never renames the watch's Bluetooth adapter.
- Cloud limit: `dl.google.com` is blocked in the cloud session, so `:app` cannot be built there. `:app` tasks are verified by the user in Android Studio on Windows (`gradlew.bat :app:assembleDebug`).

## Review Focus

1. **A Data Source response split in the middle of a length field or a multi-byte UTF-8 character.** It must still decode correctly → Task 3 test `splitAtEveryIndexGivesSameResult`, `multiByteCharacterSplitAcrossFragments`.
2. **A notification removed while its fetch is waiting or in flight.** No stale fetch, no crash → Task 4 tests `removedWhileQueuedIsNotFetched`, `removedWhileInFlightStillCompletesQueue`.
3. **The iPhone answers a Control Point write with 0xA2** (the notification is already gone). That request is skipped and the next one still runs → Task 4 test `controlPointErrorSkipsToNext`.
4. **Flood of PreExisting notifications after (re)connecting.** None are fetched → Task 4 test `preExistingEventsAreNotFetched`.
5. **A response that never completes, or a GATT callback that never arrives.** Next work still proceeds → Task 4 test `responseTimeoutSkipsToNext`, Task 5 test `timeoutReportsErrorAndClears`.

---

### Task 1: Repo scaffolding + `:protocol` build + ANCS constants + Notification Source parser

**Files:**
- Create: `.gitignore` (standard Android: `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`, `captures/`, `.externalNativeBuild/`, `.cxx/`, `*.apk`, `*.aab`, `.DS_Store`)
- Create: `LICENSE` (MIT, `Copyright (c) 2026 Harsha`)
- Create: `settings.gradle.kts` (root, no `:app` yet), `gradle.properties`, `gradle/libs.versions.toml`, wrapper files
- Create: `protocol/settings.gradle.kts`, `protocol/build.gradle.kts`
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/AncsUuids.kt`
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/AncsIds.kt`
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/NotificationSourceEvent.kt`
- Test: `protocol/src/test/kotlin/com/wristbridge/protocol/ancs/NotificationSourceEventTest.kt`
- Test helper: `protocol/src/test/kotlin/com/wristbridge/protocol/TestBytes.kt` with `fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }` (used by all `:protocol` tests)

**Interfaces:**
- Produces:
  - `object AncsUuids { val SERVICE: UUID; val NOTIFICATION_SOURCE: UUID; val CONTROL_POINT: UUID; val DATA_SOURCE: UUID; val GATT_SERVICE: UUID /*0x1801*/; val SERVICE_CHANGED: UUID /*0x2A05*/; val CCCD: UUID /*0x2902*/ }` (`java.util.UUID`)
  - `object AncsIds` with `const val`s: `CATEGORY_OTHER=0, CATEGORY_INCOMING_CALL=1, CATEGORY_MISSED_CALL=2, CATEGORY_VOICEMAIL=3, CATEGORY_SOCIAL=4, CATEGORY_SCHEDULE=5, CATEGORY_EMAIL=6, CATEGORY_NEWS=7, CATEGORY_HEALTH_AND_FITNESS=8, CATEGORY_BUSINESS_AND_FINANCE=9, CATEGORY_LOCATION=10, CATEGORY_ENTERTAINMENT=11`, `FLAG_SILENT=1, FLAG_IMPORTANT=2, FLAG_PRE_EXISTING=4, FLAG_POSITIVE_ACTION=8, FLAG_NEGATIVE_ACTION=16`, `COMMAND_GET_NOTIFICATION_ATTRIBUTES=0`, `ATTR_APP_IDENTIFIER=0, ATTR_TITLE=1, ATTR_SUBTITLE=2, ATTR_MESSAGE=3, ATTR_MESSAGE_SIZE=4, ATTR_DATE=5, ATTR_POSITIVE_ACTION_LABEL=6, ATTR_NEGATIVE_ACTION_LABEL=7`, `ERROR_UNKNOWN_COMMAND=0xA0, ERROR_INVALID_COMMAND=0xA1, ERROR_INVALID_PARAMETER=0xA2, ERROR_ACTION_FAILED=0xA3`
  - `enum class EventId(val value: Int) { ADDED(0), MODIFIED(1), REMOVED(2) }`
  - `data class NotificationSourceEvent(val eventId: EventId, val flags: Int, val categoryId: Int, val categoryCount: Int, val uid: Long)` with `val isSilent/isImportant/isPreExisting/hasPositiveAction/hasNegativeAction: Boolean` and `companion fun parse(bytes: ByteArray): NotificationSourceEvent?`. `uid` is the unsigned 32-bit value (0..4294967295).

- [ ] **Step 1: Create repo files and the `:protocol` build**

`gradle/libs.versions.toml` (exact):
```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
junit = "4.13.2"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
wearCompose = "1.4.0"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
wear-compose-material = { group = "androidx.wear.compose", name = "compose-material", version.ref = "wearCompose" }
wear-compose-foundation = { group = "androidx.wear.compose", name = "compose-foundation", version.ref = "wearCompose" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```
Root `settings.gradle.kts`: `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, `dependencyResolutionManagement { repositoriesMode.set(FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }`, `rootProject.name = "WristBridge"`, `includeBuild("protocol")`.
`protocol/settings.gradle.kts`: plugin repos `gradlePluginPortal(); mavenCentral()`; dependency repo `mavenCentral()`; `versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`; `rootProject.name = "protocol"`.
`protocol/build.gradle.kts`: `alias(libs.plugins.kotlin.jvm)`, `group = "com.wristbridge"`, Java/Kotlin target 17 (set `java.sourceCompatibility`/`targetCompatibility` and `kotlin.compilerOptions.jvmTarget`; no toolchain download), `testImplementation(libs.junit)`.
`gradle.properties`: `org.gradle.jvmargs=-Xmx2048m`, `android.useAndroidX=true`, `kotlin.code.style=official`.
Wrapper: `gradle wrapper --gradle-version 8.14.3` from the repo root.

- [ ] **Step 2: Write the failing tests**

```kotlin
class NotificationSourceEventTest {
    @Test fun parsesIncomingCallWithActions() {
        val e = NotificationSourceEvent.parse(bytes(0x00, 0x18, 0x01, 0x01, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(EventId.ADDED, e.eventId)
        assertEquals(AncsIds.CATEGORY_INCOMING_CALL, e.categoryId)
        assertEquals(1, e.categoryCount)
        assertEquals(0x12345678L, e.uid)
        assertTrue(e.hasPositiveAction); assertTrue(e.hasNegativeAction)
        assertFalse(e.isPreExisting); assertFalse(e.isSilent); assertFalse(e.isImportant)
    }
    @Test fun uidIsUnsigned() {
        assertEquals(4294967295L, NotificationSourceEvent.parse(bytes(0x02, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF))!!.uid)
    }
    @Test fun preExistingFlag() {
        assertTrue(NotificationSourceEvent.parse(bytes(0x00, 0x04, 0x04, 0x02, 1, 0, 0, 0))!!.isPreExisting)
    }
    @Test fun tooShortReturnsNull() { assertNull(NotificationSourceEvent.parse(bytes(0x00, 0x00, 0x00, 0x00, 1, 0, 0))) }
    @Test fun reservedEventIdReturnsNull() { assertNull(NotificationSourceEvent.parse(bytes(0x03, 0, 0, 0, 1, 0, 0, 0))) }
    @Test fun uuidsMatchAppleSpec() {
        assertEquals(UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0"), AncsUuids.SERVICE)
        assertEquals(UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD"), AncsUuids.NOTIFICATION_SOURCE)
        assertEquals(UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9"), AncsUuids.CONTROL_POINT)
        assertEquals(UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB"), AncsUuids.DATA_SOURCE)
    }
}
```
Packets longer than 8 bytes parse the first 8 (no test needed; parse must not throw).

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew -p protocol test`
Expected: compilation FAIL (`Unresolved reference: NotificationSourceEvent`).

- [ ] **Step 4: Implement `AncsUuids`, `AncsIds`, `EventId`, `NotificationSourceEvent.parse`**

Bluetooth base UUID for 16-bit values: `0000xxxx-0000-1000-8000-00805F9B34FB`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew -p protocol test`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add .gitignore LICENSE settings.gradle.kts gradle.properties gradle gradlew gradlew.bat protocol
git commit -m "Add protocol module with ANCS constants and Notification Source parser"
```

---

### Task 2: Get Notification Attributes command builder

**Files:**
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/ControlPointCommands.kt`
- Test: `protocol/src/test/kotlin/com/wristbridge/protocol/ancs/ControlPointCommandsTest.kt`

**Interfaces:**
- Consumes: `AncsIds` (Task 1).
- Produces:
  - `data class AttributeRequest(val id: Int, val maxLength: Int? = null)`
  - `object ControlPointCommands { fun getNotificationAttributes(uid: Long, attributes: List<AttributeRequest>): ByteArray }`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun buildsPhase1Request() {
    val cmd = ControlPointCommands.getNotificationAttributes(0x12345678L, listOf(
        AttributeRequest(AncsIds.ATTR_APP_IDENTIFIER),
        AttributeRequest(AncsIds.ATTR_TITLE, 64),
        AttributeRequest(AncsIds.ATTR_MESSAGE, 256)))
    assertArrayEquals(bytes(0x00, 0x78, 0x56, 0x34, 0x12, 0x00, 0x01, 0x40, 0x00, 0x03, 0x00, 0x01), cmd)
}
@Test(expected = IllegalArgumentException::class) fun titleWithoutMaxLengthIsRejected() {
    ControlPointCommands.getNotificationAttributes(1, listOf(AttributeRequest(AncsIds.ATTR_TITLE)))
}
@Test(expected = IllegalArgumentException::class) fun maxLengthOnAppIdentifierIsRejected() {
    ControlPointCommands.getNotificationAttributes(1, listOf(AttributeRequest(AncsIds.ATTR_APP_IDENTIFIER, 10)))
}
```

- [ ] **Step 2: Run to verify fail** — `./gradlew -p protocol test` → compilation FAIL.
- [ ] **Step 3: Implement.** Max length is required for Title, Subtitle, Message (ids 1, 2, 3) and forbidden for the others; write it as 2 bytes little endian.
- [ ] **Step 4: Run to verify pass** — `./gradlew -p protocol test` → all tests pass.
- [ ] **Step 5: Commit** — `git commit -m "Add ANCS Get Notification Attributes command builder"`

---

### Task 3: Data Source response assembler

**Files:**
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/DataSourceAssembler.kt`
- Test: `protocol/src/test/kotlin/com/wristbridge/protocol/ancs/DataSourceAssemblerTest.kt`

**Interfaces:**
- Consumes: `AncsIds` (Task 1).
- Produces:
  - `data class NotificationAttributes(val uid: Long, val values: Map<Int, String>)` (key = attribute id)
  - `class DataSourceAssembler { fun begin(uid: Long, attributeIds: List<Int>); fun append(fragment: ByteArray): NotificationAttributes?; fun reset() }`

Behaviour: `begin` sets the expected UID and attribute ids (in request order) and clears the buffer. `append` adds bytes to the buffer and returns the result once every requested `AttrID Length Value` tuple is complete, then clears the expectation. It returns null while incomplete, when nothing is expected, or after completion. When the buffer has ≥5 bytes and the header is not `0x00 + expected UID`, it discards the buffer but **keeps** the expectation (late bytes from an old request must not kill the new one). Strings are decoded from bytes only at the end (UTF-8).

- [ ] **Step 1: Write the failing tests**

```kotlin
// 00 | 78 56 34 12 | 00 05 00 "com.a" | 01 02 00 "Hi" | 03 00 00
private val full = bytes(0x00, 0x78, 0x56, 0x34, 0x12) + bytes(0x00, 0x05, 0x00) + "com.a".toByteArray() +
    bytes(0x01, 0x02, 0x00) + "Hi".toByteArray() + bytes(0x03, 0x00, 0x00)
private val ids = listOf(AncsIds.ATTR_APP_IDENTIFIER, AncsIds.ATTR_TITLE, AncsIds.ATTR_MESSAGE)
private val expected = NotificationAttributes(0x12345678L, mapOf(0 to "com.a", 1 to "Hi", 3 to ""))

@Test fun singleFragment() { val a = DataSourceAssembler(); a.begin(0x12345678L, ids); assertEquals(expected, a.append(full)) }
@Test fun splitAtEveryIndexGivesSameResult() {
    for (i in 1 until full.size) {
        val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
        assertNull("split at $i", a.append(full.copyOfRange(0, i)))
        assertEquals("split at $i", expected, a.append(full.copyOfRange(i, full.size)))
    }
}
@Test fun multiByteCharacterSplitAcrossFragments() {
    val title = "Hé😀".toByteArray()          // 1 + 2 + 4 bytes
    val msg = bytes(0x00, 1, 0, 0, 0, 0x01, title.size, 0x00) + title
    val a = DataSourceAssembler(); a.begin(1, listOf(AncsIds.ATTR_TITLE))
    assertNull(a.append(msg.copyOfRange(0, 12)))   // header is 8 bytes; "H"=8, "é"=9..10, "😀"=11..14 → cuts inside "😀"
    assertEquals(NotificationAttributes(1, mapOf(1 to "Hé😀")), a.append(msg.copyOfRange(12, msg.size)))
}
@Test fun nothingExpectedReturnsNull() { assertNull(DataSourceAssembler().append(full)) }
@Test fun otherUidIsIgnoredAndCorrectResponseStillParses() {
    val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
    assertNull(a.append(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)))
    assertEquals(expected, a.append(full))
}
@Test fun resetDiscardsPartial() {
    val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
    a.append(full.copyOfRange(0, 9)); a.reset()
    assertNull(a.append(full.copyOfRange(9, full.size)))
}
@Test fun bytesAfterCompletionAreIgnored() {
    val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
    assertEquals(expected, a.append(full + bytes(0xAA)))
    assertNull(a.append(bytes(0xBB)))
}
```

- [ ] **Step 2: Run to verify fail** — `./gradlew -p protocol test` → compilation FAIL.
- [ ] **Step 3: Implement `DataSourceAssembler`.**
- [ ] **Step 4: Run to verify pass** — all tests pass.
- [ ] **Step 5: Commit** — `git commit -m "Add ANCS Data Source response assembler"`

---

### Task 4: ANCS session logic

**Files:**
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/ancs/AncsSession.kt`
- Test: `protocol/src/test/kotlin/com/wristbridge/protocol/ancs/AncsSessionTest.kt`

**Interfaces:**
- Consumes: `NotificationSourceEvent`, `EventId` (Task 1); `ControlPointCommands`, `AttributeRequest` (Task 2); `DataSourceAssembler`, `NotificationAttributes` (Task 3).
- Produces:
```kotlin
class AncsSession(private val io: Io) {
    interface Io {
        fun writeControlPoint(command: ByteArray)
        fun startResponseTimer()   // caller calls onResponseTimeout() after 5 000 ms
        fun cancelResponseTimer()
        fun onEvent(event: NotificationSourceEvent)                 // every parsed event, for logging
        fun onNotification(event: NotificationSourceEvent, attributes: NotificationAttributes)
        fun onRequestDropped(uid: Long, reason: String)            // "error 0xA2", "timeout"
    }
    fun onNotificationSource(bytes: ByteArray)
    fun onDataSource(bytes: ByteArray)
    fun onControlPointWriteFailed(status: Int)
    fun onResponseTimeout()
    fun end()                  // session over: clear queue, assembler, in-flight, cancel timer
}
```

Rules: fetch only `ADDED` events without PreExisting. Queue UIDs FIFO; one request in flight. Request = Phase 1 attributes (Global Constraints). On complete response → `cancelResponseTimer`, `onNotification` with the original event, start the next. `REMOVED` drops the UID from the queue (if in flight, let it finish or fail normally). Write failure or timeout → `onRequestDropped`, then next. Unparseable Notification Source bytes are ignored.

- [ ] **Step 1: Write the failing tests** (use a `FakeIo` recording calls in lists)

```kotlin
@Test fun newNotificationIsFetchedAndDelivered() {
    s.onNotificationSource(event(ADDED, flags = 0, uid = 0x12345678))
    assertArrayEquals(phase1RequestFor(0x12345678), io.writes.single())
    assertEquals(1, io.timerStarts)
    s.onDataSource(responseFor(0x12345678))
    assertEquals("Hi", io.notifications.single().second.values[AncsIds.ATTR_TITLE])
    assertEquals(1, io.timerCancels)
}
@Test fun preExistingEventsAreNotFetched() {
    repeat(50) { s.onNotificationSource(event(ADDED, flags = AncsIds.FLAG_PRE_EXISTING, uid = it.toLong())) }
    assertTrue(io.writes.isEmpty()); assertEquals(50, io.events.size)
}
@Test fun onlyOneRequestInFlight() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    assertEquals(1, io.writes.size)
    s.onDataSource(responseFor(1))
    assertEquals(2, io.writes.size); assertArrayEquals(phase1RequestFor(2), io.writes[1])
}
@Test fun removedWhileQueuedIsNotFetched() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    s.onNotificationSource(event(REMOVED, uid = 2))
    s.onDataSource(responseFor(1))
    assertEquals(1, io.writes.size)
}
@Test fun removedWhileInFlightStillCompletesQueue() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    s.onNotificationSource(event(REMOVED, uid = 1))
    s.onControlPointWriteFailed(AncsIds.ERROR_INVALID_PARAMETER)
    assertArrayEquals(phase1RequestFor(2), io.writes.last())
}
@Test fun controlPointErrorSkipsToNext() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    s.onControlPointWriteFailed(0xA2)
    assertEquals(1L to "error 0xA2", io.dropped.single())
    assertArrayEquals(phase1RequestFor(2), io.writes.last())
}
@Test fun responseTimeoutSkipsToNext() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    s.onDataSource(responseFor(1).copyOfRange(0, 6))
    s.onResponseTimeout()
    assertEquals(1L to "timeout", io.dropped.single())
    s.onDataSource(responseFor(2))
    assertEquals(2L, io.notifications.single().second.uid)
}
@Test fun endClearsEverything() {
    s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
    s.end()
    s.onDataSource(responseFor(1))
    assertTrue(io.notifications.isEmpty()); assertEquals(1, io.writes.size); assertEquals(1, io.timerCancels)
}
@Test fun garbageNotificationSourceIsIgnored() { s.onNotificationSource(bytes(0x01)); assertTrue(io.events.isEmpty()) }
```
Helpers in the test file: `event(id, flags = 0, uid)` builds the 8 bytes, `phase1RequestFor(uid)` calls `ControlPointCommands.getNotificationAttributes` with the Phase 1 list, `responseFor(uid)` builds a complete response with app id `"com.a"`, title `"Hi"`, empty message (same layout as Task 3's `full`).

- [ ] **Step 2: Run to verify fail** — `./gradlew -p protocol test` → compilation FAIL.
- [ ] **Step 3: Implement `AncsSession`.** Put the Phase 1 attribute list in `AncsSession.PHASE1_ATTRIBUTES`.
- [ ] **Step 4: Run to verify pass** — all tests pass.
- [ ] **Step 5: Commit** — `git commit -m "Add ANCS session logic with queueing, errors and timeouts"`

---

### Task 5: GATT operation queue

**Files:**
- Create: `protocol/src/main/kotlin/com/wristbridge/protocol/gatt/GattQueue.kt`
- Test: `protocol/src/test/kotlin/com/wristbridge/protocol/gatt/GattQueueTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun interface Cancellable { fun cancel() }
fun interface Scheduler { fun schedule(delayMs: Long, block: () -> Unit): Cancellable }
class GattQueue(
    private val scheduler: Scheduler,
    private val timeoutMs: Long = 10_000,
    private val onError: (operation: String, reason: String) -> Unit,
) {
    fun enqueue(name: String, start: () -> Boolean)
    fun complete()   // call from the GATT callback of the current operation
    fun clear()
}
```
Rules: FIFO, one running. `start()` returning false → `onError(name, "start failed")` and `clear()`. Timeout → `onError(name, "timeout")` and `clear()`. `complete()` cancels the timer and starts the next; ignored when nothing runs. Not thread-safe: the app calls it only on the main thread.

- [ ] **Step 1: Write the failing tests** (a `FakeScheduler` that stores tasks and runs them on `advance(ms)`)

```kotlin
@Test fun runsOneAtATime() { q.enqueue("a") { started += "a"; true }; q.enqueue("b") { started += "b"; true }
    assertEquals(listOf("a"), started); q.complete(); assertEquals(listOf("a", "b"), started) }
@Test fun startFailureReportsErrorAndClears() {
    q.enqueue("a") { started += "a"; true }; q.enqueue("b") { false }; q.enqueue("c") { started += "c"; true }
    q.complete()                                   // "b" starts and fails
    assertEquals("b" to "start failed", errors.single()); assertEquals(listOf("a"), started) }
@Test fun timeoutReportsErrorAndClears() { q.enqueue("a") { true }; q.enqueue("b") { started += "b"; true }
    sched.advance(10_000); assertEquals("a" to "timeout", errors.single()); assertTrue(started.isEmpty()) }
@Test fun completeCancelsTimeout() { q.enqueue("a") { true }; q.complete(); sched.advance(20_000); assertTrue(errors.isEmpty()) }
@Test fun completeWithNothingRunningIsIgnored() { q.complete(); q.enqueue("a") { started += "a"; true }; assertEquals(listOf("a"), started) }
@Test fun worksAgainAfterClear() { q.enqueue("a") { true }; q.clear(); q.enqueue("b") { started += "b"; true }; assertEquals(listOf("b"), started) }
```

- [ ] **Step 2: Run to verify fail** — compilation FAIL.
- [ ] **Step 3: Implement `GattQueue`.**
- [ ] **Step 4: Run to verify pass** — all tests pass.
- [ ] **Step 5: Commit** — `git commit -m "Add one-at-a-time GATT operation queue"`

---

### Task 6: Wear OS app shell — permissions screen and foreground service

**Files:**
- Modify: `settings.gradle.kts` (add `include(":app")`)
- Create: `build.gradle.kts` (root: the four plugins with `apply false`)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/wristbridge/MainActivity.kt`
- Create: `app/src/main/kotlin/com/wristbridge/service/BridgeService.kt`
- Create: `app/src/main/res/values/strings.xml` (`app_name` = `WristBridge`), launcher icon via a simple vector drawable `ic_launcher`

**Interfaces:**
- Consumes: `com.wristbridge:protocol` (composite build substitution).
- Produces: `BridgeService.start(context: Context)` and `BridgeService.stop(context: Context)` (companion functions). `BridgeService` creates the `IphoneLink` in Task 7.

- [ ] **Step 1: Gradle.** `app/build.gradle.kts`: plugins `android-application`, `kotlin-android`, `kotlin-compose`; `namespace = "com.wristbridge"`, `applicationId = "com.wristbridge"`, `compileSdk = 35`, `minSdk = 33`, `targetSdk = 34`, `versionCode = 1`, `versionName = "0.1.0"`, Java/Kotlin 17, `buildFeatures.compose = true`. Dependencies: `implementation("com.wristbridge:protocol")`, core-ktx, activity-compose, wear compose material + foundation.
- [ ] **Step 2: Manifest.** Permissions: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`. `<uses-feature android:name="android.hardware.type.watch"/>`, `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`. `<meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>`. Service `.service.BridgeService`, `exported=false`, `foregroundServiceType="connectedDevice"`. Launcher activity `.MainActivity`.
- [ ] **Step 3: `MainActivity`.** Compose screen (Wear `MaterialTheme`, centred column): status text and one `Chip`/`Button`. If any of the 4 runtime permissions are missing, the button says "Grant permissions" and launches `RequestMultiplePermissions`. Otherwise it says "Start" and calls `BridgeService.start(this)`. Log `"Permissions granted"` / `"Permissions missing: <list>"`.
- [ ] **Step 4: `BridgeService`.** On create: create a notification channel `bridge` (importance LOW), call `startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` with text "Waiting for iPhone". Log `"Service started"`. `START_STICKY`. On destroy: log `"Service stopped"`.
- [ ] **Step 5: Verify (user, on Windows).** Open the project in Android Studio, let Gradle sync, then run `gradlew.bat :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`. In the cloud, run `./gradlew -p protocol test` again and confirm it still passes. (`:app` can't be built in the cloud.)
- [ ] **Step 6: Commit** — `git commit -m "Add Wear OS app shell with permissions and foreground service"`

---

### Task 7: BLE link — advertise, accept, bond, subscribe, log

**Files:**
- Create: `app/src/main/kotlin/com/wristbridge/ble/Advertiser.kt`
- Create: `app/src/main/kotlin/com/wristbridge/ble/GattServer.kt`
- Create: `app/src/main/kotlin/com/wristbridge/ble/IphoneLink.kt`
- Create: `app/src/main/kotlin/com/wristbridge/ble/HandlerScheduler.kt`
- Modify: `app/src/main/kotlin/com/wristbridge/service/BridgeService.kt` (create, start and stop `IphoneLink`)

**Interfaces:**
- Consumes: `AncsUuids`, `AncsSession` (Tasks 1, 4); `GattQueue`, `Scheduler`, `Cancellable` (Task 5).
- Produces:
  - `class Advertiser(adapter: BluetoothAdapter) { enum class Mode { PAIRING, BACKGROUND }; fun start(mode: Mode, onResult: (Boolean) -> Unit); fun stop() }`
  - `class GattServer(context: Context, manager: BluetoothManager, onConnectionChange: (BluetoothDevice, Boolean) -> Unit) { fun open(): Boolean; fun close() }`
  - `class HandlerScheduler(handler: Handler) : Scheduler`
  - `class IphoneLink(context: Context) { enum class State { IDLE, ADVERTISING, CONNECTED, BONDING, DISCOVERING, SUBSCRIBED, RECONNECTING }; val state: State; fun start(); fun stop() }`

- [ ] **Step 1: `Advertiser`.** `AdvertiseSettings`: connectable, timeout 0; `PAIRING` = `ADVERTISE_MODE_BALANCED` + `ADVERTISE_TX_POWER_MEDIUM`; `BACKGROUND` = `ADVERTISE_MODE_LOW_POWER` + `ADVERTISE_TX_POWER_LOW`. Data: `setIncludeDeviceName(false)`, `addServiceSolicitationUuid(ParcelUuid(AncsUuids.SERVICE))`. Scan response: `setIncludeDeviceName(true)`. If `adapter.bluetoothLeAdvertiser` is null, call `onResult(false)`. Logs: `"Advertising started (<mode>)"` / `"Advertising failed: <errorCode>"`.
- [ ] **Step 2: `GattServer`.** Opens an empty `BluetoothGattServer`. Forwards `onConnectionStateChange` → `onConnectionChange(device, newState == STATE_CONNECTED)`.
- [ ] **Step 3: `IphoneLink`.** Everything runs on the main-thread `Handler`; every GATT and broadcast callback posts to it first. The flow is spec §5 "First pairing" steps 2–9:
  1. `start()`: register receivers for `ACTION_BOND_STATE_CHANGED` and `BluetoothAdapter.ACTION_STATE_CHANGED`, open `GattServer`, start `Advertiser(PAIRING)` → `ADVERTISING`.
  2. Server connect → stop advertising, log `"iPhone connected: <address>"`, `CONNECTED`, `device.connectGatt(context, false, callback, TRANSPORT_LE)`.
  3. On client connected: if `bondState == BOND_NONE` → `createBond()`, `BONDING`. If `BOND_BONDING` → wait. If `BOND_BONDED` → step 4. On the `BONDED` broadcast → log `"Bonded"`, step 4.
  4. `DISCOVERING`. Queue `requestMtu(185)` (log `"MTU <n>"`), then `discoverServices()`. If the ANCS service is missing → log `"ANCS not found"` and wait for Service Changed.
  5. Queue CCCD writes, in this order: Service Changed (indicate, `ENABLE_INDICATION_VALUE`, skip if absent), Data Source (`ENABLE_NOTIFICATION_VALUE`), Notification Source. Call `setCharacteristicNotification(char, true)` before each write. When all three are done → `SUBSCRIBED`, log `"ANCS subscribed"`.
  6. `onCharacteristicChanged`: Notification Source → `session.onNotificationSource`; Data Source → `session.onDataSource`; Service Changed → `session.end()` and rediscover (back to step 4).
  7. `AncsSession.Io`: `writeControlPoint` queues a `WRITE_TYPE_DEFAULT` write. In `onCharacteristicWrite` for the Control Point, `status != GATT_SUCCESS` → `session.onControlPointWriteFailed(status)`. The timer methods use `handler.postDelayed(5_000)`. `onEvent` logs `"Event <eventId> uid=<uid> category=<categoryId> flags=0x<hex>"`. `onNotification` logs `"Notification: app=<0> title=<1> message=<3>"`. `onRequestDropped` logs `"Dropped uid=<uid>: <reason>"`.
  8. Use the API 33 callback overloads (`onCharacteristicChanged(gatt, characteristic, value)`, `writeCharacteristic(char, value, writeType)`, `writeDescriptor(desc, value)`). Every GATT callback calls `queue.complete()`.
  9. Disconnect, a non-success connection status (including 133), or a `GattQueue` error → log `"Disconnected (status <s>)"` or `"GATT error <op>: <reason>"`, `session.end()`, `queue.clear()`, `gatt.close()`, `RECONNECTING`, then restart advertising after a backoff of 2 s, doubling up to 300 s. Reset the backoff to 2 s on `SUBSCRIBED`.
  10. Bluetooth turned OFF → tear down like step 9 but don't restart. Turned ON → `start()` again.
  11. `stop()`: unregister receivers, stop advertising, close gatt and server, `IDLE`.
- [ ] **Step 4: Wire into `BridgeService`.** `onCreate` → `IphoneLink(this).start()`, `onDestroy` → `stop()`.
- [ ] **Step 5: Verify.** Cloud: `./gradlew -p protocol test` passes. User: `gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`. Real test on the watch → Task 8 checklist.
- [ ] **Step 6: Commit** — `git commit -m "Add BLE link: ANCS solicitation advertising, bonding and subscription"`

---

### Task 8: README, ADB-over-Wi-Fi guide, Phase 1 test checklist

**Files:**
- Create: `README.md`
- Create: `docs/INSTALL-ADB-WIFI.md`
- Create: `docs/PHASE1-TEST.md`

- [ ] **Step 1: `README.md`.** What WristBridge is (one paragraph). No iPhone app. Phase status table (Phase 1 "ready to test", 2–5 "not started"). Known limits: no CTS clock sync (reason), no message replies, the watch shows its own Bluetooth name. How to build: Android Studio, or `gradlew.bat :app:assembleDebug`. How to test protocol: `gradlew.bat -p protocol test`. MIT license line.
- [ ] **Step 2: `docs/INSTALL-ADB-WIFI.md`** (Windows, simple English, numbered steps):
  1. Watch and PC on the same Wi-Fi.
  2. Developer mode: Settings > About watch > Software information > tap **Software version** 7 times.
  3. Settings > Developer options: turn on **ADB debugging** and **Wireless debugging**.
  4. Wireless debugging > **Pair new device**: note the IP:port and the 6-digit code.
  5. PC: `adb pair <ip>:<pair-port>` and type the code. (`adb` is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`.)
  6. `adb connect <ip>:<connect-port>`. The connect port is the one on the main Wireless debugging screen, which is different from the pair port. Check with `adb devices`.
  7. Install: Android Studio Run ▶ with the watch selected, or `gradlew.bat :app:installDebug`.
  8. Logs: `adb logcat -s WristBridge`.
  9. Troubleshooting: the port changes after a reboot or Wi-Fi change (connect again); Wi-Fi turns off to save battery (keep the watch on its charger while testing); "unauthorized" (accept the prompt on the watch).
- [ ] **Step 3: `docs/PHASE1-TEST.md`.**
  1. Remove Merge: uninstall Merge from the watch. On the iPhone: Settings > Bluetooth > (i) next to the watch > **Forget This Device**. Also delete the Merge iPhone app, so it can't reconnect.
  2. Install WristBridge (see the ADB guide). Open it, tap **Grant permissions**, then **Start**.
  3. iPhone: Settings > Bluetooth. Tap the watch's name under Other Devices. Accept **Pair**, then **Allow notifications**. Accept the pairing prompt on the watch if one appears.
  4. Send yourself a message (for example from another phone, or a Reminder due in 1 minute).
  5. Pass = Logcat shows these lines in order: `Service started`, `Advertising started (PAIRING)`, `iPhone connected: …`, `Bonded`, `MTU …`, `ANCS subscribed`, `Notification: app=… title=… message=…`.
  6. If it fails, send the full `adb logcat -s WristBridge` output. What each failure means: no `Advertising started` → Samsung blocks advertising (stop: report it); the watch isn't in the iPhone list → try nRF Connect on the iPhone (Scanner > Connect); `Bonded` but no `ANCS subscribed` → collect the log.
  7. Tip: if logs stop when the screen is off, set Settings > Apps > WristBridge > Battery to **Unrestricted**.
- [ ] **Step 4: Commit** — `git commit -m "Add README, ADB over Wi-Fi guide and Phase 1 test checklist"`
