# Download Functionality Test Suite

Comprehensive test suite for the Android download functionality, covering all critical bug fixes and scenarios.

## Overview

This test suite validates the background download system including:

- Foreground service with wake locks
- HTTP Range request resume support
- Thread-safe service connection management
- Resource leak prevention
- Lifecycle management
- Error handling

## Test Structure

### Unit Tests

#### 1. `DownloadNotificationServiceTest.kt`

Tests the foreground service that keeps downloads alive:

- ✅ Wake lock timeout (6 hours) to prevent battery drain
- ✅ Notification channel HIGH importance
- ✅ Wake lock and WiFi lock lifecycle management
- ✅ Exception handling during resource release
- ✅ Service continuation when app is swiped away

**Critical Regression Tests:**

- `testWakeLockHasTimeout()` - Prevents infinite battery drain
- `testNotificationChannelImportance()` - Fixes priority mismatch
- `testWakeLockReleasedOnDestroy()` - Prevents resource leaks

#### 2. `DownloadItemManagerTest.kt`

Tests the download queue and service lifecycle manager:

- ✅ Smart storage buffer calculation (100MB or 5% of file size)
- ✅ Thread-safe service connection state
- ✅ Application context validation (isFinishing, isDestroyed)
- ✅ Event-based error handling (no Toast)
- ✅ Proper cleanup on lifecycle events
- ✅ Exception handling for service unbinding

**Critical Regression Tests:**

- `testStorageBufferCalculation()` - Fixes wasteful 10% buffer
- `testServiceNotStartedWhenActivityFinishing()` - Prevents crashes
- `testUnbindServiceIllegalArgumentExceptionHandled()` - Specific exception handling
- `testCleanupClearsQueues()` - Lifecycle cleanup

#### 3. `InternalDownloadManagerTest.kt`

Tests the HTTP download implementation with resume support:

- ✅ Network timeout configuration (not infinite)
- ✅ Read timeout: 5 minutes (detects stalled connections)
- ✅ Call timeout: 6 hours (matches wake lock)
- ✅ Socket configuration with keep-alive
- ✅ Connection pooling and retry logic
- ✅ Resource cleanup

**Critical Regression Tests:**

- `testReadTimeoutNotInfinite()` - Prevents indefinite hangs
- `testCallTimeoutMatchesWakeLock()` - Timeout coordination
- `testCloseDoesNotThrow()` - AutoCloseable contract

#### 4. `AbsDownloaderTest.kt`

Tests the Capacitor plugin interface:

- ✅ Lifecycle cleanup (handleOnDestroy)
- ✅ Event emitter with error callback
- ✅ Component initialization

**Critical Regression Tests:**

- `testHandleOnDestroyCleansUpManager()` - Cleanup integration
- `testEventEmitterHasErrorCallback()` - Error handling

### Integration Tests

#### 5. `DownloadIntegrationTest.kt`

End-to-end scenario tests:

- ✅ All 10 critical bug fixes documented
- ✅ Gradle version compatibility (AGP 8.7.3 with Gradle 8.9)
- ✅ App swiped away during download
- ✅ Device sleeps during download
- ✅ Activity destroyed during download
- ✅ Network interruption and resume
- ✅ Low storage handling
- ✅ Concurrent downloads thread safety
- ✅ Service crash recovery

## Running Tests

### Run all tests:

```bash
cd android
./gradlew test
```

### Run specific test class:

```bash
./gradlew test --tests DownloadNotificationServiceTest
./gradlew test --tests DownloadItemManagerTest
./gradlew test --tests InternalDownloadManagerTest
./gradlew test --tests AbsDownloaderTest
./gradlew test --tests DownloadIntegrationTest
```

### Run with coverage:

```bash
./gradlew testDebugUnitTest jacocoTestReport
```

Coverage report will be at: `android/app/build/reports/jacoco/test/html/index.html`

## Critical Bug Fixes Validated

These tests prevent regression of the following critical bugs:

1. **Wake Lock Timeout** - 6-hour timeout prevents battery drain if service crashes
2. **Notification Importance** - HIGH importance fixes priority mismatch
3. **Thread Safety** - @Volatile and synchronized prevent race conditions
4. **Storage Buffer** - Smart calculation (100MB or 5%) replaces wasteful 10%
5. **Error Handling** - Event emitter replaces Toast (works when backgrounded)
6. **Context Validation** - Checks activity state before service operations
7. **Exception Specificity** - Distinguishes expected vs unexpected errors
8. **Lifecycle Cleanup** - handleOnDestroy() properly releases resources
9. **Resource Leaks** - try-finally pattern prevents file stream leaks
10. **AutoCloseable Contract** - Removed misleading @Throws annotation
11. **Gradle Compatibility** - AGP 8.7.3 compatible with Gradle 8.9
12. **Network Timeouts** - 5-minute read timeout prevents indefinite hangs

## Dependencies Required

Add to `android/app/build.gradle`:

```gradle
dependencies {
    // Testing
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.5.0'
    testImplementation 'org.mockito:mockito-inline:5.2.0'
    testImplementation 'org.robolectric:robolectric:4.11.1'
    testImplementation 'org.jetbrains.kotlin:kotlin-test:1.9.0'
}
```

## Test Maintenance

When adding new download features:

1. Add unit tests for the new functionality
2. Add regression test for the bug being fixed
3. Update integration tests if end-to-end behavior changes
4. Document the fix in this README

## Continuous Integration

These tests should run on every commit to prevent regressions. Add to your CI pipeline:

```yaml
# Example GitHub Actions
- name: Run Unit Tests
  run: |
    cd android
    ./gradlew test

- name: Upload Test Report
  uses: actions/upload-artifact@v3
  with:
    name: test-results
    path: android/app/build/reports/tests/
```

## Known Limitations

- Mock-based tests (not true instrumentation tests)
- Some tests use reflection to access private fields
- Network calls are not actually executed (mocked)

For full end-to-end testing, supplement with manual testing or instrumentation tests on real devices.
