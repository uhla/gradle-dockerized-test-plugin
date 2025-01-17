/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.*
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.api.model.ObjectFactory
import org.gradle.initialization.DefaultBuildCancellationToken
import org.gradle.internal.concurrent.DefaultExecutorFactory
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.file.PathToFileResolver
import org.gradle.internal.id.UUIDGenerator
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.remote.Address
import org.gradle.internal.remote.ConnectionAcceptor
import org.gradle.internal.remote.MessagingServer
import org.gradle.internal.remote.ObjectConnection
import org.gradle.internal.remote.internal.ConnectCompletion
import org.gradle.internal.remote.internal.IncomingConnector
import org.apache.commons.lang3.SystemUtils
import org.apache.maven.artifact.versioning.ComparableVersion
import org.gradle.internal.remote.internal.hub.MessageHubBackedObjectConnection
import org.gradle.internal.remote.internal.inet.InetAddressFactory
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress
import org.gradle.internal.remote.internal.inet.TcpIncomingConnector
import org.gradle.internal.time.Clock
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.process.internal.JavaExecHandleFactory
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory

import javax.inject.Inject

class DockerizedTestPlugin implements Plugin<Project> {

  def supportedVersion = '7.6'
  def currentUser
  def messagingServer
  def addressFactory
  def static workerSemaphore = new DefaultWorkerSemaphore()
  def memoryManager = new NoMemoryManager()

  @Inject
  DockerizedTestPlugin(MessagingServer messagingServer, InetAddressFactory addressFactory) {
    this.currentUser = SystemUtils.IS_OS_WINDOWS ? "0" : "id -u".execute().text.trim()
    this.messagingServer = new MessageServer(messagingServer.connector, messagingServer.executorFactory)
    this.addressFactory = new InetAddressFactoryWildcardDelegate(addressFactory)
  }
// TODO parallel forks vs parallel tasks - look into

  // TODO verify if this is ok to keep, works fine this way (basically overriding localBinding to wildcard binding to work with docker in bridge mode
  class InetAddressFactoryWildcardDelegate extends InetAddressFactory {

    @Delegate
    final InetAddressFactory delegate

    InetAddressFactoryWildcardDelegate(InetAddressFactory delegate) {
      this.delegate = delegate;
    }

    InetAddress getLocalBindingAddress(){
      // configurable?
//      InetAddress.getByName("172.31.176.1")
      getWildcardBindingAddress()
    }

  }

  void configureTest(project, test) {
    def ext = test.extensions.create("docker", DockerizedTestExtension, [] as Object[])
    def startParameter = project.gradle.startParameter

//
//        throw new RuntimeException()


    ext.volumes = ["$startParameter.gradleUserHomeDir": "$startParameter.gradleUserHomeDir",
                   "$project.projectDir"              : "$project.projectDir"]
    ext.user = currentUser
    test.doFirst {
      def extension = test.extensions.docker

      if (extension?.image) {
        workerSemaphore.applyTo(test.project)
        test.testExecuter = new TestExecuter(newProcessBuilderFactory(project, extension, test.processBuilderFactory, startParameter.gradleUserHomeDir, services), actorFactory, moduleRegistry, services.get(BuildOperationExecutor), services.get(Clock), services.get(WorkerLeaseService));

        if (!extension.client) {
          extension.client = createDefaultClient(extension.socketAddress)
        }
        def pullCmd = ((DockerClient) extension.client).pullImageCmd(extension.image)
        pullCmd.exec(new PullImageResultCallback()).awaitCompletion()
      }

    }
  }

  static DockerClient createDefaultClient(String socketAddress) {
    // on windows use podman docker npipe by default, on linux use docker socker
    socketAddress ?= Os.isFamily(Os.FAMILY_WINDOWS) ? 'npipe:////./pipe/docker_engine' : 'unix:///var/run/docker.sock'
    DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(socketAddress).build())
        .withDockerHttpClient(new ApacheDockerHttpClient.Builder().dockerHost(URI.create(socketAddress)).build())
        .build()
  }

  void apply(Project project) {

    boolean unsupportedVersion = new ComparableVersion(project.gradle.gradleVersion).compareTo(new ComparableVersion(supportedVersion)) < 0
    if (unsupportedVersion) throw new GradleException("dockerized-test plugin requires Gradle ${supportedVersion}+")

    project.tasks.withType(Test).each { test -> configureTest(project, test) }
    project.tasks.whenTaskAdded { task ->
      if (task instanceof Test) configureTest(project, task)
    }
  }

  def newProcessBuilderFactory(project, extension, defaultProcessBuilderFactory, gradleUserHome, services) {

    def executorFactory = new DefaultExecutorFactory()
    def executor = executorFactory.create("Docker container link")
    def buildCancellationToken = new DefaultBuildCancellationToken()

    def execHandleFactory = [newJavaExec: { ->
      new DockerizedJavaExecHandleBuilder(extension,
          project.fileResolver,
          executor,
          buildCancellationToken,
          workerSemaphore,
          services.get(FileCollectionFactory),
          services.get(ObjectFactory),
          services.get(TemporaryFileProvider),
          services.get(PathToFileResolver)
      )
    }] as JavaExecHandleFactory
    new DefaultWorkerProcessFactory(defaultProcessBuilderFactory.loggingManager,
        messagingServer,
        defaultProcessBuilderFactory.workerImplementationFactory.classPathRegistry,
        defaultProcessBuilderFactory.idGenerator,
        gradleUserHome,
        defaultProcessBuilderFactory.workerImplementationFactory.temporaryFileProvider,
        execHandleFactory,
        new DockerizedJvmVersionDetector(extension),
        defaultProcessBuilderFactory.outputEventListener,
        memoryManager
    )
  }

  class MessageServer implements MessagingServer {
    IncomingConnector connector;
    ExecutorFactory executorFactory;

    MessageServer(IncomingConnector connector, ExecutorFactory executorFactory) {
      this.connector = connector;
      this.executorFactory = executorFactory;
    }

    ConnectionAcceptor accept(Action<ObjectConnection> action) {
      TcpIncomingConnector myconnector = new TcpIncomingConnector(executorFactory,addressFactory,new UUIDGenerator())
      return new ConnectionAcceptorDelegate(myconnector.accept(new ConnectEventAction(action, executorFactory), true))
    }


  }

  class ConnectEventAction implements Action<ConnectCompletion> {
    def action;
    def executorFactory;

    ConnectEventAction(Action<ObjectConnection> action, executorFactory) {
      this.executorFactory = executorFactory
      this.action = action
    }

    void execute(ConnectCompletion completion) {
      action.execute(new MessageHubBackedObjectConnection(executorFactory, completion));
    }
  }

  class ConnectionAcceptorDelegate implements ConnectionAcceptor {

    MultiChoiceAddress address

    @Delegate
    final ConnectionAcceptor delegate

    ConnectionAcceptorDelegate(ConnectionAcceptor delegate) {
      this.delegate = delegate
    }

    Address getAddress() {
      synchronized (delegate) {
        if (address == null) {

          def remoteAddresses = NetworkInterface.networkInterfaces.findAll { it.up && !it.loopback }*.inetAddresses*.collect { it }.flatten()
          // host.containers.internal podamn TODO in windows - see notes_improvements.txt
//          remoteAddresses.add(InetAddress.getByName("10.88.0.1"))
          // TODO this won't work as it's executed on a host
//          remoteAddresses.add(InetAddress.getByName("host.containers.internal"))
          def original = delegate.address
          address = new MultiChoiceAddress(original.canonicalAddress, original.port, remoteAddresses)
        }
      }

      address
    }
  }

}