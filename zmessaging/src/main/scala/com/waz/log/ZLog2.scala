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
package com.waz.log

import java.io.File
import java.net.URI
import java.util.Locale

import android.net.Uri
import com.waz.ZLog.LogTag
import com.waz.api.impl.ErrorResponse
import com.waz.api.{MessageContent => _, _}
import com.waz.cache2.CacheService.{AES_CBC_Encryption, Encryption, NoEncryption}
import com.waz.content.Preferences.PrefKey
import com.waz.log.InternalLog.LogLevel.{Debug, Error, Info, Verbose, Warn}
import com.waz.model.AccountData.Password
import com.waz.model.GenericContent.Location
import com.waz.model.ManagedBy.ManagedBy
import com.waz.model.messages.media.{ArtistData, TrackData}
import com.waz.model.{SSOId, _}
import com.waz.model.otr.{Client, ClientId, UserClients}
import com.waz.model.sync.{ReceiptType,SyncCommand, SyncJob, SyncRequest}
import com.waz.service.PropertyKey
import com.waz.service.assets.AssetService.RawAssetInput
import com.waz.service.assets.AssetService.RawAssetInput.{BitmapInput, ByteInput, UriInput, WireAssetInput}
import com.waz.service.assets.{GlobalRecordAndPlayService, Player}
import com.waz.service.assets.GlobalRecordAndPlayService.{AssetMediaKey, Idle, PCMContent, Paused, Playing, Recording, UnauthenticatedContent, UriMediaKey}
import com.waz.service.assets2.{Asset, AssetDetails}
import com.waz.service.call.Avs.AvsClosedReason.reasonString
import com.waz.service.call.Avs.VideoState
import com.waz.service.call.CallInfo
import com.waz.service.otr.OtrService.SessionId
import com.waz.sync.SyncResult
import com.waz.sync.client.AuthenticationManager.{AccessToken, Cookie}
import com.waz.utils.{sha2, wrappers}
import org.json.JSONObject
import org.threeten.bp
import org.threeten.bp.Instant

import scala.annotation.tailrec
import scala.collection.immutable.ListSet
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

object ZLog2 {

  trait LogShow[-T] {
    def showSafe(value: T): String
    def showUnsafe(value: T): String = showSafe(value)

    def contramap[B](f: B => T): LogShow[B] = LogShow.create[B](f andThen showSafe, f andThen showUnsafe)
  }

  //Used to mark traits that are safe to print their natural toString implementation
  trait SafeToLog
  object SafeToLog {
    implicit val SafeToLogLogShow: LogShow[SafeToLog] = LogShow.logShowWithToString
  }


  object LogShow {
    import shapeless._
    import shapeless.ops.record.ToMap

    def apply[T: LogShow]: LogShow[T] = implicitly[LogShow[T]]

    def create[T](safe: T => String, unsafe: T => String): LogShow[T] = new LogShow[T] {
      override def showSafe(value: T): String = safe(value)
      override def showUnsafe(value: T): String = unsafe(value)
    }

    def create[T](safe: T => String): LogShow[T] = new LogShow[T] {
      override def showSafe(value: T): String = safe(value)
    }

    def createFrom[T](log: T => Log): LogShow[T] = new LogShow[T] {
      override def showSafe(value: T): String = log(value).buildMessageSafe
      override def showUnsafe(value: T): String = log(value).buildMessageUnsafe
    }

    //maybe we need to tune it
    //TODO provide ability to take set of "unsafe" fields, for which we search and apply the LogShow for their type
    def create[T, H <: HList](hideFields: Set[String] = Set.empty, inlineFields: Set[String] = Set.empty, padding: Int = 2)
                             (implicit ct: ClassTag[T], lg: LabelledGeneric.Aux[T, H], tm: ToMap[H]): LogShow[T] =
      new LogShow[T] {
        override def showSafe(value: T): String = {
          val record = tm.apply(lg.to(value)).collect {
            case (k: Symbol, v) if !hideFields.contains(k.name) => k.name -> v
          }

          val (inlined, normal) = record.partition(t => inlineFields.contains(t._1))
          val builder = new StringBuilder(s"\n${ct.runtimeClass.getSimpleName}:\n")
          val paddingStr = String.valueOf(Array.fill(padding)(' '))

          val padTo = if (normal.isEmpty) 0 else normal.keySet.maxBy(_.length).length

          normal.foreach { case (fieldName, fieldValue) =>
            builder.append(paddingStr).append(String.format("%1$-" + (padTo + 1) + "s", fieldName + ":")).append(s" $fieldValue").append("\n")
          }

          if (inlined.nonEmpty) {
            builder.append(paddingStr).append("OTHER FIELDS: ")
            inlined.foreach { case (fieldName, fieldValue) =>
                builder.append(fieldName).append(" = ").append(fieldValue.toString).append(" | ")
            }
          }

          builder.toString()
        }
      }

