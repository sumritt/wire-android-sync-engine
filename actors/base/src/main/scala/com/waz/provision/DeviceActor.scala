/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.provision

import java.io._

import akka.actor.SupervisorStrategy._
import akka.actor._
import android.content.Context
import android.view.View
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.LogTag
import com.waz.api.MessageContent
import com.waz.content.{Database, GlobalDatabase, GlobalPreferences}
import com.waz.log.{InternalLog, LogOutput}
import com.waz.media.manager.context.IntensityLevel
import com.waz.model.otr.ClientId
import com.waz.model.{ConvId, Liking, RConvId, MessageContent => _, _}
import com.waz.provision.DeviceActor.responseTimeout
import com.waz.service.AccountManager.ClientRegistrationState.Registered
import com.waz.service._
import com.waz.service.assets.AssetService.RawAssetInput.{ByteInput, UriInput}
import com.waz.service.call.Avs.WCall
import com.waz.service.call.{Avs, CallingService, FlowManagerService}
import com.waz.threading._
import com.waz.ui.UiModule
import com.waz.utils._
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.URI
import com.sun.jna.Pointer

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}

/**
  * Protip: checkout QAActorSpec.scala for a simple test environment that'll speed up debugging
  */
object DeviceActor {

  val responseTimeout = 240.seconds

  def props(deviceName: String,
            application: Context,
            backend: BackendConfig = BackendConfig.StagingBackend) =
  Props(new DeviceActor(deviceName, application, backend)).withDispatcher("ui-dispatcher")
}

