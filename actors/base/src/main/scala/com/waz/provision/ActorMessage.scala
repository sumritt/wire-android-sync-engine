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

import akka.actor.ActorRef
import com.waz.api.Message
import com.waz.api.impl.AccentColor
import com.waz.model._
import com.waz.threading.QueueReport
import org.threeten.bp.Instant

import scala.concurrent.duration.FiniteDuration

trait ActorMessage

trait LogisticalMessage extends ActorMessage

trait ResponseMessage extends LogisticalMessage


object ActorMessage {

  //////////////////////////////////////////////////////////////////////////////
  // LOGISTICAL MESSAGES - for setting up and communicating between actors
  //////////////////////////////////////////////////////////////////////////////
  /**
   * A message used to release all remote process actors. Pass this message to the [[CoordinatorActor]], and it
   * will pass it on to any connected remotes, which may subsequently shut down their host processes (up to remote actor).
   */
  case object ReleaseRemotes extends LogisticalMessage

  /**
    * Sent from the [[CoordinatorActor]] to each registered [[RemoteProcessActor]] upon receiving a [[ReleaseRemotes]]
    * [[RemoteProcessActor]] which was created with specific coordinator will stop and [[RemoteProcess]] will die.
    * If remote actor was started without specific coordinator it will switch to waiting for next coordinator.
   */
  case object ReleaseProcess extends LogisticalMessage

  /**
   * A response message from the CoordinatorActor whenever a command is sent, but there are no registered
   * remote processes.
   */
  case object NoRemotes extends LogisticalMessage

  /**
   * Sent automatically to the [[CoordinatorActor]] by a [[RemoteProcessActor]] as soon as the remote process
   * is established and the actor is ready. With this message, the [[CoordinatorActor]] gets a reference to the remote.
   * @param processName The name that the remote process wishes to be aliased as - makes debugging easier
   */
  case class RegisterProcess(processName: String) extends LogisticalMessage

  /**
   * A message to be sent to any [[RemoteProcessActor]] asking it to create a new "Device", at which point a
   * new instance of SE will be created on the host process. The [[RemoteProcessActor]] will then forward the
   * [[ActorRef]] back to whatever sender asked for the device to be spawned
   *
   * @param processName This message can also be passed to a [[CoordinatorActor]], at which point the [[processName]]
   *                    will be used to look up the reference to the [[RemoteProcessActor]]
   * @param deviceName The alias that we want to give to the spawned 'device'. This is just to help debugging
   */
  case class SpawnRemoteDevice(processName: String, deviceName: String) extends LogisticalMessage

  /**
   * To be sent to a [[CoordinatorActor]] to wait on and fetch the [[ActorRef]] of a newly created process.
   * The usual flow is that the main (testing) process creates an [[akka.actor.ActorSystem]] with a
   * [[CoordinatorActor]], and then starts a new [[RemoteProcess]], specifying the [[processName]]. Then the testing
   * process needs to ask the [[CoordinatorActor]] with a [[WaitUntilRegistered()]] message, which should eventually
   * return our [[RemoteProcessActor]]s [[ActorRef]].
   * @param processName the name of the process that was just spawned
   */
  case class WaitUntilRegistered(processName: String) extends LogisticalMessage

  /**
   * Sent to the [[CoordinatorActor]] to return a list of registered remote processes
   */
  case object ListRemotes extends LogisticalMessage

  /**
   * The response message for [[ListRemotes]]
   * @param remotes a list of the [[RemoteProcessActor]]s [[ActorRef]]s and their process names
   */
  case class ListRemotes(remotes: Map[ActorRef, String]) extends LogisticalMessage

  /**
   * A message to have the [[CoordinatorActor]] pass on any [[ActorMessage]] to the desired remote process by its
   * alias. Useful if we lose the reference to the [[RemoteProcessActor]] for any reason
   * @param targetProcessName the remote process alias, to who we want to send a message
   * @param msg the message
   */
  case class Command(targetProcessName: String, msg: ActorMessage) extends LogisticalMessage

  /**
   * A general utility message signifying that the command was executed successfully. Useful if we HAVE to wait
   * on the command being completed before exectution can continue
   */
  case object Successful extends ResponseMessage

  /**
   * Same as [[Successful]] but with a value that results as part of the completion, such as a [[UserId]], for example.
   * @param response successful completion value
   */
  case class Successful(response: String) extends ResponseMessage

  /**
   * A general utility message signifying that something went wrong while an Actor was carrying out a command
   * @param reason A description of the reason for failure
   */
  case class Failed(reason: String) extends ResponseMessage

  /**
   * A message used to basically ping an actor. All actors should simply return this [[ActorMessage]] to the sender
   * with the same [[msg]] as was sent to it, so that the sender knows the actor is still alive
   * @param msg Some message to be echoed by the target actor
   * @param processName The target Actor would normally respond with its own alias, just for extra certainty
   */
  case class Echo(msg: String, processName: String = "") extends ResponseMessage


  //////////////////////////////////////////////////////////////////////////////
  // COMMAND MESSAGES - the things that we actually want our "Devices" to perform.
  //////////////////////////////////////////////////////////////////////////////

  case class Login(emailLogin: String, password: String) extends ActorMessage

  case object GetUserName extends ActorMessage

  case class DeleteMessage(convId: RConvId, id: MessageId) extends ActorMessage

  case class RecallMessage(convId: RConvId, id: MessageId) extends ActorMessage

  case class SendText(remoteId: RConvId, msg: String) extends ActorMessage

  case class UpdateText(msgId: MessageId, msg: String) extends ActorMessage

  case class SendImage(remoteId: RConvId, path: String) extends ActorMessage

  case class SendGiphy(remoteId: RConvId, searchQuery: String) extends ActorMessage

  case class SendFile(remoteId: RConvId, filePath: String, mime: String) extends ActorMessage

  case class SendLocation(remoteId: RConvId, lon: Float, lat: Float, name: String, zoom: Int) extends ActorMessage

  case class ClearConversation(remoteId: RConvId) extends ActorMessage

  case class Knock(remoteId: RConvId) extends ActorMessage

  case class Typing(remoteId: RConvId) extends ActorMessage

  case class SetEphemeral(remoteId: RConvId, ephemeral: Option[FiniteDuration]) extends ActorMessage

  case class MarkEphemeralRead(convId: RConvId, msgId: MessageId) extends ActorMessage

  case class ClearTyping(remoteId: RConvId) extends ActorMessage

  case class ArchiveConv(remoteId: RConvId) extends ActorMessage

  case class UnarchiveConv(remoteId: RConvId) extends ActorMessage

  case class MuteConv(remoteId: RConvId) extends ActorMessage

  case class UnmuteConv(remoteId: RConvId) extends ActorMessage

  case class SetMessageReaction(remoteId: RConvId, messageId: MessageId, action: Liking.Action) extends ActorMessage

  case class UpdateProfileUserName(userName : String) extends ActorMessage

  case object AwaitSyncCompleted extends ActorMessage

  case class SetDeviceLabel(label: String) extends ActorMessage

  case class GetDeviceId() extends ActorMessage

  case class GetDeviceFingerPrint() extends ActorMessage

  case class MessageInfo(id: MessageId, tpe: Message.Type, time: Instant)

  case class ConvMessages(msgs: Array[MessageInfo]) extends ResponseMessage

  case class GetMessages(remoteId: RConvId) extends ActorMessage

  case class SetStatus(status: String) extends ActorMessage
}