    private[log] def logShowWithToString[T]: LogShow[T] = create(_.toString)

    private[log] def logShowWithHash[T]: LogShow[T] = new LogShow[T] {
      private def name(v: T) = v.getClass.getSimpleName

      override def showSafe(v: T): String = s"${name(v)}(${sha2(v.toString).take(9)})"
      override def showUnsafe(v: T): String = s"${name(v)}(${v.toString})"
    }

    //primitives
    implicit val BooleanLogShow:     LogShow[Boolean]        = logShowWithToString
    implicit val ByteLogShow:        LogShow[Byte]           = logShowWithToString
    implicit val ShortLogShow:       LogShow[Short]          = logShowWithToString
    implicit val IntLogShow:         LogShow[Int]            = logShowWithToString
    implicit val LongLogShow:        LogShow[Long]           = logShowWithToString
    implicit val FloatLogShow:       LogShow[Float]          = logShowWithToString
    implicit val DoubleLogShow:      LogShow[Double]         = logShowWithToString
    implicit val RemoteInstantShow:  LogShow[RemoteInstant]  = logShowWithToString
    implicit val LocalInstantShow:   LogShow[LocalInstant]   = logShowWithToString
    implicit val InstantShow:        LogShow[Instant]        = logShowWithToString
    implicit val DurationShow:       LogShow[Duration]       = logShowWithToString
    implicit val FiniteDurationShow: LogShow[FiniteDuration] = logShowWithToString
    implicit val BPDurationShow:     LogShow[bp.Duration]    = logShowWithToString
    implicit val ThrowableShow:      LogShow[Throwable]      = logShowWithToString
    implicit val ShowStringLogShow:  LogShow[ShowString]     = logShowWithToString

    implicit val RedactedStringShow: LogShow[RedactedString] = create(_ => "<redacted>", _.value)

    implicit val Sha256LogShow: LogShow[Sha256] = create(_.hexString, _.str)

    implicit val enumShow: LogShow[Enum[_]] = LogShow.create((enumValue: Enum[_]) => enumValue.name())

    implicit val JSONObjectLogShow:  LogShow[JSONObject] = logShowWithHash

    //TODO how much of a file/uri can we show in prod?
    //common types
    implicit val FileLogShow: LogShow[File] = create(_ => "<file>", _.getAbsolutePath)
    implicit val AUriLogShow: LogShow[Uri]  = create(_ => "<uri>", _.toString)
    implicit val UriLogShow:  LogShow[URI]  = create(_ => "<uri>", _.toString)
    implicit val WUriLogShow: LogShow[wrappers.URI] = create(_ => "<uri>", _.toString)

    implicit def traversableShow[T](implicit show: LogShow[T]): LogShow[Traversable[T]] = {
      def createString(xs: Traversable[T], toString: T => String, elemsToPrint: Int = 3): String = {
        val end = if (xs.size > elemsToPrint) s" and ${xs.size - elemsToPrint} other elements..." else ""
        xs.take(elemsToPrint).mkString("", ", ", end)
      }

      create(
        (xs: Traversable[T]) => createString(xs, show.showSafe),
        (xs: Traversable[T]) => createString(xs, show.showUnsafe)
      )
    }

    implicit def arrayShow[T: LogShow]: LogShow[Array[T]] =
      LogShow[Traversable[T]].contramap(_.toTraversable)

    implicit def listSetShow[T: LogShow]: LogShow[ListSet[T]] =
      LogShow[Traversable[T]].contramap(_.toTraversable)

    implicit def optionShow[T](implicit show: LogShow[T]): LogShow[Option[T]] =
      create(_.map(show.showSafe).toString, _.map(show.showUnsafe).toString)

    implicit def tryShow[T](implicit show: LogShow[T]): LogShow[Try[T]] =
      create(_.map(show.showSafe).toString, _.map(show.showUnsafe).toString)

