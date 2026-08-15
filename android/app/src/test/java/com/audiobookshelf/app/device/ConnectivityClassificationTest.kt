package com.audiobookshelf.app.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Connection classification - the connectivity-state issue cluster (see `TESTING.md` §10):
 * [#1702](https://github.com/advplyr/audiobookshelf-app/issues/1702),
 * [#1560](https://github.com/advplyr/audiobookshelf-app/issues/1560),
 * [#1802](https://github.com/advplyr/audiobookshelf-app/issues/1802). Reported contract:
 *
 * > *VPN or a temporary connection loss must not be mistaken for permanent offline state, crash the
 * > app, or strand an unfinished operation.*
 *
 * `DeviceManager.checkConnectivity` is the whole of that decision. Everything downstream - whether
 * `MediaProgressSyncer.sync` contacts the server at all (`MediaProgressSyncer.kt:249`), whether
 * `PlayerNotificationService` treats the session as offline - is a consequence of the single
 * boolean it returns, so this is the seam the cluster names.
 *
 * The `TRANSPORT_*` and `NET_CAPABILITY_*` values are `public static final int` compile-time
 * constants, so they inline correctly on both sides and are safe here - unlike `Build.VERSION
 * .SDK_INT`, which is a real field read and returns `0` under the mockable `android.jar`.
 */
class ConnectivityClassificationTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var ctx: Context
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var capabilities: NetworkCapabilities

  @Before
  fun setUp() {
    ctx = mockk(relaxed = true)
    connectivityManager = mockk(relaxed = true)
    capabilities = mockk(relaxed = true)
    every { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    every { connectivityManager.activeNetwork } returns mockk<Network>(relaxed = true)
    every { connectivityManager.getNetworkCapabilities(any()) } returns capabilities
    every { capabilities.hasTransport(any()) } returns false
    every { capabilities.hasCapability(any()) } returns false
  }

  private fun withTransport(transport: Int) {
    every { capabilities.hasTransport(transport) } returns true
  }

  private fun withCapability(capability: Int) {
    every { capabilities.hasCapability(capability) } returns true
  }

  /**
   * The shape of a network that genuinely works: the transport is present *and* the system has
   * probed it and confirmed internet reachability. Transport presence alone is not enough - see
   * the captive-portal specs below - so every "counts as connected" case has to say so explicitly
   * rather than relying on production trusting the transport.
   */
  private fun withWorkingInternet() {
    withCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    withCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  // --- The three transports production recognises ---------------------------------------------

  @Test
  fun `cellular counts as connected`() {
    withTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    withWorkingInternet()

    assertTrue(DeviceManager.checkConnectivity(ctx))
  }

  @Test
  fun `wifi counts as connected`() {
    withTransport(NetworkCapabilities.TRANSPORT_WIFI)
    withWorkingInternet()

    assertTrue(DeviceManager.checkConnectivity(ctx))
  }

  @Test
  fun `ethernet counts as connected`() {
    withTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    withWorkingInternet()

    assertTrue(DeviceManager.checkConnectivity(ctx))
  }

  // --- Genuinely offline states ---------------------------------------------------------------

  @Test
  fun `no active network is offline`() {
    every { connectivityManager.getNetworkCapabilities(any()) } returns null

    assertFalse(DeviceManager.checkConnectivity(ctx))
  }

  @Test
  fun `a network with no recognised transport is offline`() {
    // Every hasTransport stub returns false - the airplane-mode / no-network shape.
    assertFalse(DeviceManager.checkConnectivity(ctx))
  }

  @Test
  fun `bluetooth tethering alone is not treated as connected`() {
    // Characterization: TRANSPORT_BLUETOOTH is not in production's list. Reverse-tethering over
    // Bluetooth is rare enough that excluding it is defensible; recorded so it is a decision.
    withTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

    assertFalse(DeviceManager.checkConnectivity(ctx))
  }

  // --- The reported defects --------------------------------------------------------------------

  /**
   * #1702's shape.
   *
   * Inputs: the active network is a VPN whose underlying transport is not reported on the VPN
   * network object - the state on devices where the VPN interface is the active network and
   * `NetworkCapabilities` carries `TRANSPORT_VPN` plus `NET_CAPABILITY_INTERNET` only.
   *
   * Expected: connected. A VPN that has internet is not offline; treating it as offline is exactly
   * "a VPN mistaken for permanent offline state".
   *
   * Observed: `false`. `checkConnectivity` tests only `TRANSPORT_CELLULAR`, `TRANSPORT_WIFI` and
   * `TRANSPORT_ETHERNET` (`DeviceManager.kt:160-169`) and returns `false` for anything else, so
   * every server sync is skipped and the app behaves as if there were no network at all.
   */
  @Test
  fun `a VPN network that has internet must not be classified as offline`() {
    withTransport(NetworkCapabilities.TRANSPORT_VPN)
    withCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    // Deliberately no NET_CAPABILITY_VALIDATED, which is the shape this spec's own inputs
    // describe: on the devices #1702 is reported from, the VPN network object carries
    // TRANSPORT_VPN plus NET_CAPABILITY_INTERNET and nothing else, because the system's
    // validation probes run against the underlying network rather than the tunnel. Stubbing
    // VALIDATED here (as this spec previously did) would have tested a state the reporters never
    // had, and would let a production check that demands VALIDATED pass while still classifying
    // their VPN as permanently offline - the exact bug.
    assertTrue(
            "a VPN advertising internet is a working connection, not an offline state",
            DeviceManager.checkConnectivity(ctx)
    )
  }

  /**
   * The converse error, and the issue doc's own first named case for this cluster: *"transport
   * available but internet probe fails"*.
   *
   * Inputs: Wi-Fi is associated but the network has neither `NET_CAPABILITY_INTERNET` nor
   * `NET_CAPABILITY_VALIDATED` - a captive portal (hotel, airport, corporate guest Wi-Fi), or a
   * router that is up while its uplink is down.
   *
   * Expected: not connected, so the caller keeps working offline rather than issuing requests that
   * will hang or be answered by the portal's login page.
   *
   * Observed: `true`. Only the transport is inspected; `hasCapability` is never called
   * (`DeviceManager.kt:158-170`). Requests then go to the portal, and #1560's report of the app
   * stranding an unfinished operation follows from waiting on responses that never come.
   */
  @Test
  fun `wifi with no validated internet must not be classified as connected`() {
    withTransport(NetworkCapabilities.TRANSPORT_WIFI)
    // Deliberately no NET_CAPABILITY_INTERNET and no NET_CAPABILITY_VALIDATED.

    assertFalse(
            "an associated-but-unvalidated network is a captive portal, not a usable connection",
            DeviceManager.checkConnectivity(ctx)
    )
  }

  /**
   * The **realistic** captive-portal shape, and the reason the spec above is not sufficient on its
   * own. A hotel/airport network does advertise `NET_CAPABILITY_INTERNET` - that capability means
   * "this network intends to provide internet", which the portal's own DHCP/DNS quite happily
   * claims. What it lacks is `NET_CAPABILITY_VALIDATED`: Android probed for connectivity and got
   * the portal's login page instead of the expected response.
   *
   * So `INTERNET` alone proves nothing, and a check written as `INTERNET || VALIDATED` classifies
   * a captive portal as connected while still passing the spec above (which stubs neither
   * capability, a combination a real Wi-Fi network essentially never reports). This spec pins the
   * distinction so that only a check requiring `VALIDATED` can satisfy both.
   */
  @Test
  fun `wifi advertising internet but not validated is a captive portal, not a connection`() {
    withTransport(NetworkCapabilities.TRANSPORT_WIFI)
    withCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    // Deliberately no NET_CAPABILITY_VALIDATED - the probe hit the portal's login page.

    assertFalse(
            "a network claiming internet that failed validation is a portal, not a usable connection",
            DeviceManager.checkConnectivity(ctx)
    )
  }

  /**
   * The same distinction on cellular, which is why the capability requirement is not scoped to
   * Wi-Fi. Exhausted prepaid data and carrier top-up walled gardens produce exactly this shape:
   * the mobile transport is up and advertises internet, while validation fails because every
   * request is redirected to the carrier's payment page.
   */
  @Test
  fun `cellular advertising internet but not validated is not a usable connection`() {
    withTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    withCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    // Deliberately no NET_CAPABILITY_VALIDATED - carrier walled garden.

    assertFalse(
            "a carrier walled garden is not a usable connection just because the radio is up",
            DeviceManager.checkConnectivity(ctx)
    )
  }

  /**
   * **The negative half of the VPN case, and the reason the spec above is not sufficient alone.**
   *
   * The obvious way to satisfy "a VPN must not read as offline" is to append `TRANSPORT_VPN` to
   * the transport list. That fix passes every other spec in this class while classifying a tunnel
   * that reaches nothing as a working connection - a VPN whose underlying network has dropped, or
   * one still negotiating. Requiring a capability rather than trusting the transport is what makes
   * the two VPN specs satisfiable together.
   */
  @Test
  fun `a VPN advertising neither internet nor validation is offline`() {
    withTransport(NetworkCapabilities.TRANSPORT_VPN)
    // Deliberately no NET_CAPABILITY_INTERNET and no NET_CAPABILITY_VALIDATED.

    assertFalse(
            "a tunnel that advertises no internet at all is not a usable connection",
            DeviceManager.checkConnectivity(ctx)
    )
  }

  /** The fully-working VPN, so the positive half is pinned symmetrically with the transports. */
  @Test
  fun `a validated VPN counts as connected`() {
    withTransport(NetworkCapabilities.TRANSPORT_VPN)
    withWorkingInternet()

    assertTrue(DeviceManager.checkConnectivity(ctx))
  }

  /**
   * The other null the platform can hand back. `getNetworkCapabilities(null)` is covered above via
   * a null capabilities object; this covers `activeNetwork` itself being null, which is what the
   * system reports in airplane mode and while a network is being torn down. Production passes that
   * null straight into `getNetworkCapabilities`, so the two nulls reach different lines.
   */
  @Test
  fun `a null active network is offline`() {
    every { connectivityManager.activeNetwork } returns null
    every { connectivityManager.getNetworkCapabilities(null) } returns null

    assertFalse(DeviceManager.checkConnectivity(ctx))
  }

  /**
   * Characterization of the crash surface #1560 describes. `checkConnectivity` opens with an
   * unchecked `as ConnectivityManager` cast (`DeviceManager.kt:157`). `getSystemService` is
   * documented to return null when the service is unavailable - which happens while a `Context` is
   * being torn down, the state a backgrounded media service is in when the system reclaims it.
   *
   * Recorded rather than asserted as a contract: on a real device the cast failing is a genuine
   * programming error, so "must not throw" is arguable. What is not arguable is that callers such
   * as `MediaProgressSyncer.sync` do not catch it, so it propagates out of a progress save.
   */
  @Test
  fun `a context with no connectivity service throws out of the connectivity check`() {
    every { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

    var thrown: Throwable? = null
    try {
      DeviceManager.checkConnectivity(ctx)
    } catch (e: Throwable) {
      thrown = e
    }

    assertTrue(
            "expected the unchecked cast to fail loudly; got $thrown",
            thrown is NullPointerException || thrown is ClassCastException
    )
  }
}
