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
package com.waz

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.serialization.Serialization
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.waz.provision.ActorMessage.{ReleaseRemotes, SpawnRemoteDevice, WaitUntilRegistered}
import com.waz.provision._
import com.waz.service._
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.{PartialFunction => =/>}

trait ActorSystemSpec extends BeforeAndAfterAll { suite: Suite with Alerting with Informing =>

  implicit val timeout: com.waz.RobolectricUtils.Timeout = 120.seconds
  implicit val akkaTimeout = Timeout(timeout)

  def testBackend: BackendConfig

  //Create a CoordinatorActor with its system
  lazy val coordinatorSystem: ActorSystem = setUpCoordinatorSystem()
  lazy val coordinatorActor: ActorRef = setUpCoordinatorActor()

  def setUpCoordinatorSystem(configFileName: String = "actor_coordinator"): ActorSystem = {
    val config = ConfigFactory.load(configFileName)
    ActorSystem.create("CoordinatorSystem", config)
  }

  def setUpCoordinatorActor(): ActorRef = {
    coordinatorSystem.actorOf(Props[CoordinatorActor], "coordinatorActor")
  }

  def registerProcess(processName: String, maxWait: FiniteDuration = 30.seconds, backend: BackendConfig = testBackend, otrOnly: Boolean = false)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef = {
    val serialized = Serialization.serializedActorPath(coordinatorActor)
    RoboProcess[RemoteProcess](processName, serialized, backend.environment, otrOnly.toString)

    Await.result(coordinatorActor.ask(WaitUntilRegistered(processName))(maxWait).mapTo[ActorRef], maxWait)
  }

  def awaitRemote(processName: String, maxWait: FiniteDuration = 30.seconds)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef =
    Await.result(coordinatorActor.ask(WaitUntilRegistered(processName))(maxWait).mapTo[ActorRef], maxWait)

  def registerDevice(deviceName: String, remoteProcess: ActorRef): ActorRef = spawnDeviceOnProcess(deviceName, remoteProcess)

  def spawnDeviceOnProcess(deviceName: String, remoteProcessActor: ActorRef, maxWait: FiniteDuration = 30.seconds)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef = {
    assert(remoteProcessActor != null, "Requires the remote process actor to be set up properly first")
    Await.result(remoteProcessActor.ask(SpawnRemoteDevice("", deviceName))(maxWait).mapTo[ActorRef], maxWait)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    shutDownAllSystems()
  }

  def shutDownAllSystems(): Unit = {
    coordinatorActor ! ReleaseRemotes
    coordinatorSystem.shutdown()
  }
}

/**
 * [[ThreadActorSpec]]s produce multiple instances of SE per remote JVM process. This makes them quicker and easier
 * on resources, but unfortunately there's no way to split up the log files of each SE instance, making debugging
 * very difficult. See the [[ProcessActorSpec]]s below to get a debug file per SE instance.
 */
trait ThreadActorSpec extends ActorSystemSpec { suite: Suite with Alerting with Informing =>
  val otrOnly: Boolean = true
  lazy val remoteProcessActor = registerProcess(this.getClass.getSimpleName, otrOnly = otrOnly)

  def registerDevice(deviceName: String): ActorRef =
    registerDevice(deviceName, remoteProcessActor)
}

/**
 * [[ProcessActorSpec]]s create a new JVM process per device instance, each with their own log file located at:
 * target/logcat/deviceName. These will be more useful for debugging what is going wrong with any potential
 * problems on the remote.
 */
trait ProcessActorSpec extends ActorSystemSpec { suite: Suite with Alerting with Informing =>
  val otrOnly: Boolean = true

  def registerDevice(deviceName: String): ActorRef =
    registerDevice(deviceName, registerProcess(s"${this.getClass.getSimpleName}_$deviceName", otrOnly = otrOnly))

  def registerDevice(deviceName: String, otrOnly: Boolean): ActorRef =
    registerDevice(deviceName, registerProcess(s"${this.getClass.getSimpleName}_$deviceName", otrOnly = otrOnly))
}