    implicit def tuple2Show[A,B](implicit showA: LogShow[A], showB: LogShow[B]): LogShow[(A,B)] =
      create(
        t => (showA.showSafe(t._1), showB.showSafe(t._2)).toString(),
        t => (showA.showUnsafe(t._1), showB.showUnsafe(t._2)).toString())

    implicit def tuple3Show[A,B,C](implicit showA: LogShow[A], showB: LogShow[B], showC: LogShow[C]): LogShow[(A,B,C)] =
      create(
        t => (showA.showSafe(t._1), showB.showSafe(t._2), showC.showSafe(t._3)).toString(),
        t => (showA.showUnsafe(t._1), showB.showUnsafe(t._2), showC.showUnsafe(t._3)).toString()
      )

    //wire types
    implicit val UidShow:        LogShow[Uid]        = logShowWithHash
    implicit val UserIdShow:     LogShow[UserId]     = logShowWithHash
    implicit val AssetIdShow:    LogShow[AssetId]    = logShowWithHash
    implicit val RAssetIdShow:   LogShow[RAssetId]   = logShowWithHash
    implicit val AccountIdShow:  LogShow[AccountId]  = logShowWithHash
    implicit val MessageIdShow:  LogShow[MessageId]  = logShowWithHash
    implicit val ConvIdShow:     LogShow[ConvId]     = logShowWithHash
    implicit val RConvIdShow:    LogShow[RConvId]    = logShowWithHash
    implicit val ClientIdShow:   LogShow[ClientId]   = logShowWithHash
    implicit val TeamIdShow:     LogShow[TeamId]     = logShowWithHash
    implicit val NotIdShow:      LogShow[NotId]      = logShowWithHash
    implicit val CacheKeyShow:   LogShow[CacheKey]   = logShowWithHash
    implicit val AssetTokenShow: LogShow[AssetToken] = logShowWithHash

    implicit val PasswordShow: LogShow[Password] = create(_ => "********") //Also don't show in debug mode (e.g. Internal)

    implicit val NameShow:              LogShow[Name]             = logShowWithHash
    implicit val EmailShow:             LogShow[EmailAddress]     = logShowWithHash
    implicit val HandleShow:            LogShow[Handle]           = logShowWithHash
    implicit val ConfirmationCodedShow: LogShow[ConfirmationCode] = logShowWithHash
    implicit val PhoneNumberShow:       LogShow[PhoneNumber]      = logShowWithHash

    implicit val PushTokenShow:   LogShow[PushToken]   = logShowWithHash
    implicit val SearchQueryShow: LogShow[SearchQuery] = logShowWithHash
    implicit val AESKeyShow:      LogShow[AESKey]      = logShowWithHash

    implicit val PrefKeyLogShow: LogShow[PrefKey[_]]      = logShowWithToString
    implicit val PropertyKeyLogShow: LogShow[PropertyKey] = logShowWithToString
    implicit val ReadReceiptSettingsShow: LogShow[ReadReceiptSettings] = logShowWithToString

    implicit val SSOIdShow: LogShow[SSOId] = create(id => s"SSOId(subject: ${sha2(id.subject)}, tenant:${sha2(id.tenant)})")
    implicit val ManagedByShow: LogShow[ManagedBy] = create(id => s"ManagedBy($id)")

    implicit val RawAssetInputLogShow: LogShow[RawAssetInput] =
      createFrom {
        case UriInput(uri)                => l"UriInput($uri)"
        case ByteInput(bytes)             => l"ByteInput(size: ${bytes.length})"
        case BitmapInput(bm, orientation) => l"BitmapInput(@${showString(bm.toString)}, orientation: $orientation)"
        case WireAssetInput(id)           => l"WireAssetInput($id)"
      }

    implicit val CredentialsLogShow: LogShow[Credentials] =
      createFrom {
        case EmailCredentials(email, password, code) => l"EmailCredentials($email, $password, $code)"
        case PhoneCredentials(phone, code)           => l"PhoneCredentials($phone, $code)"
        case HandleCredentials(handle, password)     => l"HandleCredentials($handle, $password)"
      }

    implicit val EncryptionShow: LogShow[Encryption] =
      LogShow.createFrom {
        case NoEncryption => l"NoEncryption"
        case AES_CBC_Encryption(key) => l"AES_CBC_Encryption($key)"
      }

    implicit val CookieShow: LogShow[Cookie] = create(
      c => s"Cookie(${c.str.take(10)}, exp: ${c.expiry}, isValid: ${c.isValid})",
      c => s"Cookie(${c.str}, exp: ${c.expiry}, isValid: ${c.isValid})"
    )