class DeviceActor(val deviceName: String,
                  val application: Context,
                  backend: BackendConfig = BackendConfig.StagingBackend) extends Actor with ActorLogging {

  import ActorMessage._

  InternalLog.add(new LogOutput {
    override def log(str: String, level: InternalLog.LogLevel, tag: LogTag, ex: Option[Throwable] = None): Unit = {
      import com.waz.log.InternalLog.LogLevel._
      level match {
        case Error => DeviceActor.this.log.error(s"$tag: $str")
        case Warn  => DeviceActor.this.log.warning(s"$tag: $str")
        case _     => DeviceActor.this.log.info(s"$tag: $str")
      }
    }
    override def log(str: String, cause: Throwable, level: InternalLog.LogLevel, tag: LogTag): Unit =
      DeviceActor.this.log.error(cause, s"$tag: $str")

    override def flush() = Future.successful({})
    override def close() = Future.successful({})
    override val id = deviceName
  })

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 10.seconds) {
      case exc: Exception =>
        log.error(exc, s"device actor '$deviceName' died")
        Stop
    }

  val globalModule = new GlobalModuleImpl(application, backend) { global =>
    ZMessaging.currentGlobal = this
    lifecycle.acquireUi()

    com.waz.utils.isTest = true

    Await.ready(prefs(GlobalPreferences.FirstTimeWithTeams) := false, 5.seconds)
    Await.ready(prefs(GlobalPreferences.DatabasesRenamed) := true, 5.seconds)

    override lazy val accountsService: AccountsService = new AccountsServiceImpl(this) {
      ZMessaging.currentAccounts = this
    }

    override val storage: Database = new GlobalDatabase(application, Random.nextInt().toHexString)

    override lazy val metadata: MetaDataService = new MetaDataService(context) {
      override val cryptoBoxDirName: String = "otr_" + Random.nextInt().toHexString
      override lazy val deviceModel: String = deviceName
      override lazy val localBluetoothName: String = deviceName
    }

    override lazy val avs = new Avs {
      override def rejectCall(wCall: WCall, convId: RConvId) =
        log.warning("Calling not implemented for actors!")

      override def onNetworkChanged(wCall: WCall) =
        Future.successful(log.warning("Calling not implemented for actors!"))

      override def startCall(wCall: WCall, convId: RConvId, callType: Avs.WCallType.Value, convType: Avs.WCallConvType.Value, cbrEnabled: Boolean) =
        Future.successful { log.warning("Calling not implemented for actors!"); -1}

      override def setVideoSendState(wCall: WCall, convId: RConvId, state: Avs.VideoState.Value): Unit =
        log.warning("Calling not implemented for actors!")

      override def onHttpResponse(wCall: WCall, status: Int, reason: String, arg: Pointer) =
        Future.successful(log.warning("Calling not implemented for actors!"))

      override def endCall(wCall: WCall, convId: RConvId): Unit =
        log.warning("Calling not implemented for actors!")

      override def answerCall(wCall: WCall, convId: RConvId, callType: Avs.WCallType.Value, cbrEnabled: Boolean): Unit =
        log.warning("Calling not implemented for actors!")

      override def onConfigRequest(wCall: WCall, error: Int, json: String) =
        Future.successful(log.warning("Calling not implemented for actors!"))

      override def registerAccount(callingService: CallingService) =
        Future.failed(new Exception("Calling not implemented for actors!"))

      override def onReceiveMessage(wCall: WCall, msg: String, currTime: LocalInstant, msgTime: RemoteInstant, convId: RConvId, userId: UserId, clientId: ClientId): Unit =
        log.warning("Calling not implemented for actors!")

      override def unregisterAccount(wCall: WCall) =
        Future.successful(log.warning("Calling not implemented for actors!"))
    }

    override lazy val flowmanager = new FlowManagerService {
      override def flowManager = None

      override def getVideoCaptureDevices = Future.successful(Vector())

      override def setVideoCaptureDevice(id: RConvId, deviceId: String) = Future.successful(())

      override def setVideoPreview(view: View) = Future.successful(())

      override def setVideoView(id: RConvId, partId: Option[UserId], view: View) = Future.successful(())

      override val cameraFailedSig = Signal[Boolean](false)
    }

    override lazy val mediaManager = new MediaManagerService {
      override def mediaManager = Future.failed(new Exception("No media manager available in actors"))
      override def soundIntensity = Signal.empty[IntensityLevel]
      override def isSpeakerOn = Signal.empty[Boolean]
      override def setSpeaker(enable: Boolean) = Future.successful({})
    }
  }

  val accountsService = globalModule.accountsService

  val ui = returning(new UiModule(globalModule)) { ui =>
    ui.onStart()
  }

  val zmsOpt = accountsService.activeZms
  val zms = zmsOpt.collect { case Some(z) => z }
  val am  = accountsService.activeAccountManager.collect { case Some(a) => a }

  import Threading.Implicits.Background

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    ui.onDestroy()
    globalModule.lifecycle.releaseUi()
    Await.result(ui.getCurrent, 5.seconds) foreach { zms =>
      zms.syncContent.syncStorage { storage =>
        storage.getJobs foreach { job => storage.remove(job.id) }
      }
    }
    super.postStop()
  }

  def respondInFuture[S](receive: ActorMessage => Future[ResponseMessage]): Receive = {
    case message: ActorMessage =>
      log.info(s"Received message: $message")
      sender() ! (Try(Await.result(receive(message), responseTimeout)) match {
        case Success(m) => m
        case Failure(cause) =>
          val st = stackTrace(cause)
          log.error(cause, "Message handling failed")
          Failed(s"${cause.getMessage}: $st")
      })
  }

  override def receive: Receive = respondInFuture {
    case Echo(msg, _) => Future.successful(Echo(msg, deviceName))

    case Login(email, pass) =>
      (for {
        r1       <- accountsService.loginEmail(email, pass)
        Some(am) <- r1 match {
          case Right(userId) => accountsService.createAccountManager(userId, None, None)
          case Left(err)     => throw new Exception(s"Failed to login: $err")
        }
        _   <- accountsService.setAccount(Some(am.userId))
        r2  <- am.getOrRegisterClient()
        zms <- r2 match {
          case Right(Registered(_)) => am.zmessaging
          case Right(st) => throw new Exception(s"Failed to register new client: $st")
          case Left(err) => throw new Exception(s"Failed to register new client: $err")
        }
        //TODO wait for login-related syncing to complete before returning
//        t = clock.instant()
//        _ = log.info("Awaiting login sync")
//        _ <- zms.syncRequests.scheduler.awaitRunning
//        _ = log.info(s"Login sync complete, took: ${t until clock.instant()}")
      } yield Successful(s"Successfully logged in with user: ${am.userId}"))
        .recover {
          case NonFatal(e) => Failed(e.getMessage)
        }

    case GetUserName =>
      waitForSelf.map(u => Successful(u.name))

    case GetMessages(rConvId) =>
      for {
        (z, convId) <- zmsWithLocalConv(rConvId)
        idx         <- z.messagesStorage.msgsIndex(convId)
        cursor      <- idx.loadCursor
      } yield {
        ConvMessages(Array.tabulate(cursor.size) { i =>
          val m = cursor(i)
          MessageInfo(m.message.id, m.message.msgType, m.message.time.instant)
        })
      }

    case ClearConversation(remoteId) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, cId) =>
        z.conversations.clearConversation(cId).map(_.map(_._1))
      }

    case SendText(remoteId, msg) =>
      withZmsAndLocalConvWaitSync(remoteId) { case (z, cId) =>
        z.convsUi.sendTextMessage(cId, msg).map(_._1)
      }

    case UpdateText(msgId, text) =>
      for {
        z   <- zms.head
        msg <- z.messagesStorage.getMessage(msgId)
        res <- msg match {
          case Some(msg) if msg.userId == z.selfUserId =>
            z.convsUi.updateMessage(msg.convId, msgId, text).flatMap {
              case Some((id, _)) => awaitSyncResults(Set(id))
              case _ => Future.successful(Failed("No sync job was created"))
            }
          case _ =>
            Future.successful(Failed("No message found with given id"))
        }
      } yield res

    case DeleteMessage(rConvId, msgId) =>
      withZmsAndLocalConvWaitSync(rConvId) { case (z, cId) =>
        z.convsUi.deleteMessage(cId, msgId)
      }

    case SendGiphy(rConvId, searchQuery) =>
      zmsWithLocalConv(rConvId).flatMap { case (z, convId) =>
        for {
          res            <- (if (searchQuery.isEmpty) z.giphy.trending() else z.giphy.searchGiphyImage(searchQuery)).future
          (id, _)        <- z.convsUi.sendTextMessage(convId, "Via giphy.com")
          Some((id2, _)) <- z.convsUi.sendAssetMessage(convId, UriInput(res.head._2.source.get))
          res            <- awaitSyncResults(Set(id, id2))
        } yield res
      }

    case RecallMessage(rConvId, msgId) =>
      withZmsAndLocalConvWaitOptSync(rConvId) { case (z, cId) =>
        z.convsUi.recallMessage(cId, msgId).map(_.map(_._1))
      }

    case SendImage(remoteId, path) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.convsUi.sendAssetMessage(convId, ByteInput(IoUtils.toByteArray(new FileInputStream(path)))).map(_.map(_._1))
      }

    case SendLocation(remoteId, lon, lat, name, zoom) =>
      withZmsAndLocalConvWaitSync(remoteId) { case (z, convId) =>
        z.convsUi.sendLocationMessage(convId, new MessageContent.Location(lon, lat, name, zoom)).map(_._1)
      }

    case SendFile(remoteId, path, mime) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.convsUi.sendAssetMessage(convId, UriInput(URI.parse(path))).map(_.map(_._1))
      }

    case Knock(remoteId) =>
      withZmsAndLocalConvWaitSync(remoteId) { case (z, convId) =>
        z.convsUi.knock(convId).map(_._1)
      }

    case SetEphemeral(remoteId, expiration) =>
      zmsWithLocalConv(remoteId).flatMap { case (z, convId) =>
        z.convsUi.setEphemeral(convId, expiration)
      }.map(_ => Successful)

    case MarkEphemeralRead(convId, messageId) =>
      zms.head.flatMap(_.ephemeral.onMessageRead(messageId))
        .map(_ => Successful)

    case Typing(remoteId) =>
      zmsWithLocalConv(remoteId).flatMap { case (z, convId) =>
        z.typing.selfChangedInput(convId)
      }.map(_ => Successful)

    case ClearTyping(remoteId) =>
      zmsWithLocalConv(remoteId).flatMap { case (z, convId) =>
        z.typing.selfClearedInput(convId)
      }.map(_ => Successful)

    case ArchiveConv(remoteId) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.conversations.setConversationArchived(convId, archived = true).map(_.map(_._1))
      }

    case UnarchiveConv(remoteId) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.conversations.setConversationArchived(convId, archived = false).map(_.map(_._1))
      }

    case MuteConv(remoteId) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.conversations.setConversationMuted(convId, muted = true).map(_.map(_._1))
      }

    case UnmuteConv(remoteId) =>
      withZmsAndLocalConvWaitOptSync(remoteId) { case (z, convId) =>
        z.conversations.setConversationMuted(convId, muted = false).map(_.map(_._1))
      }

    case UpdateProfileUserName(userName) =>
      zms.head.flatMap(_.users.updateHandle(Handle(userName)))
        .map(_.fold(err => Failed(s"unable to update user name: ${err.code}, ${err.message}, ${err.label}"), _ => Successful))

    case SetStatus(status) =>
      Availability.all.find(_.toString.toLowerCase == status.toLowerCase) match {
        case Some(availability) =>
          for {
            z            <- zms.head
            _            <- z.users.storeAvailabilities(Map(z.selfUserId -> availability))
          } yield Successful
        case None => Future.successful(Failed(s"Unknown availability: $status"))
      }

    case SetMessageReaction(remoteId, messageId, action) =>
      zms.head.flatMap { z =>
        z.messagesStorage.getMessage(messageId).flatMap {
          case Some(msg) if action == Liking.Action.Like =>
            z.reactions.like(msg.convId, messageId).map(_ => Successful)
          case Some(msg) =>
            z.reactions.unlike(msg.convId, messageId).map(_ => Successful)
          case None =>
            Future.successful(Failed("No message found with given id"))
        }
      }

    case SetDeviceLabel(label) =>
      for {
        z      <- zms.head
        client <- z.otrClientsService.selfClient.head
        _      <- z.otrClientsService.updateClientLabel(client.id, label)
      } yield Successful

    case GetDeviceId() =>
      zms.head.flatMap(_.otrClientsService.selfClient.head).map(c => Successful(c.id.str))

    case GetDeviceFingerPrint() =>
      (for {
        z      <- zms.head
        am     <- am.head
        client <- z.otrClientsService.selfClient.head
        fp     <- am.fingerprintSignal(z.selfUserId, client.id).head
      } yield fp.map(new String(_)))
        .map(_.fold2(Failed("Failed to get finger print for self client"), Successful(_)))

    case AwaitSyncCompleted =>
      zms.flatMap(_.syncContent.syncJobs.filter(_.isEmpty)).head.map(_ => Successful)

    case m@_ =>
      log.error(s"unknown remote api command '$m'")
      Future.successful(Failed(s"unknown remote api command '$m'"))
  }

  def zmsWithLocalConv(rConvId: RConvId): Future[(ZMessaging, ConvId)] = {
    for {
      z   <- zmsOpt.filter(_.isDefined).head.collect { case Some(z) => z }
      _   = log.info(s"zms ready: $z")
      cId <- z.convsStorage.convsSignal.map { csSet =>
        log.info(s"all conversations: ${csSet.conversations}")
        csSet.conversations.find(_.remoteId == rConvId)
      }.collect { case Some(c) => c.id }.head
      _   = log.info(s"Found local conv: $cId for remote: $rConvId")
    } yield (z, cId)
  }

  private def withZmsAndLocalConvWaitOptSync(rConvId: RConvId)(f: (ZMessaging, ConvId) => Future[Option[SyncId]]) =
    for {
      (z, cId) <- zmsWithLocalConv(rConvId)
      id  <- f(z, cId)
      res <- id.map(Set(_)).map(awaitSyncResults).getOrElse(Future.successful(Failed("Request didn't produce sync job")))
    } yield res

  private def withZmsAndLocalConvWaitSync(rConvId: RConvId)(f: (ZMessaging, ConvId) => Future[SyncId]) =
    withZmsAndLocalConvWaitOptSync(rConvId) { case (z, cId) => f(z, cId).map(Some(_)) }

  private def withZmsWaitOptSync(f: ZMessaging => Future[Option[SyncId]]) =
    for {
      z   <- zmsOpt.filter(_.isDefined).head.collect { case Some(z) => z}
      id  <- f(z)
      res <- id.map(Set(_)).map(awaitSyncResults).getOrElse(Future.successful(Failed("Request didn't produce sync job")))
    } yield res

  private def awaitSyncResults(ids: Set[SyncId]) = {
    zms.head.flatMap(_.syncRequests.scheduler.await(ids)).map { res =>
      if (res.forall(_.isSuccess))
        Successful
      else Failed(res.find(!_.isSuccess).map(_.toString).getOrElse(s"Failed on waiting for sync results: $ids, reason unknown"))
    }
  }

  def waitForSelf: Future[UserData] =
    zms.flatMap(_.users.selfUser).head

  def stackTrace(t: Throwable) = {
    val result = new StringWriter()
    val printWriter = new PrintWriter(result)
    t.printStackTrace(printWriter)
    result.toString
  }
}
