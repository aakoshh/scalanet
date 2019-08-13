package io.iohk.scalanet.experimental

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelSetupException, MessageMTUException}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import monix.eval.Task
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class UDPExpPeerGroup[M](address: InetSocketAddress)(
    implicit
    codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer]
) extends EPeerGroup[InetSocketAddress, M] {

  private val log = LoggerFactory.getLogger(getClass)
  private val workerGroup = new NioEventLoopGroup()

  private val handlers = createSet[Handler[InetSocketAddress, M]]

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
        nettyChannel
          .pipeline()
          .addLast(new ChannelInboundHandlerAdapter() {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              val datagram = msg.asInstanceOf[DatagramPacket]
              try {
                val remoteAddress = datagram.sender()
                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                println(s"Client read $messageE from $remoteAddress")
                for {
                  message <- messageE
                  h <- handlers
                  ch = ctx.channel().asInstanceOf[NioDatagramChannel]
                } h(Envelope(None, ch.remoteAddress(), message))
              } finally {
                datagram.content().release()
              }
            }
          })
      }
    })

  private val serverBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
        nettyChannel
          .pipeline()
          .addLast(new ChannelInboundHandlerAdapter() {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              val datagram = msg.asInstanceOf[DatagramPacket]
              try {
                val remoteAddress = datagram.sender()
                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                log.debug(s"Server read $messageE from $remoteAddress")
                for {
                  message <- messageE
                  h <- handlers
                  ch = ctx.channel().asInstanceOf[NioDatagramChannel]
                } h(Envelope(None, ch.remoteAddress(), message))
              } finally {
                datagram.content().release()
              }
            }
          })
      }
    })

  override def processAddress: InetSocketAddress = address

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(address)

  override def connect(): Task[Unit] =
    toTask(serverBind)
      .map { _ =>
        log.info(s"Server bound to address $address")
      }
      .onErrorRecoverWith {
        case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
      }

  override def client(to: InetSocketAddress): Task[EClientChannel[InetSocketAddress, M]] = {
    val cf = clientBootstrap.connect(to)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map { nettyChannel =>
        log.debug(s"Client generated to talk to $to")
        new UDPExpClientChannel(nettyChannel, to)
      }
      .onErrorRecoverWith {
        case e: Throwable =>
          Task.raiseError(new ChannelSetupException[InetMultiAddress](InetMultiAddress(to), e))
      }
  }

  override def onReception(handler: Handler[InetSocketAddress, M]): Unit = {
    handlers += handler
    log.info(s"Handler registered by $processAddress.\nThere are ${handlers.size} handlers for the next message")
  }

  override def shutdown(): Task[Unit] = {
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

class UDPExpClientChannel[M](nettyChannel: NioDatagramChannel, remoteAddress: InetSocketAddress)(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer]
) extends EClientChannel[InetSocketAddress, M] {

  private val log = LoggerFactory.getLogger(getClass)

  override def to: InetSocketAddress = remoteAddress

  override def sendMessage(m: M): Task[Unit] = {
    val encodedMessage = codec.encode(m)
    toTask(nettyChannel.writeAndFlush {
      log.debug(s"Sending $encodedMessage to $remoteAddress")
      new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), remoteAddress)
    }).onErrorRecoverWith {
      case _: IOException =>
        Task.raiseError(new MessageMTUException[InetMultiAddress](InetMultiAddress(to), encodedMessage.capacity()))
    }
  }

  override def close(): Task[Unit] = Task.unit
}

class UDPExpConnection[M](nettyChannel: NioDatagramChannel, remoteAddress: InetSocketAddress)(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer]
) extends EConnection[M] {

  private val log = LoggerFactory.getLogger(getClass)

  override def replyWith(m: M): Task[Unit] = {
    val encodedMessage = codec.encode(m)
    toTask(nettyChannel.writeAndFlush {
      log.debug(s"Sending $encodedMessage to $remoteAddress")
      new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), remoteAddress)
    }).onErrorRecoverWith {
      case _: IOException =>
        Task.raiseError(new MessageMTUException[InetSocketAddress](remoteAddress, encodedMessage.capacity()))
    }
  }

  override def close(): Task[Unit] = Task.unit
}