    implicit val AccessTokenShow: LogShow[AccessToken] = create(
      t => s"AccessToken(${t.accessToken.take(10)}, exp: ${t.expiresAt}, isValid: ${t.isValid})",
      t => s"AccessToken(${t.accessToken}, exp: ${t.expiresAt}, isValid: ${t.isValid})"
    )

    implicit val ClientLogShow: LogShow[Client] = createFrom { c =>
      import c._
      l"Client(id: $id | regTime: $regTime | verified: $verified)"
    }

    implicit val UserClientsLogShow: LogShow[UserClients] = createFrom { c =>
      import c._
      l"UserClients(user: $user | clients: ${clients.values})"
    }

    implicit val SessionIdLogShow: LogShow[SessionId] = createFrom { id =>
      import id._
      l"SessionId(userId: $user | client: $client)"
    }

    implicit val MessageDataLogShow: LogShow[MessageData] =
      LogShow.createFrom { m =>
        import m._
        l"MessageData(id: $id | convId: $convId | msgType: $msgType | userId: $userId | state: $state | time: $time | localTime: $localTime)"
      }

    implicit val MessageContentLogShow: LogShow[MessageContent] =
      LogShow.createFrom { m =>
        import m._
        l"MessageContent(tpe: $tpe | asset: $asset | width: $width | height $height | syncNeeded $syncNeeded | mentions: $mentions)"
      }

    implicit val UserDataLogShow: LogShow[UserData] =
      LogShow.createFrom { u =>
        import u._
        l"UserData(id: $id | teamId: $teamId | name: $name | displayName: $displayName | email: $email | phone: $phone | handle: $handle | deleted: $deleted)"
      }

    implicit val UserInfoLogShow: LogShow[UserInfo] =
      LogShow.createFrom { u =>
        import u._
        l"UserInfo: id: $id | email: $email: | phone: $phone: | picture: $picture: | deleted: $deleted: | handle: $handle: | expiresAt: $expiresAt: | ssoId: $ssoId | managedBy: $managedBy"
      }

    implicit val ConvDataLogShow: LogShow[ConversationData] =
      LogShow.createFrom { c =>
        import c._
        l"ConversationData(id: $id | remoteId: $remoteId | name: $name | convType: $convType | team: $team | lastEventTime: $lastEventTime | muted: $muted | muteTime: $muteTime | archived: $archived | archivedTime: $archiveTime | lastRead: $lastRead | cleared: $cleared | unreadCount: $unreadCount)"
      }

    implicit val MentionShow: LogShow[Mention] =
      createFrom { m =>
        import m._
        l"Mention(userId: $userId, start: $start, length: $length)"
      }

    implicit val AssetLogShow: LogShow[Asset[AssetDetails]] =
      LogShow.createFrom { a =>
        import a._
        l"Asset(id: $id | token: $token | sha: $sha | encryption: $encryption | localSource: $localSource | preview: $preview | details: $details | convId: $convId)"
      }

    implicit val AssetDataLogShow: LogShow[AssetData] =
      LogShow.createFrom { c =>
        import c._
        l"""
           |AssetData(id: $id | mime: $mime | sizeInBytes: $sizeInBytes | status: $status | source: $source
           |  rId: $remoteId | token: $token | otrKey: $otrKey | preview: $previewId)
        """.stripMargin
      }

    implicit val TrackDataLogShow: LogShow[TrackData] =
      LogShow.createFrom { d =>
        import d._
        l"""
            |TrackData(
            | provider: $provider,
            | title: ${redactedString(title)},
            | artist: $artist,
            | linkUrl: ${new URI(linkUrl)},
            | artwork: $artwork,
            | duration: $duration,
            | streamable: $streamable,
            | streamUrl: ${streamUrl.map(new URI(_))},
            | previewUrl: ${previewUrl.map(new URI(_))},
            | expires: $expires)
          """.stripMargin
      }

    implicit val ArtistDataLogShow: LogShow[ArtistData] =
      LogShow.createFrom { d =>
        l"ArtistData(name: ${redactedString(d.name)}, avatar: ${d.avatar})"
      }

    implicit val NotificationDataLogShow: LogShow[NotificationData] =
      LogShow.createFrom { n =>
        import n._
        l"NotificationData(id: $id | conv: $conv | user: $user | msgType: $msgType | time: $time | isReply: $isReply | isSelfMentioned: $isSelfMentioned)"
      }

