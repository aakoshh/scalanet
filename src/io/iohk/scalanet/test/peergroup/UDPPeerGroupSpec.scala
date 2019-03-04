package io.iohk.scalanet.peergroup

import java.net.BindException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import io.iohk.scalanet.peergroup.UDPPeerGroup.Config
import io.iohk.scalanet.peergroup.future._
import org.scalatest.EitherValues._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.decco.auto._

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(1 second)

  behavior of "UDPPeerGroup"

  it should "send and receive a message" in withTwoRandomUDPPeerGroups { (pg1, pg2) =>
    val value: Future[ByteBuffer] = pg2.messageStream.head()
    val b: Array[Byte] = "Hello".getBytes(UTF_8)

    pg1.sendMessage(pg2.config.bindAddress, ByteBuffer.wrap(b))
    toArray(value.futureValue) shouldBe b

    val value2: Future[ByteBuffer] = pg1.messageStream.head()
    pg2.sendMessage(pg1.config.bindAddress, ByteBuffer.wrap(b))
    toArray(value2.futureValue) shouldBe b
  }

  it should "send and receive a typed message" in withTwoRandomUDPPeerGroups { (pg1, pg2) =>
    val pg1Channel = pg1.createMessageChannel[String]()
    val pg2Channel = pg2.createMessageChannel[String]()
    val message = "Hello!"

    val messageReceivedF = pg2Channel.inboundMessages.head()

    pg1Channel.sendMessage(pg2.config.bindAddress, message)

    messageReceivedF.futureValue shouldBe message
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  it should "support a throws create method" in withUDPAddressInUse { address =>
    isListeningUDP(address) shouldBe true
    val exception = the[IllegalStateException] thrownBy UDPPeerGroup.createOrThrow(Config(address))
    exception.getCause shouldBe a[BindException]
  }

  it should "support an Either create method" in withUDPAddressInUse { address =>
    isListeningUDP(address) shouldBe true
    UDPPeerGroup.create(Config(address)).left.value.cause shouldBe a[BindException]
  }

}