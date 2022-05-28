package fr.acinq.fc.app.channel

import fr.acinq.eclair.channel.LocalChannelUpdate
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.wire.protocol.UnknownMessage
import fr.acinq.fc.app.Ticker.USD_TICKER
import fr.acinq.fc.app._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class FCAnnouncementSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with FCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Establish and announce a PHC") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    announcePHC(f)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].channelAnnouncement.isDefined)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].channelAnnouncement.isDefined)
  }

  test("Re-announce updates") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    announcePHC(f)
    bob ! HostedChannel.SendAnnouncements(force = false)
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(bobSync.expectMsgType[UnknownMessage].tag == FC.PHC_UPDATE_GOSSIP_TAG)
    channelUpdateListener.expectNoMessage()
    bobSync.expectNoMessage()
    bob ! HostedChannel.SendAnnouncements(force = true)
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(bobSync.expectMsgType[UnknownMessage].tag == FC.PHC_ANNOUNCE_GOSSIP_TAG)
    assert(bobSync.expectMsgType[UnknownMessage].tag == FC.PHC_UPDATE_GOSSIP_TAG)
    channelUpdateListener.expectNoMessage()
    bobSync.expectNoMessage()
  }

  test("Turn PHC private, then public again") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    announcePHC(f)
    alice ! HC_CMD_PRIVATE(bobKit.nodeParams.nodeId, USD_TICKER)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].channelAnnouncement.isEmpty)
    alice ! HostedChannel.SendAnnouncements(force = false)
    channelUpdateListener.expectNoMessage()
    alice ! HC_CMD_PUBLIC(bobKit.nodeParams.nodeId, USD_TICKER, force = true)
    channelUpdateListener.expectMsgType[LocalChannelUpdate] // Alice update event
    bob ! alice2bob.expectMsgType[AnnouncementSignature]
    channelUpdateListener.expectMsgType[LocalChannelUpdate] // Bob update event
    bobSync.expectMsgType[UnknownMessage]
    bobSync.expectMsgType[UnknownMessage]
    alice ! bob2alice.expectMsgType[AnnouncementSignature]
    channelUpdateListener.expectMsgType[LocalChannelUpdate] // Alice update event
    aliceSync.expectMsgType[UnknownMessage]
    aliceSync.expectMsgType[UnknownMessage]
    channelUpdateListener.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    aliceSync.expectNoMessage()
    bobSync.expectNoMessage()
  }
}