    implicit val TeamDataLogShow: LogShow[TeamData] =
      LogShow.createFrom { n =>
        import n._
        l"""
           |TeamData(id: $id | name: $name | creator: $creator)
        """.stripMargin
      }

    implicit val VideoStateLogShow: LogShow[VideoState] = logShowWithToString

    implicit val CallInfoLogShow: LogShow[CallInfo] =
      LogShow.createFrom { n =>
        import n._
        l"""
           |CallInfo(account: $account | convId: $convId | caller: $caller | state: $state | prevState: $prevState | isCbrEnabled: $isCbrEnabled
           |  isGroup: $isGroup | shouldRing: $shouldRing |  muted: $muted | startedAsVideoCall: $startedAsVideoCall | videoSendState: $videoSendState
           |  others: $others | maxParticipants: $maxParticipants |
           |  startTime: $startTime | joinedTime: $joinedTime | estabTime: $estabTime | endTime: $endTime
           |  endReason: ${endReason.map(r => showString(reasonString(r)))} | wasVideoToggled: $wasVideoToggled | hasOutstandingMsg: ${outstandingMsg.isDefined})
        """.stripMargin
      }

    implicit val AccountDataLogShow: LogShow[AccountData] =
      LogShow.createFrom { u =>
        import u._
        l"""AccountData(
           | id: $id
           | teamId: $teamId
           | cookie: $cookie
           | accessToken: $accessToken
           | pushToken: $pushToken
           | password: $password
           | ssoId: $ssoId)"""
      }

    implicit val ErrorResponseLogShow: LogShow[ErrorResponse] = LogShow.create(_.toString)

    // Global Record and Play Service

    implicit val StateLogShow: LogShow[GlobalRecordAndPlayService.State] =
      LogShow.createFrom {
        case Idle => l"Idle"
        case Playing(player, key) => l"Playing(player: $player, key: $key)"
        case Paused(player, key, playhead, transient) => l"Paused(player: $player, key: $key, playhead: $playhead, transient: $transient)"
        case Recording(_, key, start, entry, promisedAsset) => l"Recording(key: $key, start: $start, entry: $entry)"
      }

    implicit val PlayerLogShow: LogShow[Player] = LogShow.create(_.getClass.getName)

    implicit val MediaKeyLogShow: LogShow[GlobalRecordAndPlayService.MediaKey] =
      LogShow.createFrom {
        case AssetMediaKey(id) => l"AssetMediaKey(id: $id)"
        case UriMediaKey(uri) => l"UriMediaKey(uri: $uri)"
      }

    implicit val ErrorLogShow: LogShow[GlobalRecordAndPlayService.Error] =
      LogShow.create { e =>
        s"Error(message: ${e.message})"
      }

    implicit val MediaPointerLogShow: LogShow[GlobalRecordAndPlayService.MediaPointer] =
      LogShow.createFrom { p =>
        l"MediaPointer(content: ${p.content}, playhead: ${p.playhead})"
      }

    implicit val ContentLogShow: LogShow[GlobalRecordAndPlayService.Content] =
      LogShow.createFrom {
        case UnauthenticatedContent(uri) => l"UnauthenticatedContent(uri: $uri)"
        case PCMContent(file) => l"PCMContent(file: $file)"
      }

    // Sync Job

    implicit val SyncJobLogShow: LogShow[SyncJob] =
      LogShow.createFrom { j =>
        import j._
        l"""SyncJob(
            | id: $id
            | request: $request
            | dependsOn: $dependsOn
            | priority: $priority
            | timestamp: $timestamp
            | startTime: $startTime
            | attempts: $attempts
            | offline: $offline
            | state: $state
            | error: ${j.error})""".stripMargin
      }

    implicit val SyncRequestLogShow: LogShow[SyncRequest] =
      LogShow.createFrom { r =>
        l"SyncRequest(cmd: ${r.cmd})"
      }

    implicit val SyncResultLogShow: LogShow[SyncResult] =
      LogShow.createFrom {
        case SyncResult.Success => l"Success"
        case SyncResult.Failure(error) => l"Failure(error: $error)"
        case SyncResult.Retry(error) => l"Retry(error: $error)"
      }

    implicit val SyncCommandLogShow: LogShow[SyncCommand] = LogShow.create(_.name())
    implicit val SyncStateLogShow: LogShow[SyncState] = LogShow.create(_.name())

    //Events

