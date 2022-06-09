package fr.acinq.fc.app.channel

import akka.testkit.TestProbe
import akka.actor.PoisonPill
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.fc.app.Ticker.USD_TICKER
import slick.jdbc.PostgresProfile.api._
import fr.acinq.fc.app._
import fr.acinq.fc.app.db.{Blocking, Channels}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class FCNormalRestartSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with FCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Alice sends 2 HTLCs without update, re-sends on restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage1, cmd_add1, _) = makeCmdAdd(100000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(100000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add1
    alice ! cmd_add2
    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    val aliceAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd1
    bob ! aliceAdd2
    bobRelayer.expectNoMessage() // No sig from alice
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bobRelayer.expectNoMessage()

    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

    fulfillAliceHtlcByBob(aliceAdd1.id, preimage1, f)
    bob ! CMD_FULFILL_HTLC(aliceAdd2.id, preimage2) // Bob fulfills without sig
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999800000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 200000L.msat)
  }

  test("Alice sends 2 HTLCs, Bob gets update for the first one only, resolved on restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage1, cmd_add1, _) = makeCmdAdd(100000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(100000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add1
    alice ! CMD_SIGN(None)
    alice ! cmd_add2
    alice ! CMD_SIGN(None)
    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd1
    bob ! alice2bob.expectMsgType[StateUpdate]
    val aliceAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd2
    alice2bob.expectMsgType[StateUpdate] // Goes nowhere
    bob2alice.expectMsgType[StateUpdate] // Goes nowhere
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectNoMessage() // No sig from alice from the second one
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! CMD_FULFILL_HTLC(aliceAdd1.id, preimage1) // Bob gets a fulfill for a first payment while offline
    bob2alice.expectMsgType[UpdateFulfillHtlc] // Goes nowhere

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc] // Bob re-sends local fulfill it got while in OFFLINE
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectNoMessage() // Second one has been processed by bob
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    fulfillAliceHtlcByBob(aliceAdd2.id, preimage2, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999800000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 200000L.msat)
  }

  test("Alice falls behind, then re-syncs on restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)

    val probe = TestProbe()

    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money

    val (preimage1, cmd_add1, _) = makeCmdAdd(10000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(10000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage3, cmd_add3, _) = makeCmdAdd(10000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage4, cmd_add4, _) = makeCmdAdd(20000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add1
    bob ! cmd_add2
    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    val bobAdd2 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd1
    alice ! bobAdd2 // Both see each other's HTLCs, not yet signed
    // Bob(1l, 1r), Alice(1l, 1r), BobSigned(0l, 0r), AliceSigned(0l, 0r)

    bob ! cmd_add4
    val bobAdd4 = bob2alice.expectMsgType[UpdateAddHtlc]
    alice ! bobAdd4
    // Bob(2l, 1r), Alice(1l, 2r), BobSigned(0l, 0r), AliceSigned(0l, 0r)
    alice ! CMD_SIGN(None)
    bob ! alice2bob.expectMsgType[StateUpdate]
    // Bob(2l, 1r), Alice(1l, 2r), BobSigned(2l, 1r), AliceSigned(0l, 0r)
    bobRelayer.expectMsgType[Relayer.RelayForward]

    alice ! cmd_add3
    val aliceAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd3
    // Bob(2l, 2r), Alice(2l, 2r), BobSigned(2l, 1r), AliceSigned(0l, 0r)
    bob2alice.expectMsgType[StateUpdate] // Goes nowhere
    aliceRelayer.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! CMD_FULFILL_HTLC(aliceAdd1.id, preimage1, replyTo_opt = Some(probe.ref)) // Bob gets a fulfill for a first payment while offline
    bob2alice.expectMsgType[UpdateFulfillHtlc] // Goes nowhere

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    // Alice should relay 2 Bob's messages and re-send one Alice add
    // Bob should resend one fulfill and later relay a message that Alice re-sends
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc] // Bob re-sends local fulfill it got while in OFFLINE
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectNoMessage() // Second one has been processed by Bob
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    aliceRelayer.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    fulfillBobHtlcByAlice(bobAdd2.id, preimage2, f)
    fulfillAliceHtlcByBob(aliceAdd3.id, preimage3, f)
    fulfillBobHtlcByAlice(bobAdd4.id, preimage4, f)

    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999810000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 190000L.msat)
  }

  test("Alice sends 2 HTLCs, then re-sends by one on two next restarts") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)

    val probe = TestProbe()

    val (preimage1, cmd_add1, _) = makeCmdAdd(10000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(10000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add1
    alice ! CMD_SIGN(None)
    alice ! cmd_add2
    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd1
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob2alice.expectMsgType[StateUpdate] // Does not reach Alice
    bobRelayer.expectMsgType[Relayer.RelayForward]
    val aliceAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd2 // Will be removed on restart because no state update
    bobRelayer.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bob2alice.expectMsgType[StateUpdate] // Goes nowhere

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    bob ! CMD_FULFILL_HTLC(aliceAdd1.id, preimage1, replyTo_opt = Some(probe.ref)) // Bob gets a fulfill for a first payment while offline
    bob ! CMD_SIGN(None)
    bob ! CMD_FULFILL_HTLC(aliceAdd2.id, preimage2, replyTo_opt = Some(probe.ref)) // Bob gets a fulfill for a first payment while offline
    bob ! CMD_SIGN(None)
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice2bob.expectMsgType[StateUpdate] // Goes nowhere
    alice2bob.expectMsgType[StateUpdate] // Goes nowhere
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]

    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999980000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 20000L.msat) // Bob restores to a state where everything is fulfilled

    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(15000L.msat, f, currentBlockHeight)
    val (preimage4, alice2bobUpdateAdd4) = addHtlcFromBob2Alice(10000L.msat, f)
    fulfillBobHtlcByAlice(alice2bobUpdateAdd4.id, preimage4, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd3.id, preimage3, f)

    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999975000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 25000L.msat)
  }

  test("Bob channel terminates before getting a fulfill, then gets it from db on restart") { f =>
    HCTestUtils.resetEntireDatabase(f.aliceDB)
    HCTestUtils.resetEntireDatabase(f.bobDB)
    reachNormal(f)

    val probe = TestProbe()

    val channelId = f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.channelId
    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(100000L.msat, f, f.currentBlockHeight)
    val bobData = f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED]
    val aliceData = f.alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED]
    f.bob ! PoisonPill
    f.alice ! PoisonPill
    f.bob2alice.expectTerminated(f.bob)
    f.alice2bob.expectTerminated(f.alice)

    val f2 = init()
    f2.bobKit.nodeParams.db.pendingCommands.addSettlementCommand(channelId, CMD_FULFILL_HTLC(alice2bobUpdateAdd3.id, preimage3, replyTo_opt = Some(probe.ref)))
    f2.bob ! bobData
    f2.alice ! aliceData
    f2.bob ! Worker.HCPeerConnected
    f2.alice ! Worker.HCPeerConnected
    f2.alice ! f2.bob2alice.expectMsgType[InvokeHostedChannel]
    f2.bob ! f2.alice2bob.expectMsgType[LastCrossSignedState]
    f2.alice ! f2.bob2alice.expectMsgType[LastCrossSignedState]
    f2.bob ! f2.alice2bob.expectMsgType[LastCrossSignedState]
    f2.bob ! f2.alice2bob.expectMsgType[ChannelUpdate]
    f2.alice ! f2.bob2alice.expectMsgType[ChannelUpdate]
    f2.alice ! f2.bob2alice.expectMsgType[UpdateFulfillHtlc]
    f2.alice ! f2.bob2alice.expectMsgType[StateUpdate]
    f2.bob ! f2.alice2bob.expectMsgType[StateUpdate]
    f2.alice ! f2.bob2alice.expectMsgType[StateUpdate]

    awaitCond(f2.alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(f2.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(f2.alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999900000L.msat)
    awaitCond(f2.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 100000L.msat)
  }

  test("Bob re-sends an HTLC fulfill in got in OFFLINE for a CLOSED channel") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)

    val probe = TestProbe()

    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(15000L.msat, f, currentBlockHeight)

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    alice ! HC_CMD_SUSPEND(randomKey.publicKey, USD_TICKER)
    awaitCond(alice.stateName == OFFLINE) // Not leaving an OFFLINE state

    bob ! HC_CMD_SUSPEND(randomKey.publicKey, USD_TICKER)
    awaitCond(bob.stateName == OFFLINE) // Not leaving an OFFLINE state

    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd3.id, preimage3, replyTo_opt = Some(probe.ref)) // Bob gets a fulfill for a first payment while offline
    bob2alice.expectMsgType[UpdateFulfillHtlc] // Goes nowhere

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[fr.acinq.eclair.wire.protocol.Error]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    awaitCond(alice.stateName == CLOSED)
    awaitCond(alice.stateName == CLOSED)
  }

  test("Alice loses channel data with HTLCs, restores from Bob's data") { f =>
    HCTestUtils.resetEntireDatabase(f.aliceDB)
    HCTestUtils.resetEntireDatabase(f.bobDB)
    reachNormal(f)

    val probe = TestProbe()

    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, f.currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money

    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(10000L.msat, f, f.currentBlockHeight)
    val (preimage2, alice2bobUpdateAdd2) = addHtlcFromBob2Alice(10000L.msat, f)

    f.alice ! Worker.HCPeerDisconnected
    f.bob ! Worker.HCPeerDisconnected
    awaitCond(f.alice.stateName == OFFLINE)
    awaitCond(f.bob.stateName == OFFLINE)

    val channelId = f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.channelId
    Blocking.txWrite(Channels.findByRemoteNodeIdUpdatableCompiled(f.bobKit.nodeParams.nodeId.value.toArray, USD_TICKER.tag).delete, f.aliceDB) // Alice loses channel data

    val f2 = init()
    f.bob ! Worker.HCPeerConnected
    f2.alice ! Worker.HCPeerConnected
    awaitCond(f.bob.stateName == SYNCING)
    awaitCond(f2.alice.stateName == SYNCING)
    f2.alice ! f2.bob2alice.expectMsgType[InvokeHostedChannel]
    f2.alice2bob.expectMsgType[InitHostedChannel] // Alice does not have a channel

    val bobData = HostedState(f.bobKit.nodeParams.nodeId, f2.aliceKit.nodeParams.nodeId,
      f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.lastCrossSignedState)

    f.bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd1.id, preimage1, replyTo_opt = Some(probe.ref))
    f2.bob2alice.expectMsgType[UpdateFulfillHtlc] // Alice does not receive this

    val f3 = init()
    f3.aliceKit.nodeParams.db.pendingCommands.addSettlementCommand(channelId, CMD_FULFILL_HTLC(alice2bobUpdateAdd2.id, preimage2, replyTo_opt = Some(probe.ref))) // Alice gets Bob's payment fulfilled, HC is not there
    f3.alice ! HC_CMD_RESTORE(bobData.nodeId1, USD_TICKER, bobData)

    f3.alice ! Worker.HCPeerDisconnected
    f.bob ! Worker.HCPeerDisconnected
    awaitCond(f3.alice.stateName == OFFLINE)
    awaitCond(f.bob.stateName == OFFLINE)

    f.bob ! Worker.HCPeerConnected
    f3.alice ! Worker.HCPeerConnected
    awaitCond(f.bob.stateName == SYNCING)
    awaitCond(f3.alice.stateName == SYNCING)
    f3.alice ! f3.bob2alice.expectMsgType[InvokeHostedChannel]
    f.bob ! f3.alice2bob.expectMsgType[LastCrossSignedState]
    f3.alice ! f3.bob2alice.expectMsgType[LastCrossSignedState]
    f.bob ! f3.alice2bob.expectMsgType[LastCrossSignedState]
    f.bob ! f3.alice2bob.expectMsgType[ChannelUpdate]
    f.bob ! f3.alice2bob.expectMsgType[UpdateFulfillHtlc]
    f.bob ! f3.alice2bob.expectMsgType[StateUpdate]
    f3.alice ! f3.bob2alice.expectMsgType[UpdateFulfillHtlc]
    f3.alice ! f3.bob2alice.expectMsgType[ChannelUpdate]
    f3.alice ! f3.bob2alice.expectMsgType[StateUpdate]
    f3.alice ! f3.bob2alice.expectMsgType[StateUpdate]
    f.bob ! f3.alice2bob.expectMsgType[StateUpdate]
    f3.alice ! f3.bob2alice.expectMsgType[StateUpdate]
    f.bob ! f3.alice2bob.expectMsgType[StateUpdate]

    f3.alice2bob.expectNoMessage()
    f3.bob2alice.expectNoMessage()

    awaitCond(f3.alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(f3.alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 9999800000L.msat)
    awaitCond(f.bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 200000L.msat)
  }
}
