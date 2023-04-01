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

package com.pedjak.gradle.plugins.dockerizedtest;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import org.gradle.StartParameter;
import org.gradle.api.*;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.concurrent.DefaultExecutorFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.operations.BuildOperationExecutor;;
import org.gradle.internal.remote.Address;
import org.gradle.internal.remote.ConnectionAcceptor;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.remote.internal.ConnectCompletion;
import org.gradle.internal.remote.internal.IncomingConnector;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.internal.remote.internal.hub.MessageHubBackedObjectConnection;
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress;
import org.gradle.internal.time.Clock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.JavaExecHandleFactory;
import org.gradle.process.internal.health.memory.MemoryManager;
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory;

import javax.inject.Inject;

class DockerizedTestPlugin implements Plugin<Project> {

    private final String supportedVersion = "7.6";
    private final String currentUser;
    private final MessageServer messagingServer;
    private static final WorkerSemaphore workerSemaphore = new DefaultWorkerSemaphore();
    private final MemoryManager memoryManager = new NoMemoryManager();

    @Inject
    DockerizedTestPlugin(MessageServer messagingServer) {
        this.currentUser = SystemUtils.IS_OS_WINDOWS ? "0" : System.getenv("UID").trim();
        this.messagingServer = new MessageServer(messagingServer.connector, messagingServer.executorFactory);
    }

    void configureTest(Project project, Test test) {
        DockerizedTestExtension ext = test.getExtensions().create("docker", DockerizedTestExtension.class);
        StartParameter startParameter = project.getGradle().getStartParameter();

        Map<String, String> volumesMap = new HashMap<>();
        volumesMap.put(startParameter.getGradleUserHomeDir().toString(), startParameter.getGradleUserHomeDir().toString());
        volumesMap.put(project.getProjectDir().toString(), project.getProjectDir().toString());
        //
        //        throw new RuntimeException()
        ext.setVolumes(volumesMap);
        ext.setUser(currentUser);
        test.doFirst(first -> {
            final DockerizedTestExtension docker = (DockerizedTestExtension) test.getExtensions().getByName("docker");
            if (docker.getImage() != null) {
                workerSemaphore.applyTo(test.getProject());
                test.setProperty("testExecutor", new TestExecutor(newProcessBuilderFactory(project, docker, test.processBuilderFactory,
                        startParameter.getGradleUserHomeDir(), services), actorFactory, moduleRegistry, services.get(BuildOperationExecutor),
                        services.get(Clock), services.get(WorkerLeaseService)));

                if (docker.getClient() == null) {
                    docker.setClient(createDefaultClient());
                }
            }
        });
        // refactor
        //  doFirst {
        //      def extension = test.extensions.docker;
        //
        //      if (extension?.image) {
        ////                println("XXXXXXXX" + test)
        ////                println("XXXXXXXX" + services.get(FileCollectionFactory).toString())
        //        workerSemaphore.applyTo(test.project)
        //        test.testExecuter = new TestExecutor(newProcessBuilderFactory(project, extension, test.processBuilderFactory, startParameter
        //        .gradleUserHomeDir, services), actorFactory, moduleRegistry, services.get(BuildOperationExecutor), services.get(Clock), services.get
        //        (WorkerLeaseService));
        //
        //        if (!extension.client) {
        //          extension.client = createDefaultClient();
        //        }
        //      }
        //
        //    }
    }

    static DockerClient createDefaultClient() {
        return DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder().build())
                // TODO?
                //                    .withDockerCmdExecFactory(new DockerHttpClient()new NettyDockerCmdExecFactory())
                .build();
    }

    @Override
    public void apply(Project project) {

        boolean unsupportedVersion = new ComparableVersion(project.getGradle().getGradleVersion()).compareTo(new ComparableVersion(supportedVersion)) < 0
        if (unsupportedVersion) {
            throw new GradleException("dockerized-test plugin requires Gradle ${supportedVersion}+");
        }

        project.getTasks().withType(Test.class).forEach({test -> configureTest(project, test)});
        project.getTasks().whenTaskAdded((Task) task -> {
            if (task instanceof Test) {
                configureTest(project, task)
            }
                }
        );
    }

    private void newProcessBuilderFactory(Project project, extension, defaultProcessBuilderFactory, gradleUserHome, services) {

        ExecutorFactory executorFactory = new DefaultExecutorFactory();
        Executor executor = executorFactory.create("Docker container link");
        BuildCancellationToken buildCancellationToken = new DefaultBuildCancellationToken();

        def execHandleFactory = [newJavaExec:
        { ->
            new DockerizedJavaExecHandleBuilder(extension,
                    project.fileResolver,
                    executor,
                    buildCancellationToken,
                    workerSemaphore,
                    services.get(FileCollectionFactory),
                    services.get(ObjectFactory),
                    services.get(TemporaryFileProvider),
                    services.get(PathToFileResolver)
            );
        }]as JavaExecHandleFactory;
        return new DefaultWorkerProcessFactory(defaultProcessBuilderFactory.loggingManager,
                messagingServer,
                defaultProcessBuilderFactory.workerImplementationFactory.classPathRegistry,
                defaultProcessBuilderFactory.idGenerator,
                gradleUserHome,
                defaultProcessBuilderFactory.workerImplementationFactory.temporaryFileProvider,
                execHandleFactory,
                new DockerizedJvmVersionDetector(extension),
                defaultProcessBuilderFactory.outputEventListener,
                memoryManager
        );
    }

    class MessageServer implements MessagingServer {
        IncomingConnector connector;
        ExecutorFactory executorFactory;

        MessageServer(IncomingConnector connector, ExecutorFactory executorFactory) {
            this.connector = connector;
            this.executorFactory = executorFactory;
        }

        @Override
        public ConnectionAcceptor accept(Action<ObjectConnection> action) {
            return new ConnectionAcceptorDelegate(connector.accept(new ConnectEventAction(action, executorFactory), true));
        }

    }

    static class ConnectEventAction implements Action<ConnectCompletion> {
        private final Action<ObjectConnection> action;
        private final ExecutorFactory executorFactory;

        ConnectEventAction(Action<ObjectConnection> action, ExecutorFactory executorFactory) {
            this.executorFactory = executorFactory;
            this.action = action;
        }

        @Override
        public void execute(ConnectCompletion completion) {
            action.execute(new MessageHubBackedObjectConnection(executorFactory, completion));
        }
    }

    static class ConnectionAcceptorDelegate implements ConnectionAcceptor {

        MultiChoiceAddress address;

        final ConnectionAcceptor delegate;

        ConnectionAcceptorDelegate(ConnectionAcceptor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Address getAddress() {
            synchronized (delegate) {
                if (address == null) {
                    try {
                        final List<InetAddress> remoteAddresses = NetworkInterface.networkInterfaces().filter(it -> {
                            try {
                                return it.isUp() && !it.isLoopback();
                            } catch (SocketException e) {
                                throw new RuntimeException(e);
                            }
                        }).map(it -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(it.getInetAddresses().asIterator(), Spliterator.ORDERED), false).collect(Collectors.toList())).flatMap(Collection::stream).collect(Collectors.toList());
                        MultiChoiceAddress original = address;
                        address = new MultiChoiceAddress(original.getCanonicalAddress(), original.getPort(), remoteAddresses);
                    } catch (SocketException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return address;
        }

        @Override
        public void requestStop() {
            delegate.requestStop();
        }

        @Override
        public void stop() {
            delegate.stop();
        }
    }

}