    implicit val EventLogShow: LogShow[Event] = logShowWithHash

    implicit val OtrErrorLogShow: LogShow[OtrError] =
      LogShow.createFrom {
        case Duplicate => l"Duplicate"
        case DecryptionError(msg, from, sender) => l"DecryptionError(msg: ${showString(msg)} | from: $from | sender: $sender)"
        case IdentityChangedError(from, sender) => l"IdentityChangedError(from: $from | sender: $sender)"
        case UnknownOtrErrorEvent(json) => l"UnknownOtrErrorEvent(json: $json)"
      }

    implicit val OtrErrorEventLogShow: LogShow[OtrErrorEvent] =
      LogShow.createFrom { e =>
        import e._
        l"OtrErrorEvent(convId: $convId | time: $time | from: $from | error: ${e.error})"
      }

    //Protos

    implicit val GenericMessageLogShow: LogShow[GenericMessage] = LogShow.create { m =>
      m.getContentCase
      s"GenericMessage(messageId: ${sha2(m.messageId)} | contentCase: ${m.getContentCase})"
    }

    implicit val LocationLogShow: LogShow[Location] = LogShow.logShowWithHash

    implicit val ReadReceiptShow: LogShow[ReadReceipt] = LogShow.createFrom { r =>
      import r._
      l"ReadReceipt($message, $user, $timestamp)"
    }

    implicit val ReceiptType: LogShow[ReceiptType] = logShowWithToString

    // System Types

    implicit val ThreadLogShow: LogShow[Thread] =
      LogShow.create { t =>
        import t._
        s"Thread(id: $getId, name: $getName, priority: $getPriority, state: $getState)"
      }

    implicit val LocaleLogShow: LogShow[Locale] = logShowWithHash
  }


  trait CanBeShown {
    def showSafe: String
    def showUnsafe: String
  }

  class CanBeShownImpl[T](value: T)(implicit logShow: LogShow[T]) extends CanBeShown {
    override def showSafe: String   = logShow.showSafe(value)
    override def showUnsafe: String = logShow.showUnsafe(value)
  }

  import scala.language.implicitConversions
  implicit def asLogShowArg[T: LogShow](value: T): CanBeShownImpl[T] = new CanBeShownImpl[T](value)

  class Log(stringParts: Iterable[String], args: Iterable[CanBeShown], private val strip: Boolean = false) {

    def buildMessageSafe: String   = applyModifiers(intersperse(stringParts.iterator, args.iterator.map(_.showSafe)))
    def buildMessageUnsafe: String = applyModifiers(intersperse(stringParts.iterator, args.iterator.map(_.showUnsafe)))

    private def applyModifiers(str: String): String =
      if (strip) str.stripMargin else str

    def stripMargin: Log = new Log(stringParts, args, strip = true)

    @tailrec
    private def intersperse(xs: Iterator[String], ys: Iterator[String], acc: StringBuilder = new StringBuilder): String = {
      if (xs.hasNext) { acc.append(xs.next()); intersperse(ys, xs, acc) }
      else acc.toString()
    }
  }

  implicit class LogHelper(val sc: StringContext) extends AnyVal {
    def l(args: CanBeShown*): Log = new Log(sc.parts.toList, args)
  }

  def error(log: Log, cause: Throwable)(implicit tag: LogTag): Unit = InternalLog.log(log, cause, Error, tag)
  def error(log: Log)(implicit tag: LogTag): Unit                   = InternalLog.log(log, Error, tag)
  def warn(log: Log, cause: Throwable)(implicit tag: LogTag): Unit  = InternalLog.log(log, cause, Warn, tag)
  def warn(log: Log)(implicit tag: LogTag): Unit                    = InternalLog.log(log, Warn, tag)
  def info(log: Log)(implicit tag: LogTag): Unit                    = InternalLog.log(log, Info, tag)
  def debug(log: Log)(implicit tag: LogTag): Unit                   = InternalLog.log(log, Debug, tag)
  def verbose(log: Log)(implicit tag: LogTag): Unit                 = InternalLog.log(log, Verbose, tag)

  class ShowString(val value: String) extends AnyVal {
    override def toString: LogTag = value
  }

  // Use to hide string content only in public logs.
  class RedactedString(val value: String)

//  @deprecated("Only for legacy support. Will be removed after migration", " ")
  def showString(str: String): ShowString = new ShowString(str)
  def redactedString(str: String): RedactedString = new RedactedString(str)